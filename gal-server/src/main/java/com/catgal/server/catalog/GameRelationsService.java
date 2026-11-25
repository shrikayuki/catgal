/*
 * 阅读说明
 * 游戏和标签、会社关联的批量读取与替换。
 * 替换前由调用方锁定游戏，关联资料共享锁防止检查后被并发删除。
 */
package com.catgal.server.catalog;

import com.catgal.common.domain.vo.GameVO;
import com.catgal.server.domain.vo.CatalogEntryVO;
import com.catgal.server.service.ICompanyService;
import com.catgal.server.service.ITagService;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class GameRelationsService {
    private final JdbcTemplate jdbc;
    private final ICompanyService companyService;
    private final ITagService tagService;

    public List<CatalogEntryVO> entries(CatalogKind kind, long gameId) {
        return jdbc.query("SELECT d.id, d.name, d.revision, d.create_time, d.update_time FROM " + kind.table
                + " d JOIN " + kind.relationTable + " r ON r." + kind.relationColumn
                + " = d.id WHERE r.game_id = ? ORDER BY d.id",
                (row, index) -> new CatalogEntryVO(row.getLong("id"), row.getString("name"),
                        row.getLong("revision"), row.getObject("create_time", java.time.LocalDateTime.class),
                        row.getObject("update_time", java.time.LocalDateTime.class)), gameId);
    }

    // 先按全部游戏 ID 批量读取关系，再分组附加到列表，避免逐游戏查询关联。
    public void attach(List<GameVO> games) {
        if (games.isEmpty()) return;
        List<Long> ids = games.stream().map(GameVO::getId).distinct().toList();
        Map<Long, List<String>> tags = names(CatalogKind.TAG, ids);
        Map<Long, List<String>> companies = names(CatalogKind.COMPANY, ids);
        for (GameVO game : games) {
            game.setTags(tags.getOrDefault(game.getId(), List.of()));
            game.setCompanies(companies.getOrDefault(game.getId(), List.of()));
        }
    }

    private Map<Long, List<String>> names(CatalogKind kind, List<Long> ids) {
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        Map<Long, List<String>> result = new HashMap<>();
        jdbc.query("SELECT r.game_id, d.name FROM " + kind.relationTable + " r JOIN " + kind.table
                        + " d ON d.id = r." + kind.relationColumn + " WHERE r.game_id IN (" + placeholders + ") ORDER BY d.id",
                row -> { result.computeIfAbsent(row.getLong("game_id"), ignored -> new java.util.ArrayList<>()).add(row.getString("name")); },
                ids.toArray());
        return result;
    }

    /** The enclosing transaction must already hold the game row lock. */
    void replace(CatalogKind kind, long gameId, Collection<Long> requested) {
        if (kind == CatalogKind.COMPANY) {
            companyService.lockEntries(requested);
        } else {
            tagService.lockEntries(requested);
        }
        replaceLocked(kind, gameId, requested);
    }

    void replaceAll(long gameId, Collection<Long> companyIds, Collection<Long> tagIds) {
        // Acquire every dictionary lock before relation triggers take outbox row locks.
        companyService.lockEntries(companyIds);
        tagService.lockEntries(tagIds);
        replaceLocked(CatalogKind.COMPANY, gameId, companyIds);
        replaceLocked(CatalogKind.TAG, gameId, tagIds);
    }

    // 调用方已持有游戏行锁，先验证关联资料并共享锁定，再替换对应关联行。
    private void replaceLocked(CatalogKind kind, long gameId, Collection<Long> requested) {
        Set<Long> desired = new TreeSet<>(requested);
        Set<Long> current = new TreeSet<>(jdbc.queryForList("SELECT " + kind.relationColumn + " FROM "
                + kind.relationTable + " WHERE game_id = ?", Long.class, gameId));
        List<Long> removed = current.stream().filter(id -> !desired.contains(id)).toList();
        List<Long> added = desired.stream().filter(id -> !current.contains(id)).toList();
        if (!removed.isEmpty()) {
            jdbc.batchUpdate("DELETE FROM " + kind.relationTable + " WHERE game_id = ? AND " + kind.relationColumn + " = ?",
                    removed, removed.size(), (statement, id) -> {
                        statement.setLong(1, gameId);
                        statement.setLong(2, id);
                    });
        }
        if (!added.isEmpty()) {
            jdbc.batchUpdate("INSERT INTO " + kind.relationTable + " (game_id, " + kind.relationColumn + ") VALUES (?, ?)",
                    added, added.size(), (statement, id) -> {
                        statement.setLong(1, gameId);
                        statement.setLong(2, id);
                    });
        }
    }
}
