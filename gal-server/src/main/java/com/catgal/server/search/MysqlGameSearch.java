/*
 * 阅读说明
 * 游戏列表的 MySQL 检索实现，作为常规列表和 ES 故障回退的数据来源。
 */
package com.catgal.server.search;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.OrderItem;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.catgal.common.domain.query.GamePageQuery;
import com.catgal.common.utils.StringUtils;
import com.catgal.server.domain.po.Game;
import com.catgal.server.mapper.GameMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class MysqlGameSearch implements GameSearchPort {
    private final GameMapper gameMapper;

    @Override
    public SearchPage search(GamePageQuery query) {
        Page<Game> page = query.toMpPage();
        page.addOrder(OrderItem.desc("id"));
        Page<Game> result = gameMapper.selectPage(page, criteria(query));
        return new SearchPage(result.getTotal(), result.getRecords().stream().map(Game::getId).toList());
    }

    /** Validate explicit ranked candidates against the same public catalog filters as ordinary search. */
    // 语义候选带源版本回库复核，并复用普通检索条件校验当前公开状态。
    public List<Long> filterCurrentCandidates(Map<Long, Long> versions, GamePageQuery query) {
        if (versions.isEmpty()) return List.of();
        if (versions.size() > 50) throw new IllegalArgumentException("Too many semantic candidates");
        LambdaQueryWrapper<Game> wrapper = criteria(query);
        wrapper.and(candidates -> versions.forEach((id, version) -> candidates.or(candidate -> candidate
                .eq(Game::getId, id)
                .apply("EXISTS (SELECT 1 FROM ai_game_vector_outbox v "
                        + "WHERE v.game_id = game.id AND v.version = {0})", version))));
        return gameMapper.selectList(wrapper).stream().map(Game::getId).toList();
    }

    // 使用 MyBatis-Plus 参数绑定组合公开条件，关联筛选通过 EXISTS 避免联表重复游戏。
    private LambdaQueryWrapper<Game> criteria(GamePageQuery query) {
        return new LambdaQueryWrapper<Game>()
                .select(Game::getId)
                .eq(Game::getStatus, 1)
                .like(StringUtils.isNotBlank(query.getName()), Game::getName, query.getName())
                .and(StringUtils.isNotBlank(query.getKeyword()), nested -> nested
                        .like(Game::getName, query.getKeyword())
                        .or().like(Game::getIntroduction, query.getKeyword())
                        .or().apply("EXISTS (SELECT 1 FROM game_tag gt JOIN tag t ON t.id = gt.tag_id "
                                + "WHERE gt.game_id = game.id AND t.name LIKE {0})", "%" + query.getKeyword() + "%")
                        .or().apply("EXISTS (SELECT 1 FROM game_company gc JOIN company c ON c.id = gc.company_id "
                                + "WHERE gc.game_id = game.id AND c.name LIKE {0})", "%" + query.getKeyword() + "%"))
                .apply(query.getReleaseYear() != null, "YEAR(release_date) = {0}", query.getReleaseYear())
                .apply(StringUtils.isNotBlank(query.getResourceType()), "FIND_IN_SET({0}, types)", query.getResourceType())
                .apply(StringUtils.isNotBlank(query.getLanguage()), "FIND_IN_SET({0}, languages)", query.getLanguage())
                .apply(StringUtils.isNotBlank(query.getPlatform()), "FIND_IN_SET({0}, platforms)", query.getPlatform())
                .apply(query.getTagId() != null,
                        "EXISTS (SELECT 1 FROM game_tag gt WHERE gt.game_id = game.id AND gt.tag_id = {0})", query.getTagId())
                .apply(query.getCompanyId() != null,
                        "EXISTS (SELECT 1 FROM game_company gc WHERE gc.game_id = game.id AND gc.company_id = {0})", query.getCompanyId());
    }
}
