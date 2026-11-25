/*
 * 阅读说明
 * Java 业务层传给 AI 客户端的完整聊天命令，身份与提示词由服务端补齐。
 */
package com.catgal.server.ai;

public record AiChatCommand(
        Long userId,
        String girlCode,
        String sessionId,
        String userMessage,
        String systemPrompt
) {
}
