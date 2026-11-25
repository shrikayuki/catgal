package com.catgal.server.service.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import com.catgal.server.service.RedisDirtySetProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static com.catgal.server.redis.RedisScriptLoader.load;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

@EnabledIfEnvironmentVariable(named = "CATGAL_FAVORITE_ATOMIC_REDIS_TEST", matches = "1")
class FavoriteAtomicRedisLiveTest {
    private static final DefaultRedisScript<Long> CHANGE = load("lua/favorite/change.lua", Long.class);
    private static final DefaultRedisScript<List> SNAPSHOT = load("lua/favorite/snapshot.lua", List.class);
    private LettuceConnectionFactory factory;
    private StringRedisTemplate redis;
    private List<String> keys;

    @BeforeEach
    void setup() {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(System.getenv("REDIS_HOST"),
                Integer.parseInt(System.getenv().getOrDefault("REDIS_PORT", "6379")));
        String password = System.getenv("REDIS_PASSWORD");
        if (password != null && !password.isEmpty()) config.setPassword(RedisPassword.of(password));
        config.setDatabase(Integer.parseInt(System.getenv().getOrDefault("REDIS_DATABASE", "0")));
        factory = new LettuceConnectionFactory(config);
        factory.afterPropertiesSet();
        factory.start();
        redis = new StringRedisTemplate(factory);
        String prefix = "catgal:test:favorite:" + UUID.randomUUID() + ":";
        keys = List.of(prefix + "hash", prefix + "expected", prefix + "counter",
                prefix + "folder-dirty", prefix + "game-dirty");
        assertEquals(0L, redis.countExistingKeys(keys));
    }

    @AfterEach
    void cleanup() {
        try {
            if (redis != null && keys != null) {
                List<String> ownedKeys = new ArrayList<>(keys);
                ownedKeys.add(keys.get(3) + ":processing");
                ownedKeys.add("lock:dirty-set:" + keys.get(3));
                redis.delete(ownedKeys);
            }
        } finally {
            if (factory != null) factory.destroy();
        }
    }

    private Long change(String game, String delta, String baseline, String databaseHasItems) {
        return redis.execute(CHANGE, keys, game, "6", "10", delta, baseline, databaseHasItems);
    }

    private List<?> snapshot() {
        return redis.execute(SNAPSHOT, keys.subList(0, 2));
    }

    private void seed(String count) {
        redis.opsForValue().set(keys.get(1), "0");
        redis.opsForValue().set(keys.get(2), count);
    }

    private Long hydrate(String... games) {
        List<String> args = new ArrayList<>(List.of("10", "6"));
        args.addAll(List.of(games));
        return redis.execute(load("lua/favorite/hydrate.lua", Long.class),
                List.of(keys.get(0), keys.get(1), keys.get(3), keys.get(3) + ":processing"), args.toArray());
    }

    private long race(String delta) throws Exception {
        var executor = Executors.newFixedThreadPool(12);
        try {
            List<Callable<Long>> calls = new ArrayList<>();
            for (int index = 0; index < 48; index++) {
                calls.add(() -> change("20", delta, "10", "0"));
            }
            long mutations = 0;
            for (var result : executor.invokeAll(calls)) {
                Long value = result.get();
                if (value >= 0) mutations++;
                else assertEquals(-1L, value);
            }
            return mutations;
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentRepeatedAddsAndRemovalsChangeCounterExactlyOnce() throws Exception {
        seed("10");
        assertEquals(1, race("1"));
        assertEquals("11", redis.opsForValue().get(keys.get(2)));
        assertEquals("1", redis.opsForValue().get(keys.get(1)));
        assertEquals(1L, redis.opsForHash().size(keys.get(0)));
        assertTrue(Boolean.TRUE.equals(redis.opsForSet().isMember(keys.get(3), "10")));
        assertTrue(Boolean.TRUE.equals(redis.opsForSet().isMember(keys.get(4), "20")));
        assertEquals(1, race("-1"));
        assertEquals("10", redis.opsForValue().get(keys.get(2)));
        assertEquals("0", redis.opsForValue().get(keys.get(1)));
        assertEquals(List.of(1L, "0"), snapshot());
    }

    @Test
    void missingHistoricalCacheRejectsBothMutationsWithoutCreatingState() {
        assertEquals(-2L, change("20", "1", "10", "1"));
        assertEquals(-2L, change("20", "-1", "10", "1"));
        assertEquals(List.of(0L), snapshot());
        assertEquals(0L, redis.countExistingKeys(keys));
    }

    @Test
    void lostHashWithSurvivingMarkerCannotBecomeAnEmptySnapshot() {
        seed("10");
        assertEquals(11L, change("20", "1", "10", "0"));
        redis.delete(keys.get(0));
        assertEquals(List.of(0L), snapshot());
        assertEquals(-2L, change("21", "1", "10", "0"));
        assertEquals("11", redis.opsForValue().get(keys.get(2)));
        assertEquals("1", redis.opsForValue().get(keys.get(1)));
    }

    @Test
    void completeCacheLossCannotBeMistakenForConfirmedEmpty() {
        seed("10");
        assertEquals(11L, change("20", "1", "10", "0"));
        redis.delete(keys.subList(0, 2));
        assertEquals(List.of(0L), snapshot());
        assertEquals(-2L, change("20", "1", "10", "1"));
    }

    @Test
    void finalRemovalRetainsEmptyProofAndCanBeAddedAgainBeforeDatabaseSync() {
        seed("0");
        assertEquals(1L, change("20", "1", "0", "0"));
        assertEquals(0L, change("20", "-1", "0", "1"));
        assertEquals(List.of(1L, "0"), snapshot());
        assertEquals(1L, change("20", "1", "0", "1"));
        assertEquals(List.of(1L, "1", "20", "6"), snapshot());
    }

    @Test
    void legacyHashRequiresCleanDatabaseHydrationBeforeItCanBeChanged() {
        redis.opsForHash().putAll(keys.get(0), Map.of("20", "6", "21", "6"));
        redis.opsForValue().set(keys.get(2), "2");
        assertEquals(-2L, change("22", "1", "2", "1"));
        assertEquals(List.of(0L), snapshot());
        assertEquals(1L, hydrate("20", "21"));
        assertEquals(3L, change("22", "1", "2", "1"));
        assertEquals("3", redis.opsForValue().get(keys.get(1)));
        assertEquals(8, snapshot().size());
        assertEquals(1L, snapshot().get(0));
    }

    @Test
    void wrongTypesAndMalformedCountersFailBeforeAnyMutation() {
        redis.opsForValue().set(keys.get(1), "0");
        redis.opsForValue().set(keys.get(2), "broken");
        assertThrows(RuntimeException.class, () -> change("20", "1", "0", "0"));
        assertFalse(Boolean.TRUE.equals(redis.hasKey(keys.get(0))));
        assertEquals("0", redis.opsForValue().get(keys.get(1)));
        assertFalse(Boolean.TRUE.equals(redis.hasKey(keys.get(3))));
        redis.delete(keys.get(2));
        redis.opsForValue().set(keys.get(4), "wrong-type");
        assertThrows(RuntimeException.class, () -> change("20", "1", "0", "0"));
        assertFalse(Boolean.TRUE.equals(redis.hasKey(keys.get(0))));
        assertFalse(Boolean.TRUE.equals(redis.hasKey(keys.get(2))));
    }

    @Test
    void overflowCannotInsertRelationshipAndCounterNeverGoesNegative() {
        seed("2147483647");
        assertThrows(RuntimeException.class, () -> change("20", "1", "2147483647", "0"));
        assertFalse(Boolean.TRUE.equals(redis.hasKey(keys.get(0))));
        redis.opsForHash().put(keys.get(0), "20", "6");
        redis.opsForValue().set(keys.get(1), "1");
        redis.opsForValue().set(keys.get(2), "0");
        assertEquals(0L, change("20", "-1", "0", "1"));
        assertEquals("0", redis.opsForValue().get(keys.get(2)));
    }

    @Test
    void explicitHydrationRequiresBothDirtySetsToBeClear() {
        assertEquals(List.of(0L), snapshot());
        assertEquals(1L, hydrate());
        assertEquals(List.of(1L, "0"), snapshot());
        for (String dirty : List.of(keys.get(3), keys.get(3) + ":processing")) {
            redis.opsForSet().add(dirty, "10");
            assertEquals(0L, hydrate("20"));
            assertEquals(List.of(1L, "0"), snapshot());
            redis.opsForSet().remove(dirty, "10");
        }
        assertEquals(1L, hydrate("20"));
        assertEquals(List.of(1L, "1", "20", "6"), snapshot());
    }

    @Test
    void missingCounterCannotUseTheStaleDatabaseCounterArgument() {
        redis.opsForValue().set(keys.get(1), "0");
        assertEquals(-2L, change("20", "1", "99", "0"));
        assertFalse(Boolean.TRUE.equals(redis.hasKey(keys.get(0))));
        assertFalse(Boolean.TRUE.equals(redis.hasKey(keys.get(2))));
    }

    @Test
    void deletionJournalKeepsStateUntilAtomicFinalizationAndOnlyDeductsOnce() {
        seed("10");
        assertEquals(11L, change("20", "1", "10", "0"));
        String journal = "D:6:1:owned-token";
        assertEquals(1L, redis.execute(load("lua/favorite/prepare-delete.lua", Long.class),
                List.of(keys.get(0), keys.get(1), keys.get(3)), "10", "6", "owned-token", "1"));
        assertEquals("11", redis.opsForValue().get(keys.get(2)));
        assertEquals(List.of(2L, journal, "20", "6"), snapshot());
        assertEquals(-2L, change("20", "-1", "10", "1"));
        List<String> finishKeys = List.of(keys.get(0), keys.get(1), keys.get(3), keys.get(4), keys.get(2));
        var finish = load("lua/favorite/finish-delete.lua", Long.class);
        assertEquals(1L, redis.execute(finish, finishKeys, journal, "10", "delete", "20", "1"));
        assertEquals("10", redis.opsForValue().get(keys.get(2)));
        assertEquals(0L, redis.execute(finish, finishKeys, journal, "10", "delete", "20", "1"));
        assertEquals("10", redis.opsForValue().get(keys.get(2)));
        assertFalse(Boolean.TRUE.equals(redis.opsForSet().isMember(keys.get(3), "10")));
        assertFalse(Boolean.TRUE.equals(redis.hasKey(keys.get(1))));
    }

    @Test
    void rollbackRestoresOnlyItsOwnJournalAndPreservesTheRelationship() {
        seed("0");
        assertEquals(1L, change("20", "1", "0", "0"));
        assertEquals(1L, redis.execute(load("lua/favorite/prepare-delete.lua", Long.class),
                List.of(keys.get(0), keys.get(1), keys.get(3)), "10", "6", "owned-token", "1"));
        var restore = load("lua/favorite/restore-delete.lua", Long.class);
        assertEquals(0L, redis.execute(restore, List.of(keys.get(1)), "D:6:1:older-token"));
        assertEquals(1L, redis.execute(restore, List.of(keys.get(1)), "D:6:1:owned-token"));
        assertEquals(List.of(1L, "1", "20", "6"), snapshot());
        assertEquals("1", redis.opsForValue().get(keys.get(2)));
    }

    @Test
    void incompleteFinalizationFailsBeforeDeletingAnyState() {
        seed("0");
        assertEquals(1L, change("20", "1", "0", "0"));
        redis.delete(keys.get(2));
        assertEquals(0L, redis.execute(load("lua/favorite/finish-delete.lua", Long.class),
                List.of(keys.get(0), keys.get(1), keys.get(3), keys.get(4), keys.get(2)),
                "1", "10", "clear", "20", "1"));
        assertEquals(List.of(1L, "1", "20", "6"), snapshot());
        assertTrue(Boolean.TRUE.equals(redis.opsForSet().isMember(keys.get(3), "10")));
    }

    @Test
    void missingSnapshotIsRequeuedAndConfirmedEmptyCanLaterBeAcknowledged() {
        RedisDirtySetProcessor processor = new RedisDirtySetProcessor(redis,
                new TransactionTemplate(mock(PlatformTransactionManager.class)));
        String pending = keys.get(3);
        redis.opsForSet().add(pending, "10");
        assertThrows(IllegalStateException.class, () -> processor.processBatch(pending, 10, false, claimed -> {
            assertEquals(List.of("10"), claimed);
            assertEquals(List.of(0L), snapshot());
            throw new IllegalStateException("isolated missing cache fixture");
        }));
        assertTrue(Boolean.TRUE.equals(redis.opsForSet().isMember(pending, "10")));
        assertEquals(0L, redis.opsForSet().size(pending + ":processing"));
        redis.opsForValue().set(keys.get(1), "0");
        assertEquals(1, processor.processBatch(pending, 10, false,
                claimed -> assertEquals(List.of(1L, "0"), snapshot())));
        assertEquals(0L, redis.opsForSet().size(pending));
        assertEquals(0L, redis.opsForSet().size(pending + ":processing"));
    }
}
