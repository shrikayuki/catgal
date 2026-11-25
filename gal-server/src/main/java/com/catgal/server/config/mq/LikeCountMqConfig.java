/*
 * 阅读说明
 * 点赞计数和收藏同步监听容器配置，统一有限重试及错误恢复。
 */
package com.catgal.server.config.mq;

import java.util.Map;
import org.slf4j.MDC;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.config.ContainerCustomizer;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.policy.SimpleRetryPolicy;

import static com.catgal.common.constants.Constant.REQUEST_ID_HEADER;

/** 点赞计数与收藏队列的有限重试配置，最终失败复用项目已配置的消息恢复器。 */
@Configuration
public class LikeCountMqConfig {
    // 包含第一次执行。调整这些常量可以改变故障等待时间，需同时验证重试耗时和队列积压。
    private static final int MAX_ATTEMPTS = 3;
    private static final long INITIAL_INTERVAL_MS = 1000;
    private static final double MULTIPLIER = 2;
    private static final long MAX_INTERVAL_MS = 5000;

    @Bean
    public SimpleRabbitListenerContainerFactory likeCountListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer, ConnectionFactory connectionFactory,
            MessageRecoverer recoverer,
            ObjectProvider<ContainerCustomizer<SimpleMessageListenerContainer>> customizers) {
        return reliableFactory(configurer, connectionFactory, recoverer, customizers);
    }

    /** 收藏当前状态校准使用相同的有限重试策略，最终进入现有错误交换机。 */
    @Bean
    public SimpleRabbitListenerContainerFactory favoriteListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer, ConnectionFactory connectionFactory,
            MessageRecoverer recoverer,
            ObjectProvider<ContainerCustomizer<SimpleMessageListenerContainer>> customizers) {
        return reliableFactory(configurer, connectionFactory, recoverer, customizers);
    }

    /** 积分通知和切换期间的旧积分消费者复用相同有限重试，避免故障时持续热重投。 */
    @Bean
    public SimpleRabbitListenerContainerFactory rewardListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer, ConnectionFactory connectionFactory,
            MessageRecoverer recoverer,
            ObjectProvider<ContainerCustomizer<SimpleMessageListenerContainer>> customizers) {
        return reliableFactory(configurer, connectionFactory, recoverer, customizers);
    }

    private SimpleRabbitListenerContainerFactory reliableFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer, ConnectionFactory connectionFactory,
            MessageRecoverer recoverer,
            ObjectProvider<ContainerCustomizer<SimpleMessageListenerContainer>> customizers) {
        var factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        customizers.ifUnique(factory::setContainerCustomizer);

        // 遍历包装异常的 cause，字段错误直接恢复，数据库与 Redis 暂时故障按上面的次数退避重试。
        var retryPolicy = new SimpleRetryPolicy(MAX_ATTEMPTS,
                Map.of(AmqpRejectAndDontRequeueException.class, false), true, true);
        factory.setAdviceChain(RetryInterceptorBuilder.stateless().retryPolicy(retryPolicy)
                .backOffOptions(INITIAL_INTERVAL_MS, MULTIPLIER, MAX_INTERVAL_MS)
                .recoverer(recoverer).build());
        factory.setAfterReceivePostProcessors(message -> {
            // 同一消费线程会处理多条消息，先清理上一条上下文，再关联当前请求的追踪 ID。
            MDC.remove(REQUEST_ID_HEADER);
            Object requestId = message.getMessageProperties().getHeader(REQUEST_ID_HEADER);
            if (requestId != null) MDC.put(REQUEST_ID_HEADER, requestId.toString());
            return message;
        });
        return factory;
    }
}
