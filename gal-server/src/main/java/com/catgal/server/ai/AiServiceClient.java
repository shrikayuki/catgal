/*
 * 阅读说明
 * Java 调用 Python AI 能力的端口，隔离 Controller 与具体 HTTP 实现。
 */
package com.catgal.server.ai;

import com.fasterxml.jackson.databind.JsonNode;
import reactor.core.publisher.Flux;
import java.util.Map;

public interface AiServiceClient {
    Flux<String> streamChat(AiChatCommand command);
    Flux<AiChatEvent> streamChatEvents(AiChatCommand command);
    JsonNode persistenceRequest(String operation, Map<String, Object> payload);
    Flux<AiChatEvent> persistentChat(Map<String, Object> payload);
}
