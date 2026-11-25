/*
 * 阅读说明
 * 抽奖记录事件的交换机、队列及路由绑定。
 */
package com.catgal.server.config.mqconfig;

import com.catgal.common.constants.MqConstants;
import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DrawRecordMQConfig {

    @Bean
    public DirectExchange drawRecordExchange() {
        return new DirectExchange(MqConstants.Exchange.DRAW_RECORD_EXCHANGE);
    }

    @Bean
    public Queue drawRecordQueue() {
        return QueueBuilder.durable(MqConstants.Queue.DRAW_RECORD_QUEUE).build();
    }

    @Bean
    public Binding drawRecordBinding() {
        return BindingBuilder.bind(drawRecordQueue())
                .to(drawRecordExchange())
                .with(MqConstants.Key.DRAW_RECORD_KEY);
    }

}
