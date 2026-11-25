package com.catgal.server.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "CATGAL_AI_LIVE_TEST", matches = "1")
class AiPythonLiveTest {
    @Test
    void javaStreamsPythonProviderAndCommitsOneCompleteRedisExchange() {
        AiServiceProperties properties = new AiServiceProperties();
        properties.setBaseUrl(System.getenv("AI_SERVICE_BASE_URL"));
        properties.setHmacSecret(System.getenv("AI_SERVICE_HMAC_SECRET"));
        PythonAiServiceClient client = new PythonAiServiceClient(
                new AiServiceClientConfig().aiServiceWebClient(properties), new ObjectMapper(),
                properties, new AiServiceRequestSigner());
        String session = "qa-" + UUID.randomUUID();
        long user = 9223372036854775806L;
        String memoryKey = "gal:chat:memory:user:" + user + ":girl:qa:session:" + session;
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(System.getenv("REDIS_HOST"),
                Integer.parseInt(System.getenv().getOrDefault("REDIS_PORT", "6379")));
        config.setDatabase(Integer.parseInt(System.getenv().getOrDefault("REDIS_DATABASE", "0")));
        String password = System.getenv("REDIS_PASSWORD");
        if (password != null && !password.isEmpty()) config.setPassword(RedisPassword.of(password));
        LettuceConnectionFactory factory = new LettuceConnectionFactory(config);
        factory.afterPropertiesSet();
        StringRedisTemplate redis = new StringRedisTemplate(factory);
        try {
            List<AiChatEvent> events = client.streamChatEvents(new AiChatCommand(user, "qa", session,
                    "请只回复连接成功", "你是连通测试助手，只回复简短测试结果。"))
                    .collectList().block(Duration.ofSeconds(100));
            assertNotNull(events);
            assertEquals("meta", events.get(0).type());
            assertEquals("done", events.get(events.size() - 1).type(),
                    () -> "Unexpected terminal code: " + events.get(events.size() - 1).code());
            assertTrue(events.stream().anyMatch(event -> "delta".equals(event.type())));
            assertEquals(2L, redis.opsForList().size(memoryKey));
            assertFalse(Boolean.TRUE.equals(redis.hasKey(memoryKey + ":generation")));
        } finally {
            redis.delete(List.of(memoryKey, memoryKey + ":generation"));
            factory.destroy();
        }
    }
}
