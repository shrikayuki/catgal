package com.catgal.server.service.listener;

import Message.FavoriteSyncMessage;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.catgal.common.constants.MqConstants;
import com.catgal.server.domain.po.FavoriteItem;
import com.catgal.server.mapper.FavoriteItemMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

@RequiredArgsConstructor
@Slf4j
@Component
public class FavoriteSyncConsumer {

    private final FavoriteItemMapper favoriteItemMapper;

    @RabbitListener(queues = MqConstants.Queue.FAVORITE_SYNC_QUEUE)
    public void handleFavoriteSync(FavoriteSyncMessage message) {
        log.info("收到收藏同步消息: {}", message);

        // 直接用唯一键判断：插入时重复则忽略，删除时存在则删
        if (message.getOperation() == 1) {
            // 收藏：插入记录（利用唯一键 uk_folder_game，重复自动失败）
            FavoriteItem item = new FavoriteItem();
            item.setUserId(message.getUserId());
            item.setFolderId(message.getFolderId());
            item.setGameId(message.getGameId());

            try {
                favoriteItemMapper.insert(item);
                log.info("收藏记录已写入DB, folderId={}, gameId={}", message.getFolderId(), message.getGameId());
            } catch (DuplicateKeyException e) {
                // 已存在，幂等处理，不报错
                log.debug("收藏记录已存在，幂等忽略, folderId={}, gameId={}", message.getFolderId(), message.getGameId());
            }
        } else {
            // 取消收藏：删除记录（存在则删，不存在也返回成功）
            int deleted = favoriteItemMapper.delete(
                    new LambdaQueryWrapper<FavoriteItem>()
                            .eq(FavoriteItem::getUserId, message.getUserId())
                            .eq(FavoriteItem::getFolderId, message.getFolderId())
                            .eq(FavoriteItem::getGameId, message.getGameId())
            );

            if (deleted > 0) {
                log.info("收藏记录已从DB删除, folderId={}, gameId={}", message.getFolderId(), message.getGameId());
            } else {
                log.debug("收藏记录不存在，幂等忽略, folderId={}, gameId={}", message.getFolderId(), message.getGameId());
            }
        }
    }
}