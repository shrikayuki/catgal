package com.catgal.server.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.List;
import java.util.UUID;

import static com.catgal.server.redis.RedisScriptLoader.load;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "CATGAL_REDIS_TEST", matches = "1")
class AiFavoriteSnapshotLiveTest {
    @Test void emptyPendingAndProcessingSnapshotsAreCurrentAndLargeHashesAreBounded() {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(System.getenv("REDIS_HOST"),
                Integer.parseInt(System.getenv().getOrDefault("REDIS_PORT", "6379")));
        String password = System.getenv("REDIS_PASSWORD");
        if (password != null && !password.isEmpty()) config.setPassword(RedisPassword.of(password));
        config.setDatabase(Integer.parseInt(System.getenv().getOrDefault("REDIS_DATABASE", "0")));
        LettuceConnectionFactory factory = new LettuceConnectionFactory(config);
        factory.afterPropertiesSet(); factory.start();
        StringRedisTemplate redis = new StringRedisTemplate(factory);
        String prefix = "catgal:test:ai-favorite:" + UUID.randomUUID();
        List<String> keys = List.of(prefix + ":hash", prefix + ":pending", prefix + ":processing");
        DefaultRedisScript<List> script = load("lua/ai-tools/favorite-snapshot.lua", List.class);
        try {
            assertEquals(0L, redis.execute(script, keys, "folder", "2").get(0));
            redis.opsForSet().add(keys.get(1), "folder");
            assertEquals(List.of(1L, 0L), redis.execute(script, keys, "folder", "2"));
            redis.opsForSet().remove(keys.get(1), "folder");
            redis.opsForSet().add(keys.get(2), "folder");
            assertEquals(List.of(1L, 0L), redis.execute(script, keys, "folder", "2"));
            redis.opsForHash().putAll(keys.get(0), java.util.Map.of("1", "7", "2", "7", "3", "7"));
            List<?> result = redis.execute(script, keys, "folder", "2");
            assertEquals(1L, result.get(0));
            assertEquals(1L, result.get(1));
            assertEquals(6, result.size());
        } finally {
            redis.delete(keys);
            factory.destroy();
        }
    }
}
