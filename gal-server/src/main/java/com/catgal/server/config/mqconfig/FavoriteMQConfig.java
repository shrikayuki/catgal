/*
 * 阅读说明
 * 收藏同步通知的交换机、队列与绑定，消费者根据当前完整状态校准。
 */
package com.catgal.server.config.mqconfig;

import com.catgal.common.constants.MqConstants;
import org.springframework.amqp.core.*;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FavoriteMQConfig {

    @Bean
    public DirectExchange favoriteExchange() {
        return new DirectExchange(MqConstants.Exchange.FAVORITE_EXCHANGE);
    }

    @Bean
    public Queue favoriteSyncQueue() {
        return QueueBuilder.durable(MqConstants.Queue.FAVORITE_SYNC_QUEUE).build();
    }

    @Bean
    public Binding favoriteSyncBinding() {
        return BindingBuilder.bind(favoriteSyncQueue())
                .to(favoriteExchange())
                .with(MqConstants.Key.FAVORITE_CHANGE_KEY);
    }
}