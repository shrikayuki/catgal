package com.catgal.server.config.repository;

import java.util.List;
import java.util.Set;

public interface ChatHistoryRepository {
    void save(String girlCode, Long userId, String sessionId);

    List<String> getHistorySessionIds(String girlCode, Long userId);

    void deleteSession(String girlCode, Long userId, String sessionId);
}
