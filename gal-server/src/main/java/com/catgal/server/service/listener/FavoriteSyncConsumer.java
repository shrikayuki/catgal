/*
 * 阅读说明
 * 收藏 MQ 通知只定位同步目标，消费者根据当前完整快照校准数据库。
 */
package com.catgal.server.service.listener;

import Message.FavoriteSyncMessage;
import com.catgal.common.constants.MqConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.stereotype.Component;


import static Message.FavoriteSyncMessage.FAVORITE;
import static Message.FavoriteSyncMessage.UNFAVORITE;

@RequiredArgsConstructor
@Slf4j
@Component
public class FavoriteSyncConsumer {

    private final com.catgal.server.service.IFavoriteItemService favoriteService;

    @RabbitListener(queues = MqConstants.Queue.FAVORITE_SYNC_QUEUE,
            containerFactory = "favoriteListenerContainerFactory")
    // 校验消息定位字段后执行当前关系校准，异常继续交给有限重试和错误恢复。
    public void handleFavoriteSync(FavoriteSyncMessage message) {
        validateMessage(message);
        // operation 仅用于兼容原消息格式，迟到通知必须读取当前已校验状态。
        favoriteService.syncFavorite(message.getFolderId(), message.getUserId(), message.getGameId());
    }

    private void validateMessage(FavoriteSyncMessage message) {
        if (message == null || message.getUserId() == null || message.getUserId() <= 0
                || message.getFolderId() == null || message.getFolderId() <= 0
                || message.getGameId() == null || message.getGameId() <= 0
                || (!FAVORITE.equals(message.getOperation()) && !UNFAVORITE.equals(message.getOperation()))) {
            throw new AmqpRejectAndDontRequeueException("收藏同步消息字段或操作类型无效");
        }
    }
}
