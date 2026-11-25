package com.catgal.server.service.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import static com.catgal.server.redis.RedisScriptLoader.load;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "CATGAL_LIKE_REDIS_TEST", matches = "1")
class LikeStateRedisLiveTest {
    private static final DefaultRedisScript<Long> ADD = load("lua/like/add.lua", Long.class);
    private static final DefaultRedisScript<Long> REMOVE = load("lua/like/remove.lua", Long.class);
    private static final DefaultRedisScript<Long> HYDRATE = load("lua/like/hydrate.lua", Long.class);
    private static final DefaultRedisScript<Long> CLEAR = load("lua/like/clear.lua", Long.class);
    private static final DefaultRedisScript<List> SNAPSHOT = load("lua/like/snapshot.lua", List.class);
    private static final DefaultRedisScript<List> INSPECT = load("lua/like/inspect.lua", List.class);
    private LettuceConnectionFactory factory;
    private StringRedisTemplate redis;
    private List<String> keys;

    @BeforeEach void setup() {
        var config = new RedisStandaloneConfiguration(System.getenv("REDIS_HOST"),
                Integer.parseInt(System.getenv().getOrDefault("REDIS_PORT", "6379")));
        String password = System.getenv("REDIS_PASSWORD");
        if (password != null && !password.isEmpty()) config.setPassword(RedisPassword.of(password));
        config.setDatabase(Integer.parseInt(System.getenv().getOrDefault("REDIS_DATABASE", "0")));
        factory = new LettuceConnectionFactory(config);
        factory.afterPropertiesSet();
        factory.start();
        redis = new StringRedisTemplate(factory);
        String prefix = "catgal:test:like:" + UUID.randomUUID() + ":";
        keys = List.of(prefix + "members", prefix + "expected", prefix + "index", prefix + "records",
                prefix + "records:processing", prefix + "counts", prefix + "counts:processing");
    }

    @AfterEach void cleanup() {
        try {
            if (redis != null && keys != null) {
                redis.delete(keys);
                redis.delete("lock:dirty-set:" + keys.get(3));
            }
        }
        finally { if (factory != null) factory.destroy(); }
    }

    private List<String> mutationKeys() { return List.of(keys.get(0), keys.get(1), keys.get(2), keys.get(3), keys.get(5)); }
    private List<?> snapshot() { return redis.execute(SNAPSHOT, keys.subList(0, 2)); }
    private long change(DefaultRedisScript<Long> script, String owner) { return redis.execute(script, mutationKeys(), owner, "20"); }
    private long hydrate(String... owners) {
        List<String> arguments = new ArrayList<>(List.of("20"));
        arguments.addAll(List.of(owners));
        return redis.execute(HYDRATE, keys, arguments.toArray());
    }

    @Test void missingAndDamagedSetsCannotBecomeConfirmedEmpty() {
        assertEquals(List.of(0L), snapshot());
        assertEquals(-2L, change(ADD, "6"));
        redis.opsForValue().set(keys.get(1), "1");
        assertEquals(List.of(0L), snapshot());
        assertEquals(-2L, change(REMOVE, "6"));
        assertEquals(0L, redis.opsForSet().size(keys.get(3)));
    }

    @Test void cleanHydrationRestoresAllDatabaseOwnersAndPreservesTheIndex() {
        assertEquals(1L, hydrate("6", "7"));
        assertEquals("2", redis.opsForValue().get(keys.get(1)));
        assertEquals(java.util.Set.of("6", "7"), redis.opsForSet().members(keys.get(0)));
        assertTrue(redis.opsForSet().isMember(keys.get(2), "20"));
        assertTrue(redis.opsForSet().isMember(keys.get(5), "20"));
        assertEquals(List.of(2L, 1L), redis.execute(INSPECT, keys.subList(0, 2), "6"));
    }

    @Test void everyPendingOrProcessingQueueBlocksUnsafeHydration() {
        for (int index : List.of(3, 4, 5, 6)) {
            redis.opsForSet().add(keys.get(index), "20");
            assertEquals(0L, hydrate("6"));
            assertFalse(redis.hasKey(keys.get(1)));
            redis.opsForSet().remove(keys.get(index), "20");
        }
    }

    @Test void concurrentDuplicatesChangeMembershipAndMarkersOnce() throws Exception {
        hydrate();
        var executor = Executors.newFixedThreadPool(12);
        try {
            for (var script : List.of(ADD, REMOVE)) {
                List<Callable<Long>> calls = new ArrayList<>();
                for (int index = 0; index < 48; index++) calls.add(() -> change(script, "6"));
                long mutations = 0;
                for (var result : executor.invokeAll(calls)) mutations += result.get();
                assertEquals(1L, mutations);
            }
        } finally { executor.shutdownNow(); }
        assertEquals("0", redis.opsForValue().get(keys.get(1)));
        assertEquals(List.of(1L), snapshot());
        assertFalse(redis.opsForSet().isMember(keys.get(2), "20"));
        assertTrue(redis.opsForSet().isMember(keys.get(3), "20"));
    }

    @Test void malformedOrMissingMarkersNeverAuthorizeSnapshots() {
        redis.opsForSet().add(keys.get(0), "6");
        for (String marker : List.of("-1", "1.0", "NaN", "2", "deleted")) {
            redis.opsForValue().set(keys.get(1), marker);
            assertEquals(List.of(0L), snapshot());
            assertEquals(List.of(-1L, 0L), redis.execute(INSPECT, keys.subList(0, 2), "6"));
        }
    }

    @Test void wrongQueueTypesAreDetectedBeforeMembershipIsWritten() {
        hydrate();
        redis.delete(keys.get(5));
        redis.opsForValue().set(keys.get(5), "wrong-type");
        assertThrows(RuntimeException.class, () -> change(ADD, "6"));
        assertEquals("0", redis.opsForValue().get(keys.get(1)));
        assertFalse(redis.hasKey(keys.get(0)));
        assertFalse(redis.hasKey(keys.get(3)));
    }

    @Test void snapshotAndInspectionRejectWrongMemberTypes() {
        redis.opsForValue().set(keys.get(0), "wrong-type");
        redis.opsForValue().set(keys.get(1), "0");
        assertEquals(List.of(0L), snapshot());
        assertEquals(List.of(-1L, 0L), redis.execute(INSPECT, keys.subList(0, 2), ""));
    }

    @Test void explicitDeletionClearsStateAndPendingButPreservesClaimedOwnership() {
        hydrate("6");
        redis.opsForSet().add(keys.get(3), "20");
        redis.opsForSet().add(keys.get(4), "20");
        redis.opsForSet().add(keys.get(6), "20");
        assertEquals(1L, redis.execute(CLEAR, mutationKeys(), "20"));
        assertFalse(redis.hasKey(keys.get(0)));
        assertFalse(redis.hasKey(keys.get(1)));
        assertFalse(redis.opsForSet().isMember(keys.get(3), "20"));
        assertFalse(redis.opsForSet().isMember(keys.get(5), "20"));
        assertTrue(redis.opsForSet().isMember(keys.get(4), "20"));
        assertTrue(redis.opsForSet().isMember(keys.get(6), "20"));
    }

    @Test void partialMembershipLossRejectsMutationsAndHydrationWithoutOverwritingTheRemainder() {
        hydrate("6", "7");
        redis.opsForSet().remove(keys.get(0), "7");
        assertEquals(List.of(0L), snapshot());
        assertEquals(-2L, change(ADD, "8"));
        assertEquals(0L, hydrate("9"));
        assertEquals(java.util.Set.of("6"), redis.opsForSet().members(keys.get(0)));
        assertEquals("2", redis.opsForValue().get(keys.get(1)));
    }

    @Test void incompleteSnapshotRequeuesTheClaimedDirtyTask() {
        hydrate("6");
        redis.opsForSet().add(keys.get(3), "20");
        redis.delete(keys.get(0));
        var processor = new com.catgal.server.service.RedisDirtySetProcessor(redis,
                new org.springframework.transaction.support.TransactionTemplate(
                        org.mockito.Mockito.mock(org.springframework.transaction.PlatformTransactionManager.class)));
        assertThrows(IllegalStateException.class, () -> processor.processBatch(keys.get(3), 1, false, claimed -> {
            assertEquals(List.of("20"), claimed);
            assertEquals(List.of(0L), snapshot());
            throw new IllegalStateException("Incomplete like cache");
        }));
        assertTrue(redis.opsForSet().isMember(keys.get(3), "20"));
        assertFalse(redis.opsForSet().isMember(keys.get(4), "20"));
    }
}
