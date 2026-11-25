/*
 * 阅读说明
 * 旧聊天入口的会话 ID 历史仓储接口，完整消息持久化由 Python 模块负责。
 */
package com.catgal.server.config.repository;

import java.util.List;
import java.util.Set;

public interface ChatHistoryRepository {
    void save(String girlCode, Long userId, String sessionId);

    List<String> getHistorySessionIds(String girlCode, Long userId);

    void deleteSession(String girlCode, Long userId, String sessionId);
}
