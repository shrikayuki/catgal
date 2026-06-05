package com.catgal.server.config.repository;


import com.catgal.common.utils.StringUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.catgal.common.constants.RedisConstant.AI_GIRL_CONVERSATION_HISTORY_KEY;

@Component
@RequiredArgsConstructor
public class RedisChatHistoryRepository implements ChatHistoryRepository {

    private final StringRedisTemplate redisTemplate;



    @Override
    public void save(String girlCode, Long userId, String sessionId) {
        String key = StringUtils.format(AI_GIRL_CONVERSATION_HISTORY_KEY, girlCode, userId);
        // 左推入，最新在最左边
        redisTemplate.opsForList().leftPush(key, sessionId);
        // 保留最近 50 条会话
        redisTemplate.opsForList().trim(key, 0, 49);
        redisTemplate.expire(key, 30, TimeUnit.DAYS);
    }

    @Override
    public List<String> getHistorySessionIds(String girlCode, Long userId) {
        String key = StringUtils.format(AI_GIRL_CONVERSATION_HISTORY_KEY, girlCode, userId);
        // 按时间倒序（最新在前）
        List<String> sessionIds = redisTemplate.opsForList().range(key, 0, -1);
        return sessionIds != null ? sessionIds : Collections.emptyList();
    }

    @Override
    public void deleteSession(String girlCode, Long userId, String sessionId) {
        String key = StringUtils.format(AI_GIRL_CONVERSATION_HISTORY_KEY, girlCode, userId);
        redisTemplate.opsForList().remove(key, 1, sessionId);
    }
}