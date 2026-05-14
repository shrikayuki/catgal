package com.catgal.server.config.mqconfig;

import com.catgal.common.utils.StringUtils;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static com.catgal.common.constants.MqConstants.Exchange.LIKE_COUNT_EXCHANGE;
import static com.catgal.common.constants.MqConstants.Key.*;
import static com.catgal.common.constants.MqConstants.Queue.LIKE_COUNT_QUEUE_TEMPLATE;

import static com.catgal.common.constants.LikeBizTypeConstant.*;

@Configuration
public class LikeMQConfig {

    @Bean
    public TopicExchange likeCountExchange() {
        return new TopicExchange(LIKE_COUNT_EXCHANGE);
    }

    @Bean
    public Queue likeCommentQueue() {
        return new Queue(StringUtils.format(LIKE_COUNT_QUEUE_TEMPLATE, LIKE_TYPE_COMMENT), true);
    }

    @Bean
    public Queue likeReviewQueue() {
        return new Queue(StringUtils.format(LIKE_COUNT_QUEUE_TEMPLATE, LIKE_TYPE_REVIEW), true);
    }

    @Bean
    public Queue likeResourceQueue() {
        return new Queue(StringUtils.format(LIKE_COUNT_QUEUE_TEMPLATE, LIKE_TYPE_RESOURCE), true);
    }

    @Bean
    public Binding likeCommentBinding() {
        return BindingBuilder.bind(likeCommentQueue())
                .to(likeCountExchange())
                .with(COMMENT_LIKED_TIMES_KEY);
    }

    @Bean
    public Binding likeReviewBinding() {
        return BindingBuilder.bind(likeReviewQueue())
                .to(likeCountExchange())
                .with(REVIEW_LIKED_TIMES_KEY);
    }

    @Bean
    public Binding likeResourceBinding() {
        return BindingBuilder.bind(likeResourceQueue())
                .to(likeCountExchange())
                .with(RESOURCE_LIKED_TIMES_KEY);
    }
}