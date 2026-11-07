package com.catgal.server.service.listener;

import Message.UserCutePointsMessage;
import com.catgal.common.exception.BusinessException;
import com.catgal.common.utils.StringUtils;
import com.catgal.server.service.IUserCutePointsService;
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

    @RabbitListener(bindings = {
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
    public void addSignInCache(UserCutePointsMessage message) {
        validateMessage(message);
        Long userId = message.getUserId();
        String key = StringUtils.format(CUTE_POINTS_KEY, userId);
        // Lua 运行前先从 MySQL 安全初始化缓存；0 是合法余额，不能当成缓存未命中。
        userCutePointsService.getUserCutePointsCache(userId);

        String processedKey = StringUtils.format(CUTE_POINTS_MESSAGE_PROCESSED_KEY, message.getBizId());
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
        if (message == null || message.getUserId() == null || message.getPointsType() == null
                || message.getCutePointsChange() == null || message.getCutePointsChange() == 0
                || StringUtils.isBlank(message.getBizId())) {
            throw BusinessException.badRequest("萌萌点消息缺少必要字段");
        }
        if (message.getPointsType().isEarn() != (message.getCutePointsChange() > 0)) {
            throw BusinessException.badRequest("萌萌点消息类型与变动方向不一致");
        }
    }
}
