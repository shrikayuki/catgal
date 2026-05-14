package com.catgal.server.service.impl;

import Message.FavoriteSyncMessage;
import com.catgal.common.autoconfigure.mq.RabbitMqHelper;
import com.catgal.common.context.UserContext;
import com.catgal.common.domain.dto.FavoriteDTO;
import com.catgal.common.utils.CollUtils;
import com.catgal.common.utils.StringUtils;
import com.catgal.server.domain.po.FavoriteFolder;
import com.catgal.server.domain.po.FavoriteItem;
import com.catgal.server.mapper.FavoriteFolderMapper;
import com.catgal.server.mapper.FavoriteItemMapper;
import com.catgal.server.service.IFavoriteItemService;
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

    @Override
    public Boolean favorite(FavoriteDTO dto) {
        Long gameId = dto.getGameId();
        Long folderId = dto.getFolderId();
        Long userId = UserContext.getUserId();

        // 1. 参数校验
        if (gameId == null || folderId == null) {
            throw new RuntimeException("游戏ID和收藏夹ID不能为空");
        }

        // 2. 校验收藏夹是否存在且属于当前用户
        FavoriteFolder folder = folderMapper.selectById(folderId);
        if (folder == null) {
            throw new RuntimeException("收藏夹不存在");
        }
        if (!folder.getUserId().equals(userId)) {
            throw new RuntimeException("无权操作");
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

        Long newCount = favoriteCountChange(StringUtils.format(FAVORITE_COUNT_KEY,gameId),FAVORITE);

        // 添加收藏后
        redisTemplate.opsForSet().add(FAVORITE_CHANGE_SET_KEY, folderId.toString());
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
        } else {
            redisTemplate.opsForSet().add(GAME_FAVORITE_COUNT_CHANGE_SET_KEY, gameId.toString());
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
            throw new RuntimeException("游戏ID和收藏夹ID不能为空");
        }

        // 2. 校验收藏夹是否存在且属于当前用户
        FavoriteFolder folder = folderMapper.selectById(folderId);
        if (folder == null) {
            throw new RuntimeException("收藏夹不存在");
        }
        if (!folder.getUserId().equals(userId)) {
            throw new RuntimeException("无权操作");
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

        Long newCount = favoriteCountChange(StringUtils.format(FAVORITE_COUNT_KEY,gameId),UNFAVORITE);

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
        } else {
            redisTemplate.opsForSet().add(GAME_FAVORITE_COUNT_CHANGE_SET_KEY, gameId.toString());
        }

        log.info("取消收藏成功, userId={}, gameId={}, folderId={}", userId, gameId, folderId);
        return true;
    }

    @Override
    @Transactional
    public void fullSyncFavoriteItemsToDBTask(Integer batchSize) {
        String changeKey = FAVORITE_CHANGE_SET_KEY;
        Long size = redisTemplate.opsForSet().size(changeKey);
        if (size == null || size <= 0) {
            log.debug("没有要同步的收藏记录");
            return;
        }

        while (true) {
            // 批量取出变化的收藏夹ID
            List<String> pop = redisTemplate.opsForSet().pop(changeKey, batchSize);
            if (CollUtils.isEmpty(pop)) {
                break;
            }

            Set<Long> folderIds = pop.stream()
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
        }

        log.info("全量同步收藏记录完成");
    }

    private Long favoriteCountChange(String countKey, Integer operation) {
        Long newCount = 0L;
        if (Objects.equals(operation, FAVORITE)) {
            // 收藏：+1
            newCount = redisTemplate.opsForValue().increment(countKey);
            log.debug("游戏收藏数+1, key={}, newCount={}", countKey, newCount);
        } else {
            // 取消收藏：-1
            newCount = redisTemplate.opsForValue().decrement(countKey);

            // 防止负数
            if (newCount != null && newCount < 0) {
                redisTemplate.opsForValue().set(countKey, "0");
                newCount = 0L;
                log.warn("游戏收藏数变为负数，已重置为0, key={}", countKey);
            }

            log.debug("游戏收藏数-1, key={}, newCount={}", countKey, newCount);
        }

        return newCount;


    }


}
