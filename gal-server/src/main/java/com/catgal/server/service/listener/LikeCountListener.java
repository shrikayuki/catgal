package com.catgal.server.service.listener;

import Message.LikeCountMessage;
import com.catgal.common.utils.CollUtils;
import com.catgal.server.domain.po.Comment;
import com.catgal.server.domain.po.GameReview;
import com.catgal.server.domain.po.Resource;
import com.catgal.server.service.ICommentService;
import com.catgal.server.service.IGameReviewService;
import com.catgal.server.service.IResourceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.catgal.common.constants.LikeBizTypeConstant.*;
import static com.catgal.common.constants.MqConstants.Exchange.LIKE_COUNT_EXCHANGE;
import static com.catgal.common.constants.MqConstants.Key.*;

@Component
@Slf4j
@RequiredArgsConstructor
public class LikeCountListener {

    private final ICommentService commentService;
    private final IGameReviewService reviewService;
    private final IResourceService resourceService;

    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(name = "like.comment.queue", durable = "true"),
            exchange = @Exchange(name = LIKE_COUNT_EXCHANGE, type = ExchangeTypes.TOPIC),
            key = COMMENT_LIKED_TIMES_KEY
    ))
    public void listenCommentLikeTimesChange(List<LikeCountMessage> messages) {
        updateLikes(messages,
                msg -> {
                    Comment c = new Comment();
                    c.setId(msg.getBizId());
                    c.setLikeCount(Math.toIntExact(msg.getLikeCount()));
                    return c;
                },
                commentService::updateBatchById,
                LIKE_TYPE_COMMENT
        );
    }

    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(name = "like.review.queue", durable = "true"),
            exchange = @Exchange(name = LIKE_COUNT_EXCHANGE, type = ExchangeTypes.TOPIC),
            key = REVIEW_LIKED_TIMES_KEY
    ))
    public void listenReviewLikeTimesChange(List<LikeCountMessage> messages) {
        updateLikes(messages,
                msg -> {
                    GameReview r = new GameReview();
                    r.setId(msg.getBizId());
                    r.setLikeCount(Math.toIntExact(msg.getLikeCount()));
                    return r;
                },
                reviewService::updateBatchById,
                LIKE_TYPE_REVIEW
        );
    }

    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(name = "like.resource.queue", durable = "true"),
            exchange = @Exchange(name = LIKE_COUNT_EXCHANGE, type = ExchangeTypes.TOPIC),
            key = RESOURCE_LIKED_TIMES_KEY
    ))
    public void listenResourceLikeTimesChange(List<LikeCountMessage> messages) {
        updateLikes(messages,
                msg -> {
                    Resource r = new Resource();
                    r.setId(msg.getBizId());
                    r.setLikeCount(Math.toIntExact(msg.getLikeCount()));
                    return r;
                },
                resourceService::updateBatchById,
                LIKE_TYPE_RESOURCE
        );
    }

    /**
     * 通用批量更新方法
     */
    private <T> void updateLikes(List<LikeCountMessage> messages,
                                 Function<LikeCountMessage, T> converter,
                                 Function<List<T>, Boolean> updater,
                                 String typeName) {
        if (CollUtils.isEmpty(messages)) {
            return;
        }

        log.info("收到{}点赞数批量消息, 数量={}", typeName, messages.size());

        List<T> list = messages.stream()
                .map(converter)
                .collect(Collectors.toList());

        boolean success = updater.apply(list);

        if (success) {
            log.info("批量更新{}点赞数成功, 数量={}", typeName, list.size());
        } else {
            log.error("批量更新{}点赞数失败", typeName);
        }
    }
}