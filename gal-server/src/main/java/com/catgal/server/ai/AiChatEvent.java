/*
 * 阅读说明
 * 浏览器 SSE 的结构化事件模型，meta、delta、done 和 error 对应生成生命周期。
 * 推荐卡片和文本通过同一事件协议传递，客户端据事件类型更新状态。
 */
package com.catgal.server.ai;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AiChatEvent(String type, String requestId, String sessionId,
                          String content, String code, String message,
                          String messageId, String userMessageId, Boolean replayed,
                          String status, String model, List<Recommendation> items) {
    public record Recommendation(String id, String name, String reason) { }
    public AiChatEvent(String type, String requestId, String sessionId, String content,
                       String code, String message, String messageId, String userMessageId,
                       Boolean replayed, String status, String model) {
        this(type, requestId, sessionId, content, code, message, messageId, userMessageId,
                replayed, status, model, null);
    }
    public AiChatEvent(String type, String requestId, String sessionId,
                       String content, String code, String message) {
        this(type, requestId, sessionId, content, code, message, null, null, null, null, null, null);
    }

    public static AiChatEvent meta(String requestId, String sessionId) {
        return new AiChatEvent("meta", requestId, sessionId, null, null, null);
    }

    public static AiChatEvent delta(String requestId, String content) {
        return new AiChatEvent("delta", requestId, null, content, null, null);
    }

    public static AiChatEvent done(String requestId) {
        return new AiChatEvent("done", requestId, null, null, null, null);
    }

    public static AiChatEvent error(String requestId, String code, String message) {
        return new AiChatEvent("error", requestId, null, null, code, message);
    }
}
