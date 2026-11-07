package com.catgal.server.service.impl;

import Message.FavoriteSyncMessage;
import com.catgal.common.autoconfigure.mq.RabbitMqHelper;
import com.catgal.common.context.UserContext;
import com.catgal.common.domain.dto.FavoriteDTO;
import com.catgal.common.exception.BusinessException;
import com.catgal.common.utils.CollUtils;
import com.catgal.common.utils.StringUtils;
import com.catgal.server.domain.po.FavoriteFolder;
import com.catgal.server.domain.po.FavoriteItem;
import com.catgal.server.mapper.FavoriteFolderMapper;
import com.catgal.server.mapper.FavoriteItemMapper;
import com.catgal.server.service.IFavoriteItemService;
import com.catgal.server.service.IGameStatsService;
import com.catgal.server.service.RedisCounterService;
import com.catgal.server.service.RedisDirtySetProcessor;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

import static Message.FavoriteSyncMessage.FAVORITE;
import static Message.FavoriteSyncMessage.UNFAVORITE;
import static com.catgal.common.constants.CountLimitConstant.COUNT_LIMIT_K;
import static com.catgal.common.constants.MqConstants.Exchange.FAVORITE_EXCHANGE;
import static com.catgal.common.constants.MqConstants.Key.FAVORITE_CHANGE_KEY;
import static com.catgal.common.constants.RedisConstant.*;

/**
 * <p>
 * 收藏明细表 服务实现类
 * </p>
 *
 * @author rance
 * @since 2026-05-01
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class FavoriteItemServiceImpl extends ServiceImpl<FavoriteItemMapper, FavoriteItem> implements IFavoriteItemService {

    private final StringRedisTemplate redisTemplate;
    private final FavoriteFolderMapper folderMapper;
    private final RabbitMqHelper mqHelper;
    private final FavoriteItemMapper favoriteItemMapper;
    private final IGameStatsService statsService;
    private final RedisCounterService redisCounterService;
    private final RedisDirtySetProcessor dirtySetProcessor;

    @Override
    public Boolean favorite(FavoriteDTO dto) {
        Long gameId = dto.getGameId();
        Long folderId = dto.getFolderId();
        Long userId = UserContext.getUserId();

        // 1. 参数校验
        if (gameId == null || folderId == null) {
            throw BusinessException.badRequest("游戏ID和收藏夹ID不能为空");
        }

        // 2. 校验收藏夹是否存在且属于当前用户
        FavoriteFolder folder = folderMapper.selectById(folderId);
        if (folder == null) {
            throw BusinessException.notFound("收藏夹不存在");
        }
        if (!folder.getUserId().equals(userId)) {
            throw BusinessException.forbidden("无权操作");
        }

        // 3. 检查是否已收藏
        String key = StringUtils.format(USER_FAVORITE_KEY, folderId);
        Boolean exists = redisTemplate.opsForHash().hasKey(key, String.valueOf(gameId));
        if (exists) {
            log.warn("游戏已收藏, gameId={}, folderId={}", gameId, folderId);
            return false;
        }

        // 4. 添加到 Redis
        redisTemplate.opsForHash().put(key, String.valueOf(gameId), String.valueOf(userId));

        Long newCount = favoriteCountChange(gameId, FAVORITE);

        // 检查收藏夹是否还有数据
        Long size = redisTemplate.opsForHash().size(key);

        // 记录变化
        redisTemplate.opsForSet().add(FAVORITE_CHANGE_SET_KEY, folderId.toString());

        // 5. 发消息到mq
        if (newCount <= COUNT_LIMIT_K) {
            FavoriteSyncMessage message = FavoriteSyncMessage.builder()
                    .userId(userId)
                    .folderId(folderId)
                    .gameId(gameId)
                    .operation(FAVORITE)  // 1-收藏
                    .build();
            mqHelper.send(FAVORITE_EXCHANGE, FAVORITE_CHANGE_KEY, message);
        }
        log.info("收藏成功, userId={}, gameId={}, folderId={}", userId, gameId, folderId);
        return true;
    }

    @Override
    public Boolean unfavorite(FavoriteDTO dto) {
        Long gameId = dto.getGameId();
        Long folderId = dto.getFolderId();
        Long userId = UserContext.getUserId();

        // 1. 参数校验
        if (gameId == null || folderId == null) {
            throw BusinessException.badRequest("游戏ID和收藏夹ID不能为空");
        }

        // 2. 校验收藏夹是否存在且属于当前用户
        FavoriteFolder folder = folderMapper.selectById(folderId);
        if (folder == null) {
            throw BusinessException.notFound("收藏夹不存在");
        }
        if (!folder.getUserId().equals(userId)) {
            throw BusinessException.forbidden("无权操作");
        }

        // 3. 检查是否已收藏
        String key = StringUtils.format(USER_FAVORITE_KEY, folderId);
        Boolean exists = redisTemplate.opsForHash().hasKey(key, String.valueOf(gameId));
        if (!exists) {
            log.warn("游戏未收藏, gameId={}, folderId={}", gameId, folderId);
            return false;
        }

        // 4. 从 Redis 移除
        Long deleted = redisTemplate.opsForHash().delete(key, String.valueOf(gameId));

        Long newCount = favoriteCountChange(gameId, UNFAVORITE);

        // 记录变化
        redisTemplate.opsForSet().add(FAVORITE_CHANGE_SET_KEY, folderId.toString());

        if (newCount <= COUNT_LIMIT_K) {
            FavoriteSyncMessage message = FavoriteSyncMessage.builder()
                    .userId(userId)
                    .folderId(folderId)
                    .gameId(gameId)
                    .operation(UNFAVORITE)  // 0-取消收藏
                    .build();
            mqHelper.send(FAVORITE_EXCHANGE, FAVORITE_CHANGE_KEY, message);
        }

        log.info("取消收藏成功, userId={}, gameId={}, folderId={}", userId, gameId, folderId);
        return true;
    }

    @Override
    public Boolean unfavoriteBatch(Long folderId) {
        if (folderId == null) {
            throw BusinessException.badRequest("收藏夹ID不能为空");
        }

        String key = StringUtils.format(USER_FAVORITE_KEY, folderId);

        // 1. 获取所有收藏内容
        Map<Object, Object> entries = redisTemplate.opsForHash().entries(key);
        if (CollUtils.isEmpty(entries)) {
            log.warn("收藏夹为空, folderId={}", folderId);
            return false;
        }

        // 2. 删除整个 Hash
        Boolean deleted = redisTemplate.delete(key);
        if (!deleted) {
            log.error("删除Redis收藏记录失败, folderId={}", folderId);
            return false;
        }

        log.info("删除收藏记录成功, folderId={}, 删除数量={}", folderId, deleted);

        // 3. 遍历处理每个游戏的收藏数
        for (Map.Entry<Object, Object> entry : entries.entrySet()) {
            Long gameId = Long.valueOf(entry.getKey().toString());
            Long userId = Long.valueOf(entry.getValue().toString());

            // 更新游戏收藏数
            Long newCount = favoriteCountChange(gameId, UNFAVORITE);

            // 同步到数据库（阈值控制）
            if (newCount <= COUNT_LIMIT_K) {
                FavoriteSyncMessage message = FavoriteSyncMessage.builder()
                        .userId(userId)
                        .folderId(folderId)
                        .gameId(gameId)
                        .operation(UNFAVORITE)
                        .build();
                mqHelper.send(FAVORITE_EXCHANGE, FAVORITE_CHANGE_KEY, message);
            }
        }

        // 4. 记录变化（用于同步到数据库）
        redisTemplate.opsForSet().add(FAVORITE_CHANGE_SET_KEY, folderId.toString());

        log.info("批量取消收藏成功, folderId={}, 处理游戏数={}", folderId, entries.size());
        return true;
    }

    @Override
    public void fullSyncFavoriteItemsToDBTask(Integer batchSize) {
        String changeKey = FAVORITE_CHANGE_SET_KEY;
        int processed = dirtySetProcessor.processBatch(changeKey, batchSize, true, claimed -> {
            Set<Long> folderIds = claimed.stream()
                    .map(Long::parseLong)
                    .collect(Collectors.toSet());

            // 批量删除旧数据
            favoriteItemMapper.deleteByFolderIds(folderIds);

            List<FavoriteItem> favoriteItems = new ArrayList<>();

            for (Long folderId : folderIds) {
                String key = StringUtils.format(USER_FAVORITE_KEY, folderId);
                Map<Object, Object> gameUserMap = redisTemplate.opsForHash().entries(key);
                if (CollUtils.isEmpty(gameUserMap)) {
                    continue;
                }

                for (Map.Entry<Object, Object> entry : gameUserMap.entrySet()) {
                    FavoriteItem item = new FavoriteItem();
                    item.setFolderId(folderId);
                    item.setGameId(Long.parseLong(entry.getKey().toString()));
                    item.setUserId(Long.parseLong(entry.getValue().toString()));
                    favoriteItems.add(item);
                }
            }

            // 批量插入
            if (!favoriteItems.isEmpty()) {
                saveBatch(favoriteItems);
                log.info("同步收藏记录成功, 数量={}", favoriteItems.size());
            }
        });
        log.info("收藏记录同步完成, 处理收藏夹数={}", processed);
    }

    private Long favoriteCountChange(Long gameId, Integer operation) {
        String countKey = StringUtils.format(FAVORITE_COUNT_KEY, gameId);
        long initialCount = statsService.getGameFavoriteCount(gameId);
        long delta = Objects.equals(operation, FAVORITE) ? 1L : -1L;
        long newCount = redisCounterService.changeAndMark(
                countKey,
                initialCount,
                delta,
                GAME_STATS_COUNT_CHANGE_SET_KEY,
                gameId
        );
        log.debug("游戏收藏数变化, key={}, delta={}, newCount={}", countKey, delta, newCount);
        return newCount;


    }


}
