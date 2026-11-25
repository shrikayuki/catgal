/*
 * 阅读说明
 * 在数据库事务中绑定、替换和解除外部条目，使用游戏 revision 检查并发。
 * 公开读取只输出允许展示的来源快照。
 */
package com.catgal.server.catalog.external;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.catgal.common.context.UserContext;
import com.catgal.common.exception.BusinessException;
import com.catgal.server.domain.po.Game;
import com.catgal.server.mapper.GameMapper;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Remote requests finish before entering these short database transactions. */
@Service
@RequiredArgsConstructor
public class ExternalLinkStore {
    private final JdbcTemplate jdbc;
    private final GameMapper games;

    @Transactional(readOnly = true)
    public ExternalLinkState adminLinks(long gameId) {
        Game game = requireGame(gameId, false);
        return new ExternalLinkState(game.getRevision(), links(gameId));
    }

    @Transactional(readOnly = true)
    public List<ExternalLinkVO> publicLinks(long gameId) {
        if (!Integer.valueOf(1).equals(requireGame(gameId, false).getStatus())) {
            throw BusinessException.notFound("游戏不存在或未发布");
        }
        return links(gameId);
    }

    @Transactional
    // 外部预览确认后在短事务锁游戏并校验 revision，绑定同站条目受唯一约束保护。
    public ExternalLinkState bind(long gameId, long revision, ExternalGamePreview item) {
        Long adminId = UserContext.getUserId();
        if (adminId == null) throw BusinessException.unauthorized("绑定外部条目需要登录");
        Game game = lockRevision(gameId, revision);
        ExternalSource source = ExternalSource.parse(item.provider());
        String id = source.normalizeId(item.externalId());
        if (item.title() == null || item.title().isBlank() || item.title().length() > 512) throw ExternalCatalogHttp.unavailable();
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM game_external_link WHERE game_id = ? AND provider = ?",
                Long.class, gameId, source.key());
        try {
            if (count != null && count > 0) {
                jdbc.update("UPDATE game_external_link SET external_id = ?, snapshot_title = ?, update_time = CURRENT_TIMESTAMP"
                        + " WHERE game_id = ? AND provider = ?", id, item.title(), gameId, source.key());
            } else {
                jdbc.update("INSERT INTO game_external_link (game_id, provider, external_id, snapshot_title, created_by)"
                        + " VALUES (?, ?, ?, ?, ?)", gameId, source.key(), id, item.title(), adminId);
            }
        } catch (DuplicateKeyException exception) {
            throw BusinessException.conflict("该外部条目已关联其他游戏，请先核对并解除原关联");
        }
        advance(game);
        return adminLinks(gameId);
    }

    @Transactional
    // 核验游戏修订号后移除来源关联，返回递增版本供后续维护继续使用。
    public ExternalLinkState unbind(long gameId, ExternalSource source, long revision) {
        Game game = lockRevision(gameId, revision);
        if (jdbc.update("DELETE FROM game_external_link WHERE game_id = ? AND provider = ?", gameId, source.key()) > 0) advance(game);
        return adminLinks(gameId);
    }

    private List<ExternalLinkVO> links(long gameId) {
        return jdbc.query("SELECT provider, external_id, snapshot_title, create_time, update_time FROM game_external_link"
                + " WHERE game_id = ? ORDER BY provider", (row, index) -> {
            ExternalSource source = ExternalSource.parse(row.getString("provider"));
            String id = source.normalizeId(row.getString("external_id"));
            return new ExternalLinkVO(source.key(), id, row.getString("snapshot_title"), source.pageUrl(id),
                    row.getObject("create_time", LocalDateTime.class), row.getObject("update_time", LocalDateTime.class));
        }, gameId);
    }

    private Game lockRevision(long gameId, long revision) {
        if (revision < 0 || revision == Long.MAX_VALUE) throw BusinessException.badRequest("修订号超出有效范围");
        Game game = requireGame(gameId, true);
        if (game.getRevision() != revision) throw BusinessException.conflict("游戏资料或关联已修改，请刷新后重试");
        return game;
    }

    private Game requireGame(long gameId, boolean lock) {
        if (gameId <= 0) throw BusinessException.badRequest("游戏ID必须为正整数");
        Game game = lock ? games.selectForUpdate(gameId) : games.selectById(gameId);
        if (game == null) throw BusinessException.notFound("游戏不存在");
        return game;
    }

    private void advance(Game game) {
        if (games.update(null, Wrappers.<Game>lambdaUpdate().eq(Game::getId, game.getId()).eq(Game::getRevision, game.getRevision())
                .set(Game::getRevision, game.getRevision() + 1).set(Game::getUpdateTime, LocalDateTime.now())) != 1) {
            throw BusinessException.conflict("游戏资料已修改，请刷新后重试");
        }
    }
}
