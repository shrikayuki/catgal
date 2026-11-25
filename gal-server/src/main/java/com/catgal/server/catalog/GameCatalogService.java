/*
 * 阅读说明
 * 管理员游戏资料事务编排，处理草稿、完整更新、状态和标签会社关联。
 * 游戏行锁与 revision 共同防止并发覆盖，文件封面引用由独立存储服务负责。
 */
package com.catgal.server.catalog;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.catgal.common.context.UserContext;
import com.catgal.common.exception.BusinessException;
import com.catgal.server.domain.dto.admin.CatalogRelationsUpdateDTO;
import com.catgal.server.domain.dto.admin.GameCatalogCreateDTO;
import com.catgal.server.domain.dto.admin.GameCatalogUpdateDTO;
import com.catgal.server.domain.po.Game;
import com.catgal.server.domain.vo.CatalogEntryVO;
import com.catgal.server.domain.vo.admin.AdminGameDetailVO;
import com.catgal.server.mapper.GameMapper;
import java.net.URI;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class GameCatalogService {
    private final GameMapper games;
    private final GameRelationsService relations;
    private final GamePublicationCache publicationCache;

    @Transactional(readOnly = true)
    public AdminGameDetailVO detail(long gameId) {
        Game game = requireGame(gameId, false);
        return new AdminGameDetailVO(present(game), relations.entries(CatalogKind.TAG, gameId), relations.entries(CatalogKind.COMPANY, gameId));
    }

    @Transactional(readOnly = true)
    public List<CatalogEntryVO> publicRelations(CatalogKind kind, long gameId) {
        Game game = requireGame(gameId, false);
        if (!Integer.valueOf(1).equals(game.getStatus())) throw BusinessException.notFound("游戏不存在或未发布");
        return relations.entries(kind, gameId);
    }

    @Transactional
    // 基础资料和标签会社关联在同一事务创建，初始状态按草稿规则生成。
    public AdminGameDetailVO create(GameCatalogCreateDTO dto) {
        Long userId = UserContext.getUserId();
        if (userId == null) throw BusinessException.unauthorized("创建游戏需要登录");
        validate(dto);
        LocalDateTime now = LocalDateTime.now();
        Game game = new Game().setName(dto.getName().strip()).setCoverUrl(normalizeCover(dto.getCoverUrl()))
                .setIntroduction(dto.getIntroduction()).setReleaseDate(dto.getReleaseDate())
                .setPublisherId(userId).setStatus(0).setRevision(0L).setCreateTime(now).setUpdateTime(now);
        if (games.insert(game) != 1 || game.getId() == null) throw BusinessException.internalError("创建游戏失败");
        replaceRelations(game.getId(), dto);
        return detail(game.getId());
    }

    @Transactional
    // 持有游戏行锁后核对 revision，替换基础资料和关联并递增修订号。
    public AdminGameDetailVO update(long gameId, GameCatalogUpdateDTO dto) {
        validate(dto);
        Game game = lockRevision(gameId, dto.getRevision());
        String cover = normalizeCover(dto.getCoverUrl(), game);
        // Lock and update references before game/outbox updates to keep lock ordering consistent.
        replaceRelations(gameId, dto);
        int updated = games.update(null, Wrappers.<Game>lambdaUpdate()
                .eq(Game::getId, gameId).eq(Game::getRevision, game.getRevision())
                .set(Game::getName, dto.getName().strip()).set(Game::getCoverUrl, cover)
                .set(Game::getIntroduction, dto.getIntroduction()).set(Game::getReleaseDate, dto.getReleaseDate())
                .set(Game::getRevision, game.getRevision() + 1).set(Game::getUpdateTime, LocalDateTime.now()));
        if (updated != 1) throw BusinessException.conflict("游戏资料已被修改，请刷新后重试");
        return detail(gameId);
    }

    @Transactional
    public AdminGameDetailVO replaceRelations(CatalogKind kind, long gameId, CatalogRelationsUpdateDTO dto) {
        Game game = lockRevision(gameId, dto.getRevision());
        relations.replace(kind, gameId, dto.getIds());
        int updated = games.update(null, Wrappers.<Game>lambdaUpdate()
                .eq(Game::getId, gameId).eq(Game::getRevision, game.getRevision())
                .set(Game::getRevision, game.getRevision() + 1).set(Game::getUpdateTime, LocalDateTime.now()));
        if (updated != 1) throw BusinessException.conflict("游戏关联已被修改，请刷新后重试");
        return detail(gameId);
    }

    @Transactional
    public Game setStatus(long gameId, Integer status) {
        if (status == null || status < 0 || status > 2) throw BusinessException.badRequest("游戏状态只能是0草稿、1发布或2下架");
        Game game = requireGame(gameId, true);
        if (!Objects.equals(game.getStatus(), status)) {
            Integer oldStatus = game.getStatus();
            int updated = games.update(null, Wrappers.<Game>lambdaUpdate()
                    .eq(Game::getId, gameId).eq(Game::getRevision, game.getRevision())
                    .set(Game::getStatus, status).set(Game::getRevision, game.getRevision() + 1)
                    .set(Game::getUpdateTime, LocalDateTime.now()));
            if (updated != 1) throw BusinessException.conflict("游戏状态已被修改，请刷新后重试");
            game = games.selectById(gameId);
            log.info("管理员修改游戏状态, adminId={}, gameId={}, oldStatus={}, newStatus={}",
                    UserContext.getUserId(), gameId, oldStatus, status);
        }
        publicationCache.afterCommit(game);
        return present(game);
    }

    // 游戏行锁串行化同目标维护，revision 检查识别客户端读取后的过期表单。
    private Game lockRevision(long gameId, Long revision) {
        CatalogValidation.requireRevision(revision);
        Game game = requireGame(gameId, true);
        if (!Objects.equals(game.getRevision(), revision)) throw BusinessException.conflict("游戏资料或关联已被修改，请刷新后重试");
        return game;
    }

    private Game requireGame(long gameId, boolean lock) {
        if (gameId <= 0) throw BusinessException.badRequest("游戏ID必须为正整数");
        Game game = lock ? games.selectForUpdate(gameId) : games.selectById(gameId);
        if (game == null) throw BusinessException.notFound("游戏不存在");
        return game;
    }

    private void replaceRelations(long gameId, GameCatalogCreateDTO dto) {
        if (dto.getCompanyIds() == null || dto.getTagIds() == null) throw BusinessException.badRequest("关联集合不能为空，可使用空集合");
        relations.replaceAll(gameId, dto.getCompanyIds(), dto.getTagIds().stream().map(Integer::longValue).toList());
    }

    private void validate(GameCatalogCreateDTO dto) {
        String name = dto.getName() == null ? "" : dto.getName().strip();
        if (name.isEmpty() || name.length() > 100) throw BusinessException.badRequest("游戏名称须为1到100字符");
        if (dto.getIntroduction() != null && dto.getIntroduction().length() > 12000) throw BusinessException.badRequest("游戏介绍不能超过12000字符");
        if (dto.getReleaseDate() != null && (dto.getReleaseDate().getYear() < 1000 || dto.getReleaseDate().getYear() > 9999)) {
            throw BusinessException.badRequest("发售日期须在1000年至9999年之间");
        }
    }

    private Game present(Game game) {
        Game view = com.catgal.common.utils.BeanUtils.copyProperties(game, Game.class);
        view.setCoverUrl(com.catgal.server.storage.FileAccessPaths.gameCover(game));
        return view;
    }

    private String normalizeCover(String cover, Game game) {
        if (game.getCoverFileId() == null) return normalizeCover(cover);
        if (cover == null || cover.isBlank() || cover.equals(com.catgal.server.storage.FileAccessPaths.gameCover(game))) {
            return game.getCoverUrl();
        }
        throw BusinessException.badRequest("托管封面请通过封面上传或清除接口修改");
    }

    private String normalizeCover(String cover) {
        if (cover == null || cover.isBlank()) return null;
        String value = cover.strip();
        if (value.length() > 500) throw BusinessException.badRequest("封面地址不能超过500字符");
        try {
            URI uri = URI.create(value);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null) {
                throw new IllegalArgumentException();
            }
            return value;
        } catch (IllegalArgumentException exception) {
            throw BusinessException.badRequest("封面须为有效的HTTP或HTTPS地址");
        }
    }

}
