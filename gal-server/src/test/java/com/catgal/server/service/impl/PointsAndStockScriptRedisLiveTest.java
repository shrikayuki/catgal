package com.catgal.server.service.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import static com.catgal.common.constants.RedisConstant.CUTE_POINTS_HASH_KEY;
import static com.catgal.common.constants.RedisConstant.TOTAL_EARNED_HASH_KEY;
import static com.catgal.common.constants.RedisConstant.TOTAL_SPENT_HASH_KEY;
import static com.catgal.server.redis.RedisScriptLoader.load;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 真实 Redis 回归，验证脚本出错时的写前保护及正常请求的幂等、并发语义。
 * 每例使用随机命名空间，仅删除本例明确创建的六个键。
 */
@EnabledIfEnvironmentVariable(named = "CATGAL_REDIS_TEST", matches = "1")
class PointsAndStockScriptRedisLiveTest {
    private static final DefaultRedisScript<Long> POINTS = load("lua/cute-points/change-once.lua", Long.class);
    private static final DefaultRedisScript<Long> STOCK = load("lua/prize/decrement-stock.lua", Long.class);
    private static final DefaultRedisScript<Long> PROJECTION = load("lua/cute-points/project-balance.lua", Long.class);
    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate redis;
    private List<String> keys;

    @BeforeAll
    static void connect() {
        var configuration = new RedisStandaloneConfiguration(System.getenv("REDIS_HOST"),
                Integer.parseInt(System.getenv().getOrDefault("REDIS_PORT", "6379")));
        String password = System.getenv("REDIS_PASSWORD");
        if (password != null && !password.isEmpty()) configuration.setPassword(RedisPassword.of(password));
        configuration.setDatabase(Integer.parseInt(System.getenv().getOrDefault("REDIS_DATABASE", "0")));
        factory = new LettuceConnectionFactory(configuration);
        factory.afterPropertiesSet();
        factory.start();
        redis = new StringRedisTemplate(factory);
    }

    @BeforeEach
    void prepareOwnedKeys() {
        String prefix = "catgal:test:points-stock:" + UUID.randomUUID() + ":";
        keys = List.of(prefix + "points", prefix + "points-dirty", prefix + "processed",
                prefix + "prizes", prefix + "maintenance", prefix + "stock-dirty");
        assertEquals(0L, redis.countExistingKeys(keys));
    }

    @AfterEach
    void cleanOwnedKeys() {
        if (keys != null) redis.delete(keys);
    }

    @AfterAll
    static void disconnect() {
        if (factory != null) factory.destroy();
    }

    private void seedPoints(String balance, String earned, String spent) {
        redis.opsForHash().putAll(keys.get(0), Map.of(CUTE_POINTS_HASH_KEY, balance,
                TOTAL_EARNED_HASH_KEY, earned, TOTAL_SPENT_HASH_KEY, spent));
    }

    private Long points(String delta) {
        return redis.execute(POINTS, keys.subList(0, 3), CUTE_POINTS_HASH_KEY,
                TOTAL_EARNED_HASH_KEY, "60", delta, TOTAL_SPENT_HASH_KEY, "6");
    }

    private void seedPrize(int total, int remaining) {
        redis.opsForHash().put(keys.get(3), "20",
                "{\"totalStock\":%d,\"remainStock\":%d}".formatted(total, remaining));
    }

    private Long stock(String quantity) {
        return redis.execute(STOCK, keys.subList(3, 6), "20", quantity);
    }

    @Test
    void projectionRejectsOlderVersionsBeyondJavascriptIntegerPrecision() {
        String key = keys.get(0);
        assertEquals(1L, redis.execute(PROJECTION, List.of(key), "9007199254740993", "10", "15", "5", "300"));
        assertEquals(0L, redis.execute(PROJECTION, List.of(key), "9007199254740992", "15", "15", "0", "300"));
        assertEquals("10", redis.opsForHash().get(key, "balance"));
        assertEquals(1L, redis.execute(PROJECTION, List.of(key), "9007199254740993", "10", "15", "5", "300"));
        assertTrue(redis.getExpire(key) > 0 && redis.getExpire(key) <= 300);
    }

    @Test
    void projectionValidatesAllValuesBeforeFirstWrite() {
        String key = keys.get(0);
        assertThrows(DataAccessException.class, () -> redis.execute(PROJECTION, List.of(key), "1", "-1", "1", "0", "300"));
        assertFalse(Boolean.TRUE.equals(redis.hasKey(key)));
        redis.opsForValue().set(key, "wrong-type");
        assertThrows(DataAccessException.class, () -> redis.execute(PROJECTION, List.of(key), "1", "1", "1", "0", "300"));
        assertEquals("wrong-type", redis.opsForValue().get(key));
    }

    @Test
    void wrongPointsDirtyKeyTypeCannotPartiallyChangeBalanceOrConsumeBusinessId() {
        seedPoints("10", "10", "0");
        redis.opsForValue().set(keys.get(1), "wrong-type");
        Map<Object, Object> original = redis.opsForHash().entries(keys.get(0));

        assertThrows(DataAccessException.class, () -> points("1"));

        assertEquals(original, redis.opsForHash().entries(keys.get(0)));
        assertFalse(Boolean.TRUE.equals(redis.hasKey(keys.get(2))));
        assertEquals("wrong-type", redis.opsForValue().get(keys.get(1)));
    }

    @Test
    void invalidEarnedFieldCannotLeaveBalanceChangedAndBusinessIdConsumed() {
        seedPoints("10", "invalid", "0");
        Map<Object, Object> original = redis.opsForHash().entries(keys.get(0));

        assertThrows(DataAccessException.class, () -> points("1"));

        assertEquals(original, redis.opsForHash().entries(keys.get(0)));
        assertEquals(0L, redis.countExistingKeys(keys.subList(1, 3)));
    }

    @Test
    void cumulativeIntegerOverflowIsRejectedBeforeAnyMutation() {
        seedPoints("10", String.valueOf(Integer.MAX_VALUE), "0");
        Map<Object, Object> original = redis.opsForHash().entries(keys.get(0));

        assertThrows(DataAccessException.class, () -> points("1"));

        assertEquals(original, redis.opsForHash().entries(keys.get(0)));
        assertEquals(0L, redis.countExistingKeys(keys.subList(1, 3)));
    }

    @Test
    void balanceIntegerOverflowIsRejectedBeforeAnyMutation() {
        seedPoints(String.valueOf(Integer.MAX_VALUE), "10", "0");
        Map<Object, Object> original = redis.opsForHash().entries(keys.get(0));

        assertThrows(DataAccessException.class, () -> points("1"));

        assertEquals(original, redis.opsForHash().entries(keys.get(0)));
        assertEquals(0L, redis.countExistingKeys(keys.subList(1, 3)));
    }

    @Test
    void spentIntegerOverflowCannotDeductBalance() {
        seedPoints("10", "10", String.valueOf(Integer.MAX_VALUE));
        Map<Object, Object> original = redis.opsForHash().entries(keys.get(0));

        assertThrows(DataAccessException.class, () -> points("-1"));

        assertEquals(original, redis.opsForHash().entries(keys.get(0)));
        assertEquals(0L, redis.countExistingKeys(keys.subList(1, 3)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "1.5", "invalid", "9223372036854775807"})
    void invalidOrNativeOverflowingTtlFailsBeforeTheFirstWrite(String ttl) {
        seedPoints("10", "10", "0");
        Map<Object, Object> original = redis.opsForHash().entries(keys.get(0));

        assertThrows(DataAccessException.class, () -> redis.execute(POINTS, keys.subList(0, 3),
                CUTE_POINTS_HASH_KEY, TOTAL_EARNED_HASH_KEY, ttl, "1", TOTAL_SPENT_HASH_KEY, "6"));

        assertEquals(original, redis.opsForHash().entries(keys.get(0)));
        assertEquals(0L, redis.countExistingKeys(keys.subList(1, 3)));
    }

    @Test
    void wrongProcessedKeyTypeIsKeptForDiagnosis() {
        seedPoints("10", "10", "0");
        redis.opsForSet().add(keys.get(2), "wrong-type");

        assertThrows(DataAccessException.class, () -> points("1"));

        assertEquals("10", redis.opsForHash().get(keys.get(0), CUTE_POINTS_HASH_KEY));
        assertTrue(Boolean.TRUE.equals(redis.opsForSet().isMember(keys.get(2), "wrong-type")));
        assertFalse(Boolean.TRUE.equals(redis.hasKey(keys.get(1))));
    }

    @Test
    void aliasedCounterFieldsCannotDoubleCreditTheBalance() {
        seedPoints("10", "10", "0");
        Map<Object, Object> original = redis.opsForHash().entries(keys.get(0));

        assertThrows(DataAccessException.class, () -> redis.execute(POINTS, keys.subList(0, 3),
                CUTE_POINTS_HASH_KEY, CUTE_POINTS_HASH_KEY, "60", "1", TOTAL_SPENT_HASH_KEY, "6"));

        assertEquals(original, redis.opsForHash().entries(keys.get(0)));
        assertEquals(0L, redis.countExistingKeys(keys.subList(1, 3)));
    }

    @Test
    void aliasedKeysAreRejectedBeforeCreatingAnyState() {
        assertThrows(DataAccessException.class, () -> redis.execute(POINTS,
                List.of(keys.get(0), keys.get(0), keys.get(2)), CUTE_POINTS_HASH_KEY,
                TOTAL_EARNED_HASH_KEY, "60", "1", TOTAL_SPENT_HASH_KEY, "6"));
        assertThrows(DataAccessException.class, () -> redis.execute(STOCK,
                List.of(keys.get(3), keys.get(4), keys.get(3)), "20", "1"));
        assertEquals(0L, redis.countExistingKeys(keys));
    }

    @ParameterizedTest
    @ValueSource(strings = {"+10", "010", "1.5", "-1", "2147483648"})
    void nonCanonicalOrOutOfRangeBalanceCannotConsumeBusinessId(String value) {
        seedPoints(value, "10", "0");
        Map<Object, Object> original = redis.opsForHash().entries(keys.get(0));

        assertThrows(DataAccessException.class, () -> points("1"));

        assertEquals(original, redis.opsForHash().entries(keys.get(0)));
        assertEquals(0L, redis.countExistingKeys(keys.subList(1, 3)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"+1", "01", "1.0", "0", "2147483648", "-2147483649"})
    void invalidDeltaCannotModifyAnyField(String delta) {
        seedPoints("10", "10", "0");
        Map<Object, Object> original = redis.opsForHash().entries(keys.get(0));

        assertThrows(DataAccessException.class, () -> points(delta));

        assertEquals(original, redis.opsForHash().entries(keys.get(0)));
        assertEquals(0L, redis.countExistingKeys(keys.subList(1, 3)));
    }

    @Test
    void insufficientBalanceAndValidDebitPreserveExistingReturnProtocol() {
        seedPoints("10", "10", "0");
        assertEquals(-1L, points("-11"));
        assertEquals(0L, redis.countExistingKeys(keys.subList(1, 3)));
        assertEquals(5L, points("-5"));
        assertEquals("5", redis.opsForHash().get(keys.get(0), CUTE_POINTS_HASH_KEY));
        assertEquals("10", redis.opsForHash().get(keys.get(0), TOTAL_EARNED_HASH_KEY));
        assertEquals("5", redis.opsForHash().get(keys.get(0), TOTAL_SPENT_HASH_KEY));
        assertEquals(-2L, points("-5"));
    }

    @Test
    void concurrentRedeliveriesApplyOneCreditAndOneDirtyMember() throws Exception {
        seedPoints("10", "10", "0");
        List<Long> results = race(() -> points("1"));

        assertEquals(1, results.stream().filter(value -> value == 11L).count());
        assertEquals(23, results.stream().filter(value -> value == -2L).count());
        assertEquals("11", redis.opsForHash().get(keys.get(0), CUTE_POINTS_HASH_KEY));
        assertEquals("11", redis.opsForHash().get(keys.get(0), TOTAL_EARNED_HASH_KEY));
        assertEquals(1L, redis.opsForSet().size(keys.get(1)));
        assertTrue(redis.getExpire(keys.get(2)) > 0);
    }

    @Test
    void absentPointsHashCanStillBeInitializedByAValidCredit() {
        assertEquals(1L, points("1"));
        assertEquals("1", redis.opsForHash().get(keys.get(0), CUTE_POINTS_HASH_KEY));
        assertEquals("1", redis.opsForHash().get(keys.get(0), TOTAL_EARNED_HASH_KEY));
    }

    @Test
    void wrongStockDirtyKeyTypeCannotLeaveStockDeducted() {
        seedPrize(1, 1);
        Object original = redis.opsForHash().get(keys.get(3), "20");
        redis.opsForValue().set(keys.get(5), "wrong-type");

        assertThrows(DataAccessException.class, () -> stock("1"));

        assertEquals(original, redis.opsForHash().get(keys.get(3), "20"));
        assertEquals("wrong-type", redis.opsForValue().get(keys.get(5)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-json", "[]", "{\"totalStock\":1,\"remainStock\":\"1\"}",
            "{\"totalStock\":1,\"remainStock\":-1}", "{\"totalStock\":1,\"remainStock\":2}",
            "{\"totalStock\":1,\"remainStock\":0.5}", "{\"totalStock\":2147483648,\"remainStock\":1}",
            "{\"totalStock\":1,\"remainStock\":NaN}", "{\"totalStock\":1,\"remainStock\":Infinity}"})
    void malformedStockDocumentIsRejectedWithoutWriting(String document) {
        redis.opsForHash().put(keys.get(3), "20", document);

        assertThrows(DataAccessException.class, () -> stock("1"));

        assertEquals(document, redis.opsForHash().get(keys.get(3), "20"));
        assertFalse(Boolean.TRUE.equals(redis.hasKey(keys.get(5))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "1.5", "+1", "01", "2147483648"})
    void invalidQuantityCannotIncreaseOrFractionallyDecreaseStock(String quantity) {
        seedPrize(5, 5);
        Object original = redis.opsForHash().get(keys.get(3), "20");

        assertThrows(DataAccessException.class, () -> stock(quantity));

        assertEquals(original, redis.opsForHash().get(keys.get(3), "20"));
        assertFalse(Boolean.TRUE.equals(redis.hasKey(keys.get(5))));
    }

    @Test
    void maintenanceMissingPrizeAndUnlimitedStockKeepExistingReturnProtocol() {
        assertEquals(0L, stock("1"));
        seedPrize(0, 0);
        Object original = redis.opsForHash().get(keys.get(3), "20");
        assertEquals(1L, stock("1"));
        assertEquals(original, redis.opsForHash().get(keys.get(3), "20"));
        assertFalse(Boolean.TRUE.equals(redis.hasKey(keys.get(5))));
        redis.opsForValue().set(keys.get(4), "owner");
        assertEquals(-1L, stock("1"));
        assertEquals(original, redis.opsForHash().get(keys.get(3), "20"));
    }

    @Test
    void concurrentDrawsNeverConsumeMoreThanAvailableStock() throws Exception {
        seedPrize(5, 5);
        List<Long> results = race(() -> stock("1"));

        assertEquals(5, results.stream().filter(value -> value == 1L).count());
        assertEquals(19, results.stream().filter(value -> value == 0L).count());
        assertTrue(redis.opsForHash().get(keys.get(3), "20").toString().contains("\"remainStock\":0"));
        assertEquals(1L, redis.opsForSet().size(keys.get(5)));
    }

    private List<Long> race(Callable<Long> operation) throws Exception {
        var executor = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Long>> requests = new ArrayList<>();
            for (int index = 0; index < 24; index++) requests.add(operation);
            List<Long> results = new ArrayList<>();
            for (var result : executor.invokeAll(requests)) results.add(result.get());
            return results;
        } finally {
            executor.shutdownNow();
        }
    }
}
