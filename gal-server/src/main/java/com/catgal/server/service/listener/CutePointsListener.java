/*
 * 阅读说明
 * 排空旧签到与抽奖积分事件，使用业务号去重后原子更新旧 Redis 余额与累计统计。
 * ACTIVE 不启动本监听器，意外进入的旧事件也会被数据库切换屏障拒绝。
 */
package com.catgal.server.service.listener;

import Message.UserCutePointsMessage;
import com.catgal.common.exception.BusinessException;
import com.catgal.common.utils.StringUtils;
import com.catgal.server.service.IUserCutePointsService;
import com.catgal.server.service.IRewardControlService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

import static com.catgal.common.constants.MqConstants.Exchange.CUTE_POINT_EXCHANGE;
import static com.catgal.common.constants.MqConstants.Key.DRAW_CONSUME_CUTE_POINTS_KEY;
import static com.catgal.common.constants.MqConstants.Key.SIGN_CUTE_POINTS_KEY;
import static com.catgal.common.constants.RedisConstant.*;
import static com.catgal.server.redis.RedisScriptLoader.load;

@RequiredArgsConstructor
@Slf4j
@Component
public class CutePointsListener {

    private static final Duration IDEMPOTENCY_TTL = Duration.ofDays(30);
    private static final DefaultRedisScript<Long> CHANGE_POINTS_ONCE_SCRIPT =
            load("lua/cute-points/change-once.lua", Long.class);

    private final StringRedisTemplate redisTemplate;
    private final IUserCutePointsService userCutePointsService;
    private final IRewardControlService control;

    @RabbitListener(id = "legacyRewardPoints", containerFactory = "rewardListenerContainerFactory",
            autoStartup = "#{!@rewardControlServiceImpl.isActive()}", bindings = {
            @QueueBinding(
                    value = @Queue(name = "cute.points.sign_in.queue", durable = "true"),
                    exchange = @Exchange(name = CUTE_POINT_EXCHANGE, type = ExchangeTypes.TOPIC),
                    key = SIGN_CUTE_POINTS_KEY
            ),
            @QueueBinding(
                    value = @Queue(name = "cute.points.draw_consumed.queue", durable = "true"),
                    exchange = @Exchange(name = CUTE_POINT_EXCHANGE, type = ExchangeTypes.TOPIC),
                    key = DRAW_CONSUME_CUTE_POINTS_KEY
            )
    })
    // 校验消息并初始化缓存，再由 Lua 原子执行去重、余额变更和标脏。
    // 去重标记有效期由 IDEMPOTENCY_TTL 控制，后续积分落库由独立同步任务执行。
    public void addSignInCache(UserCutePointsMessage message) {
        control.legacyDrain(() -> { applyLegacyMessage(message); return null; });
    }

    private void applyLegacyMessage(UserCutePointsMessage message) {
        validateMessage(message);
        Long userId = message.getUserId();
        String key = StringUtils.format(CUTE_POINTS_KEY, userId);
        // Lua 运行前先从 MySQL 安全初始化缓存；0 是合法余额，不能当成缓存未命中。
        userCutePointsService.getUserCutePointsCache(userId);

        String processedKey = StringUtils.format(CUTE_POINTS_MESSAGE_PROCESSED_KEY, message.getBizId());
        // 脚本先验证全部键类型、三个计数字段与 INT 范围，再写去重标记、余额和脏任务。
        // Redis 脚本遇到运行错误时会保留之前的写入，写前校验用于避免这些可预见的部分成功。
        // 校验失败继续抛给现有消息重试链路，保留原缓存与业务号，便于修复状态后重投。
        Long result = redisTemplate.execute(
                CHANGE_POINTS_ONCE_SCRIPT,
                List.of(key, CUTE_POINTS_CHANGE_SET_KEY, processedKey),
                CUTE_POINTS_HASH_KEY,
                TOTAL_EARNED_HASH_KEY,
                String.valueOf(IDEMPOTENCY_TTL.toSeconds()),
                String.valueOf(message.getCutePointsChange()),
                TOTAL_SPENT_HASH_KEY,
                String.valueOf(userId)
        );
        if (result == null) {
            throw BusinessException.internalError("萌萌点消息处理失败");
        }
        if (result == -2L) {
            log.info("忽略重复萌萌点消息, bizId={}, userId={}", message.getBizId(), userId);
            return;
        }
        if (result == -1L) {
            throw BusinessException.conflict("萌萌点余额不足");
        }
        log.debug("萌萌点消息处理完成, bizId={}, userId={}, balance={}",
                message.getBizId(), userId, result);
    }

    private void validateMessage(UserCutePointsMessage message) {
        if (message == null || message.getUserId() == null || message.getUserId() <= 0 || message.getPointsType() == null
                || message.getCutePointsChange() == null || message.getCutePointsChange() == 0
                || StringUtils.isBlank(message.getBizId()) || message.getBizId().length() > 64) {
            throw BusinessException.badRequest("萌萌点消息缺少必要字段");
        }
        if (message.getPointsType().isEarn() != (message.getCutePointsChange() > 0)) {
            throw BusinessException.badRequest("萌萌点消息类型与变动方向不一致");
        }
    }
}
