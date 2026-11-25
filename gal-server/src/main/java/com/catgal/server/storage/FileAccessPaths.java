/*
 * 阅读说明
 * 从文件引用投影可访问的业务读取路径，兼容历史外链字段。
 */
package com.catgal.server.storage;

import com.catgal.server.domain.po.User;
import com.catgal.server.domain.po.Game;

public final class FileAccessPaths {
    private FileAccessPaths() {}

    // 存在托管文件引用时投影应用读取路径，历史数据继续使用旧头像字段。
    public static String avatar(String fileId) {
        if (fileId == null || !fileId.matches("[0-9a-f]{32}")) {
            throw new IllegalArgumentException("Invalid file identifier");
        }
        return "/api/files/avatars/" + fileId;
    }

    // 存在托管文件引用时投影应用读取路径，历史数据继续使用旧头像字段。
    public static String avatar(User user) {
        return user.getAvatarFileId() == null ? user.getAvatarUrl() : avatar(user.getAvatarFileId());
    }

    // 存在托管封面引用时投影业务路径，历史外链通过原字段兼容。
    public static String gameCover(String fileId) {
        if (fileId == null || !fileId.matches("[0-9a-f]{32}")) {
            throw new IllegalArgumentException("Invalid file identifier");
        }
        return "/api/files/game-covers/" + fileId;
    }

    // 存在托管封面引用时投影业务路径，历史外链通过原字段兼容。
    public static String gameCover(Game game) {
        return game.getCoverFileId() == null ? game.getCoverUrl() : gameCover(game.getCoverFileId());
    }
}
