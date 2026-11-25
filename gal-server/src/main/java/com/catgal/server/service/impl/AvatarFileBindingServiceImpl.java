/*
 * 阅读说明
 * 用户头像的业务引用策略，锁用户行后授权并替换 avatar_file_id。
 * 通用文件服务维护对象状态，本策略维护用户归属与当前引用。
 */
package com.catgal.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.catgal.common.exception.BusinessException;
import com.catgal.server.domain.po.StorageFile;
import com.catgal.server.domain.po.User;
import com.catgal.server.mapper.StorageFileMapper;
import com.catgal.server.mapper.UserMapper;
import com.catgal.server.service.IFileBindingService;
import com.catgal.server.storage.FileAccessPaths;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AvatarFileBindingServiceImpl implements IFileBindingService {
    private final UserMapper users;
    private final StorageFileMapper files;

    @Override public String purpose() { return "AVATAR"; }
    @Override public String objectPrefix() { return "avatars"; }
    @Override public String publicPath(String fileId) { return FileAccessPaths.avatar(fileId); }
    @Override public Reference lockReference(long id) { return reference(users.selectForUpdate(id)); }
    @Override public Reference currentReference(long id) { return reference(users.selectById(id)); }

    private Reference reference(User user) {
        return user == null ? null : new Reference(user.getAvatarFileId(), null);
    }

    @Override
    // 上传者必须是头像所属用户，引用替换跟随用户行锁串行执行。
    public void authorizeUpload(long businessId, long uploaderId, Long revision, Reference reference) {
        if (businessId != uploaderId) throw BusinessException.forbidden("只能修改本人头像");
        if (reference == null) throw BusinessException.notFound("用户不存在");
    }

    @Override
    public Long replaceReference(long id, String fileId, Long revision) {
        if (users.update(null, new LambdaUpdateWrapper<User>().eq(User::getId, id)
                .set(User::getAvatarFileId, fileId).set(User::getAvatarUrl, null)) != 1) {
            throw BusinessException.internalError("头像保存失败");
        }
        return null;
    }

    @Override public StorageFile visibleFile(String id) { return files.selectPublicAvatar(id); }
}
