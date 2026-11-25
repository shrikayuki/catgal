/*
 * 阅读说明
 * 导出游戏向量源数据并维护独立同步队列，供 Python embedding worker 使用。
 * 版本确认使用源版本约束，查询仍由数据库核验公开状态。
 */
package com.catgal.server.ai.vector;

import com.catgal.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Independent delivery state for Python's public-game vector index. No model or ES calls run in this transaction. */
@Service
@RequiredArgsConstructor
public class AiVectorCatalogService {
    private final JdbcTemplate jdbc;

    public record Version(String gameId, String version) { }
    public record Pending(List<Version> items, boolean hasMore) { }
    public record PublicGame(String id, String name, String introduction, LocalDate releaseDate,
                             List<String> tagIds, List<String> tags, List<String> companyIds,
                             List<String> companies, List<String> languages, List<String> platforms,
                             List<String> resourceTypes) { }
    public record ExportItem(String gameId, String version, boolean deleted, PublicGame game) { }
    public record Export(List<ExportItem> items) { }
    public record Acknowledged(int acknowledged, int stale) { }
    public record Failed(int updated, int stale) { }
    public record Rebuild(long queued) { }
    public record Status(long pending, long failed, String oldestEvent) { }
    private record Source(long id, String name, String introduction, LocalDate releaseDate,
                          String languages, String platforms, String types) { }
    private record Relation(long gameId, long id, String name) { }

    public Pending pending(int limit) {
        if (limit < 1 || limit > 100) throw BusinessException.badRequest("同步批量须在1到100之间");
        List<Version> rows = jdbc.query("SELECT game_id,version FROM ai_game_vector_outbox "
                        + "WHERE pending=1 AND next_attempt_at<=UTC_TIMESTAMP(6) "
                        + "ORDER BY next_attempt_at,game_id LIMIT ?",
                (rs, index) -> new Version(rs.getString(1), rs.getString(2)), limit + 1);
        return new Pending(rows.stream().limit(limit).toList(), rows.size() > limit);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Export export(List<Long> requestedIds) {
        List<Long> ids = requestedIds.stream().distinct().sorted().toList();
        String placeholders = placeholders(ids.size());
        // Reading versions first establishes the same snapshot used for the public document and relations.
        Map<Long, Long> versions = new LinkedHashMap<>();
        jdbc.query("SELECT game_id,version FROM ai_game_vector_outbox WHERE game_id IN (" + placeholders + ")",
                rs -> { versions.put(rs.getLong(1), rs.getLong(2)); }, ids.toArray());
        if (versions.size() != ids.size()) throw BusinessException.conflict("游戏向量源状态缺失，请先重建同步队列");
        List<Source> sources = jdbc.query("SELECT id,LEFT(name,200),LEFT(introduction,6000),release_date,"
                        + "languages,platforms,types FROM game WHERE status=1 AND id IN (" + placeholders + ")",
                (rs, index) -> new Source(rs.getLong(1), rs.getString(2), rs.getString(3),
                        rs.getObject(4, LocalDate.class), rs.getString(5), rs.getString(6), rs.getString(7)), ids.toArray());
        Map<Long, List<Relation>> tags = relations(ids, true);
        Map<Long, List<Relation>> companies = relations(ids, false);
        Map<Long, PublicGame> games = new LinkedHashMap<>();
        for (Source source : sources) {
            List<Relation> gameTags = tags.getOrDefault(source.id(), List.of());
            List<Relation> gameCompanies = companies.getOrDefault(source.id(), List.of());
            games.put(source.id(), new PublicGame(String.valueOf(source.id()), clip(source.name(), 200),
                    clip(source.introduction(), 6000), source.releaseDate(), relationIds(gameTags), names(gameTags),
                    relationIds(gameCompanies), names(gameCompanies), csv(source.languages()), csv(source.platforms()), csv(source.types())));
        }
        return new Export(requestedIds.stream().distinct().map(id -> new ExportItem(String.valueOf(id),
                String.valueOf(versions.get(id)), !games.containsKey(id), games.get(id))).toList());
    }

    @Transactional
    // 源版本和处理版本相同才确认队列，导出后再发生的修改继续积压等待下一轮。
    public Acknowledged acknowledge(List<AiVectorCatalogRequests.Version> items) {
        requireDistinct(items);
        int updated = 0;
        for (AiVectorCatalogRequests.Version item : ordered(items)) {
            updated += jdbc.update("UPDATE ai_game_vector_outbox SET pending=0,attempts=0,last_error_code=NULL,"
                    + "updated_at=UTC_TIMESTAMP(6) WHERE game_id=? AND version=? AND pending=1", item.gameId(), item.version());
        }
        return new Acknowledged(updated, items.size() - updated);
    }

    @Transactional
    // 固定错误码记录同步失败，保留任务版本与有限错误信息供后续重试。
    public Failed failed(List<AiVectorCatalogRequests.Version> items, AiVectorCatalogRequests.FailureCode errorCode) {
        requireDistinct(items);
        int updated = 0;
        for (AiVectorCatalogRequests.Version item : ordered(items)) {
            updated += jdbc.update("UPDATE ai_game_vector_outbox SET "
                    + "next_attempt_at=TIMESTAMPADD(SECOND,LEAST(300,5*POW(2,LEAST(attempts,6))),UTC_TIMESTAMP(6)),"
                    + "attempts=LEAST(attempts+1,1000000),last_error_code=?,updated_at=UTC_TIMESTAMP(6) "
                    + "WHERE game_id=? AND version=? AND pending=1", errorCode.name(), item.gameId(), item.version());
        }
        return new Failed(updated, items.size() - updated);
    }

    @Transactional
    public Rebuild rebuild() {
        // Match ordinary catalog writes' game -> trigger/outbox lock order when seeding missing rows.
        jdbc.update("INSERT IGNORE INTO ai_game_vector_outbox(game_id,next_attempt_at,dirty_since,updated_at) "
                + "SELECT id,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6) FROM game ORDER BY id");
        // Increment even already-pending rows to fence every pre-rebuild in-flight export/ack.
        jdbc.update("UPDATE ai_game_vector_outbox SET version=version+1,pending=1,attempts=0,"
                + "next_attempt_at=UTC_TIMESTAMP(6),last_error_code=NULL,dirty_since=UTC_TIMESTAMP(6),updated_at=UTC_TIMESTAMP(6)");
        Long queued = jdbc.queryForObject("SELECT COUNT(*) FROM ai_game_vector_outbox WHERE pending=1", Long.class);
        return new Rebuild(queued == null ? 0 : queued);
    }

    public Status status() {
        return jdbc.queryForObject("SELECT COUNT(*),COALESCE(SUM(attempts>0),0),MIN(dirty_since) "
                + "FROM ai_game_vector_outbox WHERE pending=1", (rs, index) -> {
            LocalDateTime oldest = rs.getObject(3, LocalDateTime.class);
            return new Status(rs.getLong(1), rs.getLong(2), oldest == null ? null : oldest + "Z");
        });
    }

    private Map<Long, List<Relation>> relations(List<Long> ids, boolean tags) {
        String relation = tags ? "game_tag" : "game_company";
        String table = tags ? "tag" : "company";
        String column = tags ? "tag_id" : "company_id";
        // Legacy data can exceed current API limits. Bound each game's metadata in SQL before materializing it.
        List<Relation> rows = jdbc.query("SELECT game_id,id,name FROM (SELECT r.game_id,d.id,LEFT(d.name,80) name,"
                + "ROW_NUMBER() OVER (PARTITION BY r.game_id ORDER BY d.id) position FROM " + relation
                + " r JOIN " + table + " d ON d.id=r." + column + " WHERE r.game_id IN (" + placeholders(ids.size())
                + ")) ranked WHERE position<=50 ORDER BY game_id,id",
                (rs, index) -> new Relation(rs.getLong(1), rs.getLong(2), rs.getString(3)), ids.toArray());
        Map<Long, List<Relation>> grouped = new LinkedHashMap<>();
        for (Relation row : rows) grouped.computeIfAbsent(row.gameId(), ignored -> new ArrayList<>()).add(row);
        return grouped;
    }

    private void requireDistinct(List<AiVectorCatalogRequests.Version> items) {
        if (items.stream().map(AiVectorCatalogRequests.Version::gameId).distinct().count() != items.size())
            throw BusinessException.badRequest("同步批次不能包含重复游戏");
    }

    private List<AiVectorCatalogRequests.Version> ordered(List<AiVectorCatalogRequests.Version> items) {
        return items.stream().sorted(Comparator.comparing(AiVectorCatalogRequests.Version::gameId)).toList();
    }

    private String placeholders(int count) { return String.join(",", Collections.nCopies(count, "?")); }
    private List<String> relationIds(List<Relation> rows) { return rows.stream().map(row -> String.valueOf(row.id())).toList(); }
    private List<String> names(List<Relation> rows) { return rows.stream().limit(20).map(row -> clip(row.name(), 80)).toList(); }
    private List<String> csv(String value) {
        return value == null ? List.of() : Arrays.stream(value.split(",")).map(String::trim)
                .filter(item -> !item.isEmpty()).distinct().sorted().limit(50).map(item -> clip(item, 80)).toList();
    }
    private String clip(String value, int maximum) {
        if (value == null) return "";
        return value.substring(0, value.offsetByCodePoints(0, Math.min(value.codePointCount(0, value.length()), maximum)));
    }
}
