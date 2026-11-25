/*
 * 阅读说明
 * 资源索引维护入口，装配资源专用的数据源和 Outbox。
 */
package com.catgal.server.search;

import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class ResourceSearchMaintenance extends SearchIndexMaintenance {
    public ResourceSearchMaintenance(JdbcTemplate jdbc, SearchProperties properties, ResourceSearchOutbox outbox,
                                     ResourceIndexSource source, ElasticsearchResourceSearch writer) {
        super(jdbc, properties, outbox, source, writer, properties.getResourceAlias(), ":resource-search", properties::isResourceEnabled);
    }

    @Override
    @Scheduled(fixedDelayString = "${catgal.search.sync-delay-ms:5000}")
    public void synchronize() { super.synchronize(); }

    @Override
    public Map<String, Object> rebuild() {
        Map<String, Object> result = super.rebuild();
        return Map.of("index", result.get("index"), "scannedResources", result.get("scannedRecords"));
    }
}
