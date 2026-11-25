package com.catgal.server.service.impl;

import com.catgal.common.utils.StringUtils;
import com.catgal.server.mapper.FavoriteFolderMapper;
import com.catgal.server.mapper.FavoriteItemMapper;
import com.catgal.server.mapper.GameMapper;
import com.catgal.server.service.IFavoriteItemService;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import static com.catgal.common.constants.RedisConstant.USER_FAVORITE_KEY;
import static com.catgal.common.constants.RedisConstant.USER_FAVORITE_EXPECTED_KEY;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

@EnabledIfEnvironmentVariable(named = "CATGAL_FOLDER_COUNTS_REDIS_TEST", matches = "1")
class FolderCountRedisLiveTest {
    @Test
    void pipelineReadsRealHashSizesAndWrongTypesUseFallback() {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(System.getenv("REDIS_HOST"),
                Integer.parseInt(System.getenv().getOrDefault("REDIS_PORT", "6379")));
        String password = System.getenv("REDIS_PASSWORD");
        if (password != null && !password.isEmpty()) config.setPassword(RedisPassword.of(password));
        config.setDatabase(Integer.parseInt(System.getenv().getOrDefault("REDIS_DATABASE", "0")));
        LettuceConnectionFactory factory = new LettuceConnectionFactory(config);
        factory.afterPropertiesSet();
        factory.start();
        StringRedisTemplate redis = new StringRedisTemplate(factory);
        Long id = ThreadLocalRandom.current().nextLong(Long.MAX_VALUE / 2, Long.MAX_VALUE - 1);
        String key = StringUtils.format(USER_FAVORITE_KEY, id);
        String missingKey = StringUtils.format(USER_FAVORITE_KEY, id + 1);
        String marker = StringUtils.format(USER_FAVORITE_EXPECTED_KEY, id);
        String emptyMarker = StringUtils.format(USER_FAVORITE_EXPECTED_KEY, id + 1);
        boolean ownsKey = false;
        try {
            assertFalse(Boolean.TRUE.equals(redis.hasKey(key)));
            assertFalse(Boolean.TRUE.equals(redis.hasKey(missingKey)));
            assertFalse(Boolean.TRUE.equals(redis.hasKey(marker)));
            assertFalse(Boolean.TRUE.equals(redis.hasKey(emptyMarker)));
            ownsKey = true;
            redis.opsForHash().putAll(key, Map.of("test-game-1", "test-user", "test-game-2", "test-user"));
            redis.opsForValue().set(marker, "2");
            FavoriteFolderServiceImpl service = new FavoriteFolderServiceImpl(mock(FavoriteFolderMapper.class),
                    mock(FavoriteItemMapper.class), mock(GameMapper.class), mock(IFavoriteItemService.class), redis);
            assertEquals(Map.of(id, 2), service.getGameCountsFromFolders(List.of(id, id + 1)));
            redis.opsForValue().set(emptyMarker, "0");
            assertEquals(Map.of(id, 2, id + 1, 0), service.getGameCountsFromFolders(List.of(id, id + 1)));
            redis.opsForValue().set(marker, "3");
            assertEquals(Map.of(id + 1, 0), service.getGameCountsFromFolders(List.of(id, id + 1)));
            redis.delete(key);
            redis.opsForValue().set(key, "wrong-type-fixture");
            assertTrue(service.getGameCountsFromFolders(List.of(id)).isEmpty());
        } finally {
            try {
                if (ownsKey) redis.delete(List.of(key, marker, emptyMarker));
            } finally {
                factory.destroy();
            }
        }
    }
}
