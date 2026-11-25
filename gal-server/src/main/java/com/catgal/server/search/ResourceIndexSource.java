/*
 * 阅读说明
 * 读取资源及所属游戏、发布者资料，生成资源搜索文档。
 */
package com.catgal.server.search;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.catgal.server.domain.po.Resource;
import com.catgal.server.mapper.ResourceMapper;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ResourceIndexSource implements IndexSource {
    private final ResourceMapper mapper;
    private final JdbcTemplate jdbc;

    @Override
    public List<Long> nextIds(long afterId, int size) {
        return mapper.selectList(new LambdaQueryWrapper<Resource>().select(Resource::getId)
                        .gt(Resource::getId, afterId).orderByAsc(Resource::getId).last("LIMIT " + size))
                .stream().map(Resource::getId).toList();
    }

    @Override
    // 先为每个源 ID 建立带版本的 deleted=true 文档，再填充仍存在资源的资料。
    // 保留版本化墓碑可让迟到的旧索引写入被版本规则拒绝。
    public Map<Long, Map<String, Object>> load(List<Long> ids) {
        if (ids.isEmpty()) return Map.of();
        Map<Long, Map<String, Object>> documents = new HashMap<>();
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        jdbc.query("SELECT resource_id, version FROM resource_search_outbox WHERE resource_id IN (" + placeholders + ")",
                row -> {
                    Map<String, Object> document = new LinkedHashMap<>();
                    document.put("id", row.getLong("resource_id"));
                    document.put("version", row.getLong("version"));
                    document.put("deleted", true);
                    documents.put(row.getLong("resource_id"), document);
                }, ids.toArray());
        if (documents.size() != ids.size()) {
            throw new IllegalStateException("Resource search requires V6 migration or backfill");
        }
        for (Resource resource : mapper.selectList(new LambdaQueryWrapper<Resource>()
                .select(Resource::getId, Resource::getName, Resource::getStatus, Resource::getGameId, Resource::getUserId)
                .in(Resource::getId, ids))) {
            Map<String, Object> document = documents.get(resource.getId());
            document.put("deleted", false);
            document.put("name", resource.getName());
            document.put("status", resource.getStatus());
            document.put("game_id", resource.getGameId());
            document.put("user_id", resource.getUserId());
        }
        return documents;
    }
}
