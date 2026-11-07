package com.catgal.server.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.List;

import static com.catgal.server.redis.RedisScriptLoader.load;

/**
 * Redis 计数器的原子写入口：初始化、增减、非负保护和脏标记在同一个 Lua 脚本中完成。
 */
@Service
@RequiredArgsConstructor
public class RedisCounterService {

    private static final DefaultRedisScript<Long> CHANGE_AND_MARK_SCRIPT =
            load("lua/counter/change-and-mark.lua", Long.class);

    private final StringRedisTemplate redisTemplate;

    public long changeAndMark(String counterKey,
                              long initialValue,
                              long delta,
                              String dirtySetKey,
                              Long businessId) {
        Long result = redisTemplate.execute(
                CHANGE_AND_MARK_SCRIPT,
                List.of(counterKey, dirtySetKey),
                String.valueOf(Math.max(0L, initialValue)),
                String.valueOf(delta),
                String.valueOf(businessId)
        );
        if (result == null) {
            throw new IllegalStateException("Redis 计数更新未返回结果");
        }
        return result;
    }
}
