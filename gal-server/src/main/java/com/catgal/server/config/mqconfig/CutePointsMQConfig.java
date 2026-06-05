package com.catgal.server.config.mqconfig;

import com.catgal.common.constants.CutePointsTypeConstant;
import com.catgal.common.constants.MqConstants;
import com.catgal.common.utils.StringUtils;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static com.catgal.common.constants.MqConstants.Key.BIZ_TYPE_CUTE_POINTS_KEY;


@Configuration
public class CutePointsMQConfig {

    @Bean
    public TopicExchange cutePointsExchange() {return new TopicExchange(MqConstants.Exchange.CUTE_POINT_EXCHANGE);}

    @Bean
    public Queue signInQueue() {
        return new Queue(StringUtils.format(MqConstants.Queue.CUTE_POINTS_QUEUE, CutePointsTypeConstant.SIGN_IN));
    }

    @Bean
    public Queue drawConsumedQueue() {
        return new Queue(StringUtils.format(MqConstants.Queue.CUTE_POINTS_QUEUE, CutePointsTypeConstant.DRAW_CONSUMED));
    }

    @Bean
    public Queue drawReceivedQueue() {
        return new Queue(StringUtils.format(MqConstants.Queue.CUTE_POINTS_QUEUE, CutePointsTypeConstant.DRAW_RECEIVED));
    }

    @Bean
    public Binding signInBinding() {
        return BindingBuilder.bind(signInQueue())
                .to(cutePointsExchange())
                .with(StringUtils.format(BIZ_TYPE_CUTE_POINTS_KEY, CutePointsTypeConstant.SIGN_IN));
    }

    @Bean
    public Binding drawConsumedBinding() {
        return BindingBuilder.bind(drawConsumedQueue())
                .to(cutePointsExchange())
                .with(StringUtils.format(BIZ_TYPE_CUTE_POINTS_KEY, CutePointsTypeConstant.DRAW_CONSUMED));
    }

    @Bean
    public Binding drawReceivedBinding() {
        return BindingBuilder.bind(drawReceivedQueue())
                .to(cutePointsExchange())
                .with(StringUtils.format(BIZ_TYPE_CUTE_POINTS_KEY, CutePointsTypeConstant.DRAW_RECEIVED));
    }
}
