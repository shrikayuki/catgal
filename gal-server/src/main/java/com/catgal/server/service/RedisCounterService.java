/*
 * 阅读说明
 * 用 Lua 把计数初始化、增减、边界校验与标脏放到一次 Redis 原子操作中。
 */
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

    // 计数冷初始化、增量和业务 ID 标脏一起执行，减少写入成功却缺少同步任务的窗口。
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
