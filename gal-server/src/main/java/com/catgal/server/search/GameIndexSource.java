/*
 * 阅读说明
 * 从 MySQL 按 ID 批量加载游戏和关联名称，生成搜索索引文档。
 */
package com.catgal.server.search;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.catgal.server.domain.po.Game;
import com.catgal.server.domain.po.GameCompany;
import com.catgal.server.domain.po.GameTag;
import com.catgal.server.domain.po.Tag;
import com.catgal.server.domain.po.Company;
import com.catgal.server.mapper.GameCompanyMapper;
import com.catgal.server.mapper.GameMapper;
import com.catgal.server.mapper.GameTagMapper;
import com.catgal.server.mapper.TagMapper;
import com.catgal.server.mapper.CompanyMapper;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;

@Component
@RequiredArgsConstructor
public class GameIndexSource implements IndexSource {
    private final GameMapper gameMapper;
    private final GameTagMapper tagMapper;
    private final GameCompanyMapper companyMapper;
    private final JdbcTemplate jdbc;
    private final TagMapper tagNameMapper;
    private final CompanyMapper companyNameMapper;

    public List<Long> nextIds(long afterId, int size) {
        return gameMapper.selectList(new LambdaQueryWrapper<Game>()
                        .select(Game::getId).gt(Game::getId, afterId).orderByAsc(Game::getId)
                        .last("LIMIT " + size))
                .stream().map(Game::getId).toList();
    }

    // 先读取 Outbox 版本建立 status=0 墓碑，再填充当前已发布游戏资料。
    // 保留版本化墓碑可让迟到的旧索引写入被版本规则拒绝。
    public Map<Long, Map<String, Object>> load(List<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        // Read versions before document values. Later commits must receive a higher ES version.
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        Map<Long, Map<String, Object>> documents = new HashMap<>();
        jdbc.query("SELECT game_id, version FROM search_outbox WHERE game_id IN (" + placeholders + ")",
                row -> {
                    Map<String, Object> tombstone = new LinkedHashMap<>();
                    tombstone.put("id", row.getLong("game_id"));
                    tombstone.put("version", row.getLong("version"));
                    tombstone.put("status", 0);
                    documents.put(row.getLong("game_id"), tombstone);
                }, ids.toArray());
        if (documents.size() != ids.size()) {
            throw new IllegalStateException("Game search outbox requires migration or backfill");
        }
        Map<Long, List<Integer>> tags = tagMapper.selectList(new LambdaQueryWrapper<GameTag>()
                        .in(GameTag::getGameId, ids)).stream()
                .collect(Collectors.groupingBy(GameTag::getGameId,
                        Collectors.mapping(GameTag::getTagId, Collectors.toList())));
        Map<Long, List<Long>> companies = companyMapper.selectList(new LambdaQueryWrapper<GameCompany>()
                        .in(GameCompany::getGameId, ids)).stream()
                .collect(Collectors.groupingBy(GameCompany::getGameId,
                        Collectors.mapping(GameCompany::getCompanyId, Collectors.toList())));
        List<Integer> tagIds = tags.values().stream().flatMap(List::stream).distinct().toList();
        Map<Integer, String> tagNames = tagIds.isEmpty() ? Map.of() : tagNameMapper.selectBatchIds(tagIds)
                .stream().collect(Collectors.toMap(Tag::getId, Tag::getName));
        List<Long> companyIds = companies.values().stream().flatMap(List::stream).distinct().toList();
        Map<Long, String> companyNames = companyIds.isEmpty() ? Map.of() : companyNameMapper.selectBatchIds(companyIds)
                .stream().collect(Collectors.toMap(Company::getId, Company::getName));
        for (Game game : gameMapper.selectBatchIds(ids)) {
            if (!Integer.valueOf(1).equals(game.getStatus())) {
                continue;
            }
            Map<String, Object> document = documents.get(game.getId());
            document.put("id", game.getId());
            document.put("name", game.getName());
            document.put("introduction", game.getIntroduction());
            document.put("tag_names", tags.getOrDefault(game.getId(), List.of()).stream()
                    .map(tagNames::get).filter(java.util.Objects::nonNull).toList());
            document.put("company_names", companies.getOrDefault(game.getId(), List.of()).stream()
                    .map(companyNames::get).filter(java.util.Objects::nonNull).toList());
            document.put("status", game.getStatus());
            document.put("release_date", game.getReleaseDate() == null ? null : game.getReleaseDate().toString());
            document.put("create_time", game.getCreateTime() == null ? null : game.getCreateTime().toString());
            document.put("rating", game.getRating());
            document.put("view_count", game.getViewCount());
            document.put("download_count", game.getDownloadCount());
            document.put("favorite_count", game.getFavoriteCount());
            document.put("types", split(game.getTypes()));
            document.put("languages", split(game.getLanguages()));
            document.put("platforms", split(game.getPlatforms()));
            document.put("tag_ids", tags.getOrDefault(game.getId(), List.of()));
            document.put("company_ids", companies.getOrDefault(game.getId(), List.of()));
            documents.put(game.getId(), document);
        }
        return documents;
    }

    private List<String> split(String values) {
        return values == null || values.isEmpty() ? List.of() : Arrays.asList(values.split(","));
    }
}
