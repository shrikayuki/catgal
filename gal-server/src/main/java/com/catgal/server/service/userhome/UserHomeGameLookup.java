/*
 * 阅读说明
 * UserHomeGameLookup 属于用户主页按 tab 分发的查询策略链。
 * 注册表选择支持目标 tab 的策略，查询结果统一组装到用户主页响应。
 */
package com.catgal.server.service.userhome;

import com.catgal.common.utils.CollUtils;
import com.catgal.server.domain.po.Game;
import com.catgal.server.mapper.GameMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class UserHomeGameLookup {

    private final GameMapper gameMapper;

    public Map<Long, Game> getGameMap(Set<Long> gameIds) {
        if (CollUtils.isEmpty(gameIds)) {
            return Collections.emptyMap();
        }
        List<Game> games = gameMapper.selectBatchIds(gameIds);
        return games.stream().collect(Collectors.toMap(Game::getId, Function.identity(), (left, right) -> left));
    }

    public Map<Long, String> getGameNameMap(Set<Long> gameIds) {
        return getGameMap(gameIds).values().stream()
                .collect(Collectors.toMap(Game::getId, Game::getName, (left, right) -> left));
    }
}
