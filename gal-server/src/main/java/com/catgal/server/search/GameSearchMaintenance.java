/*
 * 阅读说明
 * 游戏索引的定时同步和管理端重建入口，委托通用维护流程。
 */
package com.catgal.server.search;

import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class GameSearchMaintenance extends SearchIndexMaintenance {
    public GameSearchMaintenance(JdbcTemplate jdbc, SearchProperties properties,
                                 @Qualifier("searchOutbox") SearchOutbox outbox,
                                 GameIndexSource source, GameIndexWriter writer) {
        super(jdbc, properties, outbox, source, writer, properties.getAlias(), ":game-search", properties::isEnabled);
    }

    @Override
    @Scheduled(fixedDelayString = "${catgal.search.sync-delay-ms:5000}")
    public void synchronize() {
        super.synchronize();
    }

    @Override
    public Map<String, Object> rebuild() {
        Map<String, Object> result = super.rebuild();
        return Map.of("index", result.get("index"), "scannedGames", result.get("scannedRecords"));
    }
}
