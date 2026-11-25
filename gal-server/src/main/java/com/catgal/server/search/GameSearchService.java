/*
 * 阅读说明
 * 游戏查询的检索编排，按开关、关键词与结果窗口选择 ES，异常时回退 MySQL。
 * ES 命中后回库校验发布状态，冷却时间内避免持续访问故障搜索服务。
 */
package com.catgal.server.search;

import com.catgal.common.domain.query.GamePageQuery;
import com.catgal.common.exception.BusinessException;
import com.catgal.common.utils.StringUtils;
import com.catgal.server.domain.po.Game;
import com.catgal.server.mapper.GameMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Qualifier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class GameSearchService {
    private final GameSearchPort mysql;
    private final GameSearchPort elasticsearch;
    private final GameMapper gameMapper;
    private final SearchProperties properties;
    private volatile Instant retryAfter = Instant.EPOCH;

    public GameSearchService(@Qualifier("mysqlGameSearch") GameSearchPort mysql,
                             @Qualifier("elasticsearchGameSearch") GameSearchPort elasticsearch,
                             GameMapper gameMapper, SearchProperties properties) {
        this.mysql = mysql;
        this.elasticsearch = elasticsearch;
        this.gameMapper = gameMapper;
        this.properties = properties;
    }

    // 关键词检索在 ES 开关开启、结果窗口允许且故障冷却结束时走 ES。
    // 命中 ID 回库核验后返回，缺失、下架或网络异常时由 MySQL 路径完成本次查询。
    public GamePage search(GamePageQuery query) {
        validate(query);
        long offset = (query.getPageNo() - 1L) * query.getPageSize();
        if (properties.isEnabled() && (StringUtils.isNotBlank(query.getName()) || StringUtils.isNotBlank(query.getKeyword()))
                && offset + query.getPageSize() <= 10_000 && Instant.now().isAfter(retryAfter)) {
            try {
                GameSearchPort.SearchPage hits = elasticsearch.search(query);
                List<Game> games = load(hits.ids());
                if (games.size() == hits.ids().size()) {
                    return new GamePage(hits.total(), games);
                }
                log.warn("Game index contains missing or unpublished records, falling back to MySQL");
            } catch (RuntimeException exception) {
                retryAfter = Instant.now().plus(properties.getFailureCooldown());
                // Never log request text, credentials or Elasticsearch response bodies.
                log.warn("Game search falling back to MySQL, failureType={}", exception.getClass().getSimpleName());
            }
        }
        GameSearchPort.SearchPage hits = mysql.search(query);
        return new GamePage(hits.total(), load(hits.ids()));
    }

    // 批量回库过滤当前未发布游戏，再按检索 ID 的顺序恢复结果相关性排序。
    private List<Game> load(List<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<Long, Game> games = gameMapper.selectBatchIds(ids).stream()
                .filter(game -> Integer.valueOf(1).equals(game.getStatus()))
                .collect(Collectors.toMap(Game::getId, Function.identity()));
        return ids.stream().map(games::get).filter(java.util.Objects::nonNull).toList();
    }

    private void validate(GamePageQuery query) {
        if (query.getPageNo() == null || query.getPageNo() < 1 || query.getPageSize() == null
                || query.getPageSize() < 1 || query.getPageSize() > 100) {
            throw BusinessException.badRequest("页码须大于0，每页数量须在1到100之间");
        }
        if (query.getName() != null && query.getName().length() > 200) {
            throw BusinessException.badRequest("游戏搜索名称不能超过200个字符");
        }
        if (query.getKeyword() != null && query.getKeyword().length() > 200) {
            throw BusinessException.badRequest("联合搜索关键字不能超过200个字符");
        }
        if (query.getReleaseYear() != null && (query.getReleaseYear() < 1 || query.getReleaseYear() > 9998)) {
            throw BusinessException.badRequest("发售年份须在1到9998之间");
        }
        if (query.getIsAsc() == null) {
            throw BusinessException.badRequest("排序方向不能为空");
        }
    }

    public record GamePage(long total, List<Game> games) { }
}
