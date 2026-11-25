package com.catgal.server.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.catgal.common.constants.RedisConstant;
import com.catgal.common.utils.StringUtils;
import com.catgal.server.domain.po.Resource;
import com.catgal.server.mapper.GameMapper;
import com.catgal.server.mapper.ResourceMapper;
import com.catgal.server.mapper.ResourceCountOutboxMapper;
import com.catgal.server.service.IResourceCountService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 每次按当前 status=1 的资源重算，重复任务不会累加计数。
 * Redis 故障只影响此派生任务，资源编辑/审核事务已独立提交。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ResourceCountServiceImpl implements IResourceCountService {
    private final GameMapper gameMapper;
    private final ResourceMapper resourceMapper;
    private final ResourceCountOutboxMapper outboxMapper;
    private final StringRedisTemplate redisTemplate;
    private final PlatformTransactionManager transactionManager;

    @Override
    public long pendingCount() {
        return outboxMapper.pendingCount();
    }

    @Override
    public void synchronizePending() {
        // 一轮最多100个游戏，失败按2~300秒退避。单个游戏失败不阻塞其他任务。
        for (Long gameId : outboxMapper.dueGames()) {
            Long[] version = new Long[1];
            try {
                TransactionTemplate transaction = new TransactionTemplate(transactionManager);
                transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
                transaction.executeWithoutResult(status -> {
                    // 与资源写入和运维修复共用游戏锁。整个投影过程持锁，防止旧快照晚写覆盖新值。
                    if (gameMapper.selectForUpdate(gameId) == null) return;
                    version[0] = outboxMapper.lockDueVersion(gameId);
                    if (version[0] == null) return;
                    int count = Math.toIntExact(resourceMapper.selectCount(Wrappers.<Resource>lambdaQuery()
                            .eq(Resource::getGameId, gameId).eq(Resource::getStatus, 1)));
                    if (outboxMapper.updateCount(gameId, count) != 1) throw new IllegalStateException("资源统计写入失败");
                    redisTemplate.opsForValue().set(StringUtils.format(RedisConstant.RESOURCE_COUNT_KEY, gameId), Integer.toString(count));
                    if (outboxMapper.acknowledge(gameId, version[0]) != 1) throw new IllegalStateException("资源统计版本已变化");
                });
            } catch (RuntimeException failure) {
                // 投影事务回滚后更新退避。并发资源变更会递增版本，旧失败不能覆盖新任务的到期时间。
                if (version[0] != null) outboxMapper.failed(gameId, version[0], failure.getClass().getSimpleName());
                log.warn("资源统计等待恢复, gameId={}, errorType={}", gameId, failure.getClass().getSimpleName());
            }
        }
    }
}
