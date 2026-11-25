/*
 * 阅读说明
 * 游戏列表轻量装配器，批量读取统计并补充标签、会社和封面路径。
 */
package com.catgal.server.search;

import com.catgal.common.domain.vo.GameVO;
import com.catgal.common.utils.StringUtils;
import com.catgal.server.domain.po.Game;
import com.catgal.server.catalog.GameRelationsService;
import com.catgal.server.mapper.CommentMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import static com.catgal.common.constants.RedisConstant.*;

@Component
@RequiredArgsConstructor
public class GameListAssembler {
    private final StringRedisTemplate redis;
    private final CommentMapper commentMapper;
    private final GameRelationsService relations;

    public List<GameVO> assemble(List<Game> games) {
        if (games.isEmpty()) {
            return List.of();
        }
        List<String> keys = new ArrayList<>(games.size() * 3);
        for (Game game : games) {
            keys.add(StringUtils.format(GAME_DOWNLOAD_COUNT_KEY, game.getId()));
            keys.add(StringUtils.format(FAVORITE_COUNT_KEY, game.getId()));
            keys.add(StringUtils.format(LOOK_COUNT_KEY, game.getId()));
        }
        List<String> counters = redis.opsForValue().multiGet(keys);
        // 整页只执行一次数据库聚合，避免读到旧Redis计数，也不会逐个游戏查询。
        Map<Long, Integer> comments = commentMapper.countVisibleInGames(games.stream().map(Game::getId).toList())
                .stream().collect(Collectors.toMap(CommentMapper.GameCommentCount::gameId,
                        CommentMapper.GameCommentCount::commentCount));
        List<GameVO> result = new ArrayList<>(games.size());
        for (int i = 0; i < games.size(); i++) {
            Game game = games.get(i);
            GameVO vo = new GameVO();
            vo.setId(game.getId());
            vo.setName(game.getName());
            vo.setCoverUrl(com.catgal.server.storage.FileAccessPaths.gameCover(game));
            vo.setCommentCount(comments.getOrDefault(game.getId(), 0));
            vo.setDownloadCount(counter(counters, i * 3, game.getDownloadCount()));
            vo.setFavoriteCount(counter(counters, i * 3 + 1, game.getFavoriteCount()));
            vo.setViewCount(counter(counters, i * 3 + 2, game.getViewCount()));
            vo.setRating(game.getRating() == null ? BigDecimal.ZERO : game.getRating());
            vo.setResourceTypes(game.getTypeList());
            vo.setResourceCount(null);
            vo.setIsFavorite(null);
            result.add(vo);
        }
        relations.attach(result);
        return result;
    }

    private Integer counter(List<String> counters, int index, Integer fallback) {
        if (counters == null || counters.get(index) == null) {
            return fallback == null ? 0 : fallback;
        }
        try {
            return Integer.valueOf(counters.get(index));
        } catch (NumberFormatException ignored) {
            return fallback == null ? 0 : fallback;
        }
    }
}
