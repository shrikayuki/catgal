package com.catgal.server.service.listener;

import Message.UserCutePointsMessage;
import com.catgal.common.utils.StringUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import static com.catgal.common.constants.MqConstants.Exchange.CUTE_POINT_EXCHANGE;
import static com.catgal.common.constants.MqConstants.Key.DRAW_CONSUME_CUTE_POINTS_KEY;
import static com.catgal.common.constants.MqConstants.Key.SIGN_CUTE_POINTS_KEY;
import static com.catgal.common.constants.RedisConstant.*;

@RequiredArgsConstructor
@Slf4j
@Component
public class CutePointsListener {

    private final StringRedisTemplate redisTemplate;

    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(name = "cute.points.sign_in.queue", durable = "true"),
            exchange = @Exchange(name = CUTE_POINT_EXCHANGE, type = ExchangeTypes.TOPIC),
            key = SIGN_CUTE_POINTS_KEY
    ))
    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(name = "cute.points.draw_consumed.queue", durable = "true"),
            exchange = @Exchange(name = CUTE_POINT_EXCHANGE, type = ExchangeTypes.TOPIC),
            key = DRAW_CONSUME_CUTE_POINTS_KEY
    ))
    public void addSignInCache(UserCutePointsMessage message) {
        Long userId = message.getUserId();
        String key = StringUtils.format(CUTE_POINTS_KEY, userId);
        Integer cutePointsChange = message.getCutePointsChange();
        redisTemplate.opsForHash().increment(key, CUTE_POINTS_HASH_KEY, cutePointsChange);
        if (cutePointsChange > 0) {
            redisTemplate.opsForHash().increment(key, TOTAL_EARNED_HASH_KEY, cutePointsChange);
        }else if (cutePointsChange < 0) {
            redisTemplate.opsForHash().increment(key, TOTAL_SPENT_HASH_KEY, -cutePointsChange);
        }

        redisTemplate.opsForSet().add(CUTE_POINTS_CHANGE_SET_KEY, userId.toString());

    }




}
