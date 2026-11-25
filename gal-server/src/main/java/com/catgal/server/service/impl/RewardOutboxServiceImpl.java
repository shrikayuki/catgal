package com.catgal.server.service.impl;

import Message.RewardCommittedMessage;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.catgal.common.exception.BusinessException;
import com.catgal.server.domain.po.*;
import com.catgal.server.mapper.*;
import com.catgal.server.service.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import static com.catgal.server.redis.RedisScriptLoader.load;

/** 持久化租约、确认发送、消费完成是三个状态，任意一步失败均可重试同一个事件。 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RewardOutboxServiceImpl extends ServiceImpl<RewardOutboxMapper, RewardOutbox> implements IRewardOutboxService {
    public static final String EXCHANGE = "reward.committed.v1";
    public static final String ROUTING_KEY = "reward.committed";
    public static final String CACHE_PREFIX = "reward:balance:";
    private static final DefaultRedisScript<Long> PROJECT = load("lua/cute-points/project-balance.lua", Long.class);
    private final RewardOutboxMapper events;
    private final RewardRequestMapper requests;
    private final UserCutePointsMapper balances;
    private final IConfirmedRewardPublisher publisher;
    private final IRewardControlService control;
    private final StringRedisTemplate redis;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    @Override
    public void dispatchOne() {
        if (!control.isActive()) return;
        RewardOutbox event = transactions.execute(status -> {
            RewardOutbox claimed = events.claimOne();
            if (claimed == null) return null;
            String token = UUID.randomUUID().toString();
            jdbc.update("UPDATE reward_outbox SET state='IN_FLIGHT',lease_token=?,lease_until=UTC_TIMESTAMP(6)+INTERVAL 30 SECOND,"
                    + "attempts=attempts+1,update_time=UTC_TIMESTAMP(6) WHERE id=?", token, claimed.getId());
            claimed.setLeaseToken(token);
            return claimed;
        });
        if (event == null) return;
        try {
            publisher.send(EXCHANGE, ROUTING_KEY, new RewardCommittedMessage(1, event.getId()));
            // 消费者可能已经完成。条件更新不会把 COMPLETE 倒退为 SENT。
            jdbc.update("UPDATE reward_outbox SET state='SENT',lease_token=NULL,lease_until=NULL,last_error=NULL,"
                    + "next_attempt_at=UTC_TIMESTAMP(6)+INTERVAL 60 SECOND,update_time=UTC_TIMESTAMP(6) "
                    + "WHERE id=? AND lease_token=? AND projected_at IS NULL", event.getId(), event.getLeaseToken());
        } catch (RuntimeException failure) {
            int seconds = (int) Math.min(300L, 5L * (event.getAttempts() + 1L));
            jdbc.update("UPDATE reward_outbox SET state='FAILED',lease_token=NULL,lease_until=NULL,last_error=?,"
                    + "next_attempt_at=TIMESTAMPADD(SECOND,?,UTC_TIMESTAMP(6)),update_time=UTC_TIMESTAMP(6) "
                    + "WHERE id=? AND lease_token=? AND projected_at IS NULL", failure.getClass().getSimpleName(),
                    seconds, event.getId(), event.getLeaseToken());
            // 不记录密钥、消息正文或底层连接 URL。任务保留在表内，持续有限退避重试。
            log.warn("积分通知待重试, eventId={}, type={}", event.getId(), failure.getClass().getSimpleName());
        }
    }

    @Override
    public void project(Long eventId) {
        if (eventId == null || eventId <= 0) throw BusinessException.badRequest("积分事件编号无效");
        RewardOutbox event = events.selectById(eventId);
        if (event == null) throw BusinessException.notFound("积分事件不存在");
        if (event.getProjectedAt() != null) return;
        try {
            projectVerifiedSource(event);
        } catch (RuntimeException failure) {
            try {
                // 消费失败也留下持久状态。清除租约可防止发送端把 FAILED 倒退覆盖为 SENT。
                jdbc.update("UPDATE reward_outbox SET state='FAILED',lease_token=NULL,lease_until=NULL,last_error=?,"
                        + "next_attempt_at=TIMESTAMPADD(SECOND,LEAST(300,5*GREATEST(attempts,1)),UTC_TIMESTAMP(6)),"
                        + "update_time=UTC_TIMESTAMP(6) WHERE id=? AND projected_at IS NULL", failure.getClass().getSimpleName(), eventId);
            } catch (RuntimeException trackingFailure) { failure.addSuppressed(trackingFailure); }
            throw failure;
        }
    }

    private void projectVerifiedSource(RewardOutbox event) {
        RewardRequest request = requests.selectById(event.getRequestId());
        if (request == null || !"COMPLETED".equals(request.getStatus()) || Boolean.TRUE.equals(request.getLegacyImported()))
            throw BusinessException.conflict("积分事件来源不完整");
        UserCutePoints snapshot = balances.selectById(request.getUserId());
        if (snapshot == null) throw BusinessException.conflict("积分事件余额缺失");
        Long projected = redis.execute(PROJECT, List.of(CACHE_PREFIX + request.getUserId()),
                snapshot.getRevision().toString(), snapshot.getCutePoints().toString(), snapshot.getTotalEarned().toString(),
                snapshot.getTotalSpent().toString(), "300");
        if (projected == null) throw new IllegalStateException("积分投影未返回结果");
        // Redis 写入成功、MySQL 确认失败时会重投，Lua 版本校验保证乱序和重复安全。
        jdbc.update("UPDATE reward_outbox SET state='COMPLETE',projected_at=UTC_TIMESTAMP(6),lease_token=NULL,"
                + "lease_until=NULL,last_error=NULL,update_time=UTC_TIMESTAMP(6) WHERE id=? AND projected_at IS NULL", event.getId());
    }

    @Override
    public Map<String, Object> overview() {
        if (!control.schemaReady()) return Map.of("counts", List.of(), "recent", List.of());
        return Map.of("counts", jdbc.queryForList("SELECT state,COUNT(*) AS count FROM reward_outbox GROUP BY state"),
                "recent", jdbc.queryForList("SELECT CAST(id AS CHAR) AS id,state,attempts,last_error,next_attempt_at,projected_at "
                        + "FROM reward_outbox ORDER BY id DESC LIMIT 30"));
    }

    @Override
    public void retry(Long eventId) {
        if (eventId == null || eventId <= 0) throw BusinessException.badRequest("积分事件编号无效");
        int updated = jdbc.update("UPDATE reward_outbox SET next_attempt_at=UTC_TIMESTAMP(6),update_time=UTC_TIMESTAMP(6) "
                + "WHERE id=? AND projected_at IS NULL AND (lease_until IS NULL OR lease_until<UTC_TIMESTAMP(6))", eventId);
        if (updated == 0) throw BusinessException.conflict("事件已完成、正在处理或不存在");
    }
}
