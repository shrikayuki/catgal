package com.catgal.server.catalog;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import static com.catgal.server.redis.RedisScriptLoader.load;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "CATGAL_REDIS_TEST", matches = "1")
class PublicationLuaLiveTest {
    @Test
    void rejectsStalePublicationAndPreservesBigintPrecision() {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(System.getenv("REDIS_HOST"),
                Integer.parseInt(System.getenv().getOrDefault("REDIS_PORT", "6379")));
        String password = System.getenv("REDIS_PASSWORD");
        if (password != null && !password.isEmpty()) config.setPassword(RedisPassword.of(password));
        config.setDatabase(Integer.parseInt(System.getenv().getOrDefault("REDIS_DATABASE", "0")));
        LettuceConnectionFactory factory = new LettuceConnectionFactory(config);
        factory.afterPropertiesSet();
        factory.start();
        StringRedisTemplate redis = new StringRedisTemplate(factory);
        String prefix = "catgal:test:publication:" + UUID.randomUUID();
        List<String> keys = List.of(prefix + ":ids", prefix + ":revisions");
        DefaultRedisScript<Long> script = load("lua/game-catalog/published-cache.lua", Long.class);
        try {
            assertEquals(1L, redis.execute(script, keys, "1", "2", "2"));
            assertEquals(0L, redis.execute(script, keys, "1", "1", "1"));
            assertFalse(Boolean.TRUE.equals(redis.opsForSet().isMember(keys.get(0), "1")));
            assertEquals(1L, redis.execute(script, keys, "1", "3", "1"));
            assertTrue(Boolean.TRUE.equals(redis.opsForSet().isMember(keys.get(0), "1")));
            assertEquals(1L, redis.execute(script, keys, "1", "9007199254740993", "2"));
            assertEquals(0L, redis.execute(script, keys, "1", "9007199254740992", "1"));
            assertFalse(Boolean.TRUE.equals(redis.opsForSet().isMember(keys.get(0), "1")));
        } finally {
            redis.delete(keys);
            factory.destroy();
        }
    }
}
