/*
 * 阅读说明
 * 游戏封面的业务引用策略，游戏行锁与 revision 协调上传、替换和清除。
 * 公开已发布游戏的当前封面，草稿和下架封面按当前管理员权限读取。
 */
package com.catgal.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.catgal.common.exception.BusinessException;
import com.catgal.server.domain.po.Game;
import com.catgal.server.domain.po.StorageFile;
import com.catgal.server.domain.po.User;
import com.catgal.server.mapper.GameMapper;
import com.catgal.server.mapper.StorageFileMapper;
import com.catgal.server.mapper.UserMapper;
import com.catgal.server.security.CurrentUserProvider;
import com.catgal.server.service.IFileBindingService;
import com.catgal.server.storage.FileAccessPaths;
import java.time.LocalDateTime;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class GameCoverFileBindingServiceImpl implements IFileBindingService {
    private final GameMapper games;
    private final UserMapper users;
    private final StorageFileMapper files;
    private final CurrentUserProvider currentUser;

    @Override public String purpose() { return "GAME_COVER"; }
    @Override public String objectPrefix() { return "game-covers"; }
    @Override public String publicPath(String fileId) { return FileAccessPaths.gameCover(fileId); }
    @Override public Reference lockReference(long id) { return reference(games.selectForUpdate(id)); }
    @Override public Reference currentReference(long id) { return reference(games.selectById(id)); }

    private Reference reference(Game game) {
        return game == null ? null : new Reference(game.getCoverFileId(), game.getRevision());
    }

    @Override
    // 文件生命周期先锁游戏，再核对管理员与 revision，绑定替换由当前版本约束。
    public void authorizeUpload(long businessId, long uploaderId, Long revision, Reference reference) {
        if (!isAdmin(uploaderId)) throw BusinessException.forbidden("管理封面需要管理员权限");
        if (reference == null) throw BusinessException.notFound("游戏不存在");
        if (revision == null || revision < 0) throw BusinessException.badRequest("revision须为非负整数");
        if (!Objects.equals(revision, reference.revision())) throw BusinessException.conflict("游戏资料已变化，请刷新后重试");
    }

    @Override
    public Long replaceReference(long id, String fileId, Long revision) {
        int updated = games.update(null, new LambdaUpdateWrapper<Game>().eq(Game::getId, id)
                .eq(Game::getRevision, revision).set(Game::getCoverFileId, fileId).set(Game::getCoverUrl, null)
                .set(Game::getRevision, revision + 1).set(Game::getUpdateTime, LocalDateTime.now()));
        if (updated != 1) throw BusinessException.conflict("游戏资料已变化，请刷新后重试");
        return revision + 1;
    }

    @Override
    // 仅当前 READY 封面可读，发布游戏允许公开读取，其他状态要求当前管理员。
    public StorageFile visibleFile(String id) {
        Long userId = currentUser.getOptionalUserId();
        return files.selectVisibleGameCover(id, userId != null && isAdmin(userId));
    }

    private boolean isAdmin(long id) {
        User user = users.selectById(id);
        return user != null && Integer.valueOf(2).equals(user.getRole());
    }
}
