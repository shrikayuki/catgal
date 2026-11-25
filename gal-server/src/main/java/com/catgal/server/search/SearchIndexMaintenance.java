/*
 * 阅读说明
 * 索引增量同步与全量重建流程，使用 MySQL 命名锁协调多实例与重建。
 * Outbox 事件写索引并 refresh 成功后才确认，失败保留重试状态。
 */
package com.catgal.server.search;

import com.catgal.common.exception.BusinessException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.function.BooleanSupplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

@Slf4j
public class SearchIndexMaintenance {
    private final JdbcTemplate jdbc;
    private final SearchProperties properties;
    private final SearchOutbox outbox;
    private final IndexSource source;
    private final SearchIndexWriter writer;
    private final String alias;
    private final String lockSuffix;
    private final BooleanSupplier enabled;

    public SearchIndexMaintenance(JdbcTemplate jdbc, SearchProperties properties, SearchOutbox outbox,
                                  IndexSource source, SearchIndexWriter writer, String alias,
                                  String lockSuffix, BooleanSupplier enabled) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.outbox = outbox;
        this.source = source;
        this.writer = writer;
        this.alias = alias;
        this.lockSuffix = lockSuffix;
        this.enabled = enabled;
    }

    // 每轮处理有界批次，写索引并 refresh 成功后按源版本确认 Outbox。
    public void synchronize() {
        if (!enabled.getAsBoolean()) {
            return;
        }
        try {
            locked(() -> {
                for (int batch = 0; batch < 10; batch++) {
                    List<SearchOutbox.Event> events = outbox.pending(batchSize());
                    if (events.isEmpty()) {
                        break;
                    }
                    try {
                        writeEvents(alias, events);
                        writer.refresh(alias);
                        outbox.acknowledge(events);
                    } catch (RuntimeException exception) {
                        outbox.failed(events, exception.getClass().getSimpleName());
                        throw exception;
                    }
                }
                return true;
            });
        } catch (BusinessException ignored) {
            // Another instance or a rebuild owns the shared MySQL lock.
        } catch (RuntimeException exception) {
            log.warn("Search index synchronization failed, failureType={}", exception.getClass().getSimpleName());
        }
    }

    // 按游戏或资源主键滚动扫描新索引，再回放扫描期间的事件，最后切换别名。
    // 重建期间保留 Outbox 待普通 worker 确认，失败可继续维护旧索引。
    public Map<String, Object> rebuild() {
        return locked(() -> {
            outbox.status();
            String index = writer.createIndex();
            long lastId = 0;
            long imported = 0;
            List<Long> ids;
            while (!(ids = source.nextIds(lastId, batchSize())).isEmpty()) {
                writer.write(index, ids, source.load(ids));
                imported += ids.size();
                lastId = ids.get(ids.size() - 1);
            }
            // Replay changes accumulated during the scan. Keep events until the live worker
            // acknowledges them, so a failed rebuild cannot consume the old index's changes.
            long lastEvent = 0;
            List<SearchOutbox.Event> events;
            while (!(events = outbox.after(lastEvent, batchSize())).isEmpty()) {
                writeEvents(index, events);
                lastEvent = events.get(events.size() - 1).entityId();
            }
            writer.refresh(index);
            writer.activate(index);
            log.info("Search index rebuilt, index={}, scannedRecords={}", index, imported);
            return Map.of("index", index, "scannedRecords", imported);
        });
    }

    private void writeEvents(String index, List<SearchOutbox.Event> events) {
        List<Long> ids = events.stream().map(SearchOutbox.Event::entityId).distinct().toList();
        writer.write(index, ids, source.load(ids));
    }

    private int batchSize() {
        return Math.max(1, Math.min(properties.getBatchSize(), 1000));
    }

    // 同一数据库连接持有 MySQL 命名锁直到操作结束，finally 释放。
    private <T> T locked(Supplier<T> operation) {
        return jdbc.execute((ConnectionCallback<T>) connection -> {
            String name;
            try (PreparedStatement statement = connection.prepareStatement("SELECT CONCAT(DATABASE(), ?)")) {
                statement.setString(1, lockSuffix);
                try (ResultSet result = statement.executeQuery()) {
                    result.next();
                    name = result.getString(1);
                }
            }
            if (!lock(connection, "GET_LOCK", name)) {
                throw BusinessException.conflict("搜索索引正在同步或重建，请稍后重试");
            }
            try {
                return operation.get();
            } finally {
                lock(connection, "RELEASE_LOCK", name);
            }
        });
    }

    private boolean lock(Connection connection, String function, String name) throws SQLException {
        String sql = function.equals("GET_LOCK") ? "SELECT GET_LOCK(?, 0)" : "SELECT RELEASE_LOCK(?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, name);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getInt(1) == 1;
            }
        }
    }
}
