/*
 * 阅读说明
 * 机器人头像和立绘的业务引用策略。
 * 通过 IFileBindingService 向通用上传服务提供归属、版本、路径和私有可见性规则。
 */
package com.catgal.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.catgal.common.exception.BusinessException;
import com.catgal.server.domain.po.AiBot;
import com.catgal.server.domain.po.StorageFile;
import com.catgal.server.mapper.AiBotMapper;
import com.catgal.server.mapper.StorageFileMapper;
import com.catgal.server.security.CurrentUserProvider;
import com.catgal.server.service.IFileBindingService;
import com.catgal.server.storage.AiBotImageKind;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class AiBotImageFileBindingServiceImpl implements IFileBindingService {
    private final AiBotImageKind kind;
    private final AiBotMapper bots;
    private final StorageFileMapper files;
    private final CurrentUserProvider currentUser;

    @Override public String purpose() { return kind.purpose(); }
    @Override public String objectPrefix() { return kind.prefix(); }
    @Override public String publicPath(String fileId) { return kind.path(fileId); }
    @Override public Reference lockReference(long id) { return reference(bots.selectForUpdate(id)); }
    @Override public Reference currentReference(long id) { return reference(bots.selectById(id)); }

    private Reference reference(AiBot bot) {
        if (bot == null) return null;
        String url = kind == AiBotImageKind.AVATAR ? bot.getAvatarUrl() : bot.getPortraitUrl();
        return new Reference(kind.fileId(url), bot.getRevision());
    }

    @Override
    // 核对机器人归属和当前修订号，平台机器人与个人形象按业务策略限制更新。
    public void authorizeUpload(long id, long uploaderId, Long revision, Reference reference) {
        // 上传前和对象写入后均在业务行锁内重新检查归属、状态及版本。
        AiBot bot = bots.selectById(id);
        if (bot == null || !Objects.equals(bot.getUserId(), uploaderId)) {
            throw BusinessException.notFound("聊天伙伴不存在");
        }
        if (!"custom".equals(bot.getKind()) || !Integer.valueOf(1).equals(bot.getStatus())) {
            throw BusinessException.forbidden("请选择正在使用的个人聊天伙伴");
        }
        if (revision == null || revision <= 0 || revision == Long.MAX_VALUE) {
            throw BusinessException.badRequest("请刷新伙伴配置后重试");
        }
        if (reference == null || !Objects.equals(reference.revision(), revision)
                || !Objects.equals(bot.getRevision(), revision)) {
            throw BusinessException.conflict("伙伴配置已更新，请刷新后重试");
        }
    }

    @Override
    public Long replaceReference(long id, String fileId, Long revision) {
        String url = fileId == null ? "" : publicPath(fileId);
        var update = new LambdaUpdateWrapper<AiBot>().eq(AiBot::getId, id)
                .eq(AiBot::getUserId, currentUser.requireUserId()).eq(AiBot::getKind, "custom")
                .eq(AiBot::getStatus, 1).eq(AiBot::getRevision, revision)
                .set(AiBot::getRevision, revision + 1)
                .set(AiBot::getUpdateTime, LocalDateTime.now(ZoneOffset.UTC));
        if (kind == AiBotImageKind.AVATAR) update.set(AiBot::getAvatarUrl, url);
        else update.set(AiBot::getPortraitUrl, url);
        if (bots.update(null, update) != 1) {
            throw BusinessException.conflict("伙伴配置已更新，请刷新后重试");
        }
        return revision + 1;
    }

    @Override
    // 仅当前登录用户所属机器人当前有效图片可以被打开。
    public StorageFile visibleFile(String fileId) {
        return files.selectOwnedAiBotImage(fileId, purpose(), currentUser.requireUserId());
    }
}
