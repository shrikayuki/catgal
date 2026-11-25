/*
 * 阅读说明
 * 区分机器人头像与立绘，集中定义业务用途、对象前缀及读取路径。
 */
package com.catgal.server.storage;

import com.catgal.common.exception.BusinessException;

public enum AiBotImageKind {
    AVATAR("AI_BOT_AVATAR", "ai-bot-avatars"),
    PORTRAIT("AI_BOT_PORTRAIT", "ai-bot-portraits");

    private final String purpose;
    private final String prefix;

    AiBotImageKind(String purpose, String prefix) {
        this.purpose = purpose;
        this.prefix = prefix;
    }

    public String purpose() { return purpose; }
    public String prefix() { return prefix; }

    public String path(String fileId) {
        if (fileId == null || !fileId.matches("[0-9a-f]{32}")) {
            throw new IllegalArgumentException("Invalid file identifier");
        }
        return "/api/files/" + prefix + "/" + fileId;
    }

    public String fileId(String url) {
        String start = "/api/files/" + prefix + "/";
        if (url == null || !url.startsWith(start)) return null;
        String id = url.substring(start.length());
        return id.matches("[0-9a-f]{32}") ? id : null;
    }

    public static AiBotImageKind fromPath(String value) {
        return switch (value) {
            case "avatar" -> AVATAR;
            case "portrait" -> PORTRAIT;
            default -> throw BusinessException.badRequest("请选择头像或立绘");
        };
    }
}
