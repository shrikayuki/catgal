/*
 * 阅读说明
 * 按当前已验证的点赞关系校准目标计数，重复和乱序通知共用相同结果。
 */
package com.catgal.server.service.listener;

import Message.LikeCountMessage;
import com.catgal.server.service.ILikeRecordService;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import static com.catgal.common.constants.LikeBizTypeConstant.*;
import static com.catgal.common.constants.MqConstants.Exchange.LIKE_COUNT_EXCHANGE;
import static com.catgal.common.constants.MqConstants.Key.*;

/**
 * 接收点赞计数变更通知，按目标触发当前状态校准。
 * 消息中的数字可能随投递延迟而过期，数据库更新由 ILikeRecordService 在目标行锁内完成。
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class LikeCountListener {

    private final ILikeRecordService likeService;

    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(name = "like.comment.queue", durable = "true"),
            exchange = @Exchange(name = LIKE_COUNT_EXCHANGE, type = ExchangeTypes.TOPIC),
            key = COMMENT_LIKED_TIMES_KEY
    ), containerFactory = "likeCountListenerContainerFactory")
    public void listenCommentLikeTimesChange(List<LikeCountMessage> messages) {
        updateLikes(messages, LIKE_TYPE_COMMENT);
    }

    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(name = "like.review.queue", durable = "true"),
            exchange = @Exchange(name = LIKE_COUNT_EXCHANGE, type = ExchangeTypes.TOPIC),
            key = REVIEW_LIKED_TIMES_KEY
    ), containerFactory = "likeCountListenerContainerFactory")
    public void listenReviewLikeTimesChange(List<LikeCountMessage> messages) {
        updateLikes(messages, LIKE_TYPE_REVIEW);
    }

    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(name = "like.resource.queue", durable = "true"),
            exchange = @Exchange(name = LIKE_COUNT_EXCHANGE, type = ExchangeTypes.TOPIC),
            key = RESOURCE_LIKED_TIMES_KEY
    ), containerFactory = "likeCountListenerContainerFactory")
    public void listenResourceLikeTimesChange(List<LikeCountMessage> messages) {
        updateLikes(messages, LIKE_TYPE_RESOURCE);
    }

    /**
     * 先验证整批通知，再进入事务业务层。
     * 无效消息由恢复器归档，运行时异常继续向上抛出，由专用监听工厂有限重试。
     */
    // 监听器委托当前状态校准，非法消息与可重试的临时错误按容器配置分流。
    private void updateLikes(List<LikeCountMessage> messages, String type) {
        if (messages == null || messages.isEmpty()) return;
        for (LikeCountMessage message : messages) {
            if (message == null || !Objects.equals(type, message.getBizType())
                    || message.getBizId() == null || message.getBizId() <= 0
                    || message.getLikeCount() == null || message.getLikeCount() < 0
                    || message.getLikeCount() > Integer.MAX_VALUE) {
                throw new AmqpRejectAndDontRequeueException("点赞计数消息字段或业务类型无效");
            }
        }
        likeService.syncLikeCounts(type, messages.stream().map(LikeCountMessage::getBizId).distinct().toList());
        log.info("点赞计数已按当前状态校准, type={}, 通知数={}", type, messages.size());
    }
}
