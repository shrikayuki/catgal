/*
 * 阅读说明
 * 从用户收藏和评价中读取有限兴趣线索，供授权后的游戏推荐使用。
 * 收藏缓存作为有限样本读取，无法取得可用缓存时按归属回库，响应标记采样及降级状态。
 */
package com.catgal.server.ai.tools;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.catgal.common.utils.StringUtils;
import com.catgal.server.domain.po.FavoriteFolder;
import com.catgal.server.domain.po.FavoriteItem;
import com.catgal.server.domain.po.GameReview;
import com.catgal.server.mapper.FavoriteFolderMapper;
import com.catgal.server.mapper.FavoriteItemMapper;
import com.catgal.server.mapper.GameReviewMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import static com.catgal.common.constants.RedisConstant.FAVORITE_CHANGE_SET_KEY;
import static com.catgal.common.constants.RedisConstant.USER_FAVORITE_KEY;
import static com.catgal.server.redis.RedisScriptLoader.load;

@Component
@RequiredArgsConstructor
public class AiInterestReader {
    private static final int MAX_FOLDERS = 50;
    private static final int MAX_CANDIDATES = 200;
    private static final DefaultRedisScript<List> SNAPSHOT = load("lua/ai-tools/favorite-snapshot.lua", List.class);
    private final FavoriteFolderMapper folders;
    private final FavoriteItemMapper items;
    private final GameReviewMapper reviews;
    private final StringRedisTemplate redis;

    public record Snapshot(List<Long> favoriteIds, List<GameReview> reviews, boolean sampled,
                            String sourceStatus) { }

    public Snapshot read(long userId, int limit) {
        List<FavoriteFolder> owned = folders.selectList(new LambdaQueryWrapper<FavoriteFolder>()
                .eq(FavoriteFolder::getUserId, userId).orderByDesc(FavoriteFolder::getId).last("LIMIT 51"));
        boolean sampled = owned.size() > MAX_FOLDERS;
        boolean databaseFallback = false;
        LinkedHashSet<Long> favoriteIds = new LinkedHashSet<>();
        for (FavoriteFolder folder : owned.stream().limit(MAX_FOLDERS).toList()) {
            // Recheck ownership even if a stale or malformed row is returned by the mapper.
            if (!Long.valueOf(userId).equals(folder.getUserId())) continue;
            int remaining = MAX_CANDIDATES - favoriteIds.size();
            if (remaining == 0) { sampled = true; break; }
            List<?> cached = readCache(folder.getId(), remaining + 1);
            if (cached != null && cached.size() >= 2 && "1".equals(String.valueOf(cached.get(0)))) {
                // Existing writers can initialise a cold hash with only the latest addition.
                // Without a completeness marker, even a dirty empty hash is only a sample.
                sampled = true;
                for (int index = 2; index + 1 < cached.size(); index += 2) {
                    if (String.valueOf(userId).equals(String.valueOf(cached.get(index + 1)))) {
                        Long gameId = positiveId(cached.get(index));
                        if (gameId != null) favoriteIds.add(gameId);
                    }
                }
            } else {
                databaseFallback = true;
                List<FavoriteItem> persisted = items.selectList(new LambdaQueryWrapper<FavoriteItem>()
                        .eq(FavoriteItem::getFolderId, folder.getId()).eq(FavoriteItem::getUserId, userId)
                        .orderByDesc(FavoriteItem::getId).last("LIMIT " + (remaining + 1)));
                for (FavoriteItem item : persisted) {
                    if (Long.valueOf(userId).equals(item.getUserId()) && folder.getId().equals(item.getFolderId())
                            && item.getGameId() != null && item.getGameId() > 0) favoriteIds.add(item.getGameId());
                }
            }
            if (favoriteIds.size() > MAX_CANDIDATES) {
                sampled = true;
                break;
            }
        }
        List<GameReview> latest = reviews.selectList(new LambdaQueryWrapper<GameReview>()
                .select(GameReview::getGameId, GameReview::getUserId, GameReview::getScore,
                        GameReview::getRecommendLevel, GameReview::getPlayStatus, GameReview::getStatus)
                .eq(GameReview::getUserId, userId).eq(GameReview::getStatus, 1)
                .orderByDesc(GameReview::getUpdateTime, GameReview::getId).last("LIMIT " + (limit + 1)));
        sampled |= latest.size() > limit || favoriteIds.size() > limit;
        List<GameReview> safeReviews = latest.stream()
                .filter(review -> Long.valueOf(userId).equals(review.getUserId()) && Integer.valueOf(1).equals(review.getStatus()))
                .limit(limit).toList();
        return new Snapshot(new ArrayList<>(favoriteIds).subList(0, Math.min(MAX_CANDIDATES, favoriteIds.size())),
                safeReviews, sampled, databaseFallback ? "database_fallback" : "current");
    }

    private List<?> readCache(long folderId, int limit) {
        try {
            return redis.execute(SNAPSHOT, List.of(StringUtils.format(USER_FAVORITE_KEY, folderId),
                    FAVORITE_CHANGE_SET_KEY, FAVORITE_CHANGE_SET_KEY + ":processing"),
                    String.valueOf(folderId), String.valueOf(limit));
        } catch (DataAccessException exception) {
            // The response identifies degraded freshness. Do not log private interests or keys.
            return null;
        }
    }

    private Long positiveId(Object value) {
        try {
            long id = Long.parseLong(String.valueOf(value));
            return id > 0 ? id : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}
