/*
 * 阅读说明
 * 搜索事务发件箱读取、确认、失败重试与积压统计。
 * 确认附带事件版本，防止处理旧任务时清掉并发新增的更新。
 */
package com.catgal.server.search;

import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class SearchOutbox {
    private final JdbcTemplate jdbc;
    private final String table;
    private final String idColumn;

    @Autowired
    public SearchOutbox(JdbcTemplate jdbc) {
        this(jdbc, "search_outbox", "game_id");
    }

    protected SearchOutbox(JdbcTemplate jdbc, String table, String idColumn) {
        if (!(table.equals("search_outbox") && idColumn.equals("game_id"))
                && !(table.equals("resource_search_outbox") && idColumn.equals("resource_id"))) {
            throw new IllegalArgumentException("Unsupported search outbox");
        }
        this.jdbc = jdbc;
        this.table = table;
        this.idColumn = idColumn;
    }

    public List<Event> pending(int limit) {
        return jdbc.query("SELECT " + idColumn + " AS entity_id, version FROM " + table + " WHERE pending = 1 AND next_attempt_at <= NOW(6) ORDER BY next_attempt_at, " + idColumn + " LIMIT ?",
                (row, index) -> new Event(row.getLong("entity_id"), row.getLong("version")), limit);
    }

    public List<Event> after(long id, int limit) {
        return jdbc.query("SELECT " + idColumn + " AS entity_id, version FROM " + table + " WHERE pending = 1 AND " + idColumn + " > ? ORDER BY " + idColumn + " LIMIT ?",
                (row, index) -> new Event(row.getLong("entity_id"), row.getLong("version")), id, limit);
    }

    // 只确认与已处理版本一致的任务，新发生的源更新继续留在队列。
    public void acknowledge(List<Event> events) {
        // A concurrent write increments version in the business transaction and stays pending.
        jdbc.batchUpdate("UPDATE " + table + " SET pending = 0, attempts = 0, last_error = NULL WHERE " + idColumn + " = ? AND version = ?", events, events.size(),
                (statement, event) -> {
                    statement.setLong(1, event.entityId());
                    statement.setLong(2, event.version());
                });
    }

    // 记录有界错误信息和下一次重试状态，运维接口可查看积压及显式重试。
    public void failed(List<Event> events, String errorType) {
        jdbc.batchUpdate("UPDATE " + table + " SET attempts = attempts + 1, last_error = ?, "
                        + "next_attempt_at = TIMESTAMPADD(SECOND, LEAST(300, POW(2, LEAST(attempts, 8))), NOW(6)) WHERE " + idColumn + " = ? AND version = ?",
                events, events.size(), (statement, event) -> {
                    statement.setString(1, errorType);
                    statement.setLong(2, event.entityId());
                    statement.setLong(3, event.version());
                });
    }

    public void retryFailures() {
        jdbc.update("UPDATE " + table + " SET next_attempt_at = NOW(6) WHERE pending = 1 AND attempts > 0");
    }

    public java.util.Map<String, Object> status() {
        return jdbc.queryForMap("SELECT COUNT(*) AS pending, COALESCE(SUM(attempts > 0), 0) AS failed, "
                + "MIN(dirty_since) AS oldest_event FROM " + table + " WHERE pending = 1");
    }

    public record Event(long entityId, long version) { }
}
