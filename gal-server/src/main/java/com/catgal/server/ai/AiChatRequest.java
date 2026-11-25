/*
 * 阅读说明
 * Java 到 Python 的内部聊天协议，包含请求关联信息和经过业务校验的身份。
 */
package com.catgal.server.ai;

public record AiChatRequest(
        String requestId,
        Long userId,
        String sessionId,
        String girlCode,
        String message,
        String systemPrompt
) {
}
