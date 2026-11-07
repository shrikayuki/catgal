package com.catgal.server.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static com.catgal.server.redis.RedisScriptLoader.load;

/**
 * 可靠消费 Redis 脏集合：先原子转移到 processing，业务成功后确认，失败则重排队。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class RedisDirtySetProcessor {

    private static final Duration LOCK_TTL = Duration.ofMinutes(5);
    private static final DefaultRedisScript<List> CLAIM_SCRIPT =
            load("lua/dirty-set/claim.lua", List.class);
    private static final DefaultRedisScript<Long> REQUEUE_ALL_SCRIPT =
            load("lua/dirty-set/requeue-all.lua", Long.class);
    private static final DefaultRedisScript<Long> REQUEUE_SCRIPT =
            load("lua/dirty-set/requeue.lua", Long.class);
    private static final DefaultRedisScript<Long> RELEASE_LOCK_SCRIPT =
            load("lua/common/release-lock.lua", Long.class);

    private final StringRedisTemplate redisTemplate;
    private final TransactionTemplate transactionTemplate;

    public int processBatch(String pendingKey,
                            int batchSize,
                            boolean transactional,
                            Consumer<List<String>> consumer) {
        String processingKey = pendingKey + ":processing";
        String lockKey = "lock:dirty-set:" + pendingKey;
        String token = UUID.randomUUID().toString();
        Boolean locked = redisTemplate.opsForValue().setIfAbsent(lockKey, token, LOCK_TTL);
        if (!Boolean.TRUE.equals(locked)) {
            log.debug("脏集合已有实例处理, key={}", pendingKey);
            return 0;
        }

        List<String> claimed = Collections.emptyList();
        try {
            redisTemplate.execute(REQUEUE_ALL_SCRIPT, List.of(pendingKey, processingKey));
            claimed = claim(pendingKey, processingKey, batchSize);
            if (claimed.isEmpty()) {
                return 0;
            }
            List<String> immutableClaimed = List.copyOf(claimed);
            if (transactional) {
                transactionTemplate.executeWithoutResult(status -> consumer.accept(immutableClaimed));
            } else {
                consumer.accept(immutableClaimed);
            }
            redisTemplate.opsForSet().remove(processingKey, immutableClaimed.toArray());
            return immutableClaimed.size();
        } catch (RuntimeException exception) {
            if (!claimed.isEmpty()) {
                redisTemplate.execute(REQUEUE_SCRIPT,
                        List.of(pendingKey, processingKey),
                        (Object[]) claimed.toArray(String[]::new));
            }
            throw exception;
        } finally {
            redisTemplate.execute(RELEASE_LOCK_SCRIPT, List.of(lockKey), token);
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> claim(String pendingKey, String processingKey, int batchSize) {
        List<?> values = redisTemplate.execute(CLAIM_SCRIPT,
                List.of(pendingKey, processingKey), String.valueOf(batchSize));
        if (values == null || values.isEmpty()) {
            return Collections.emptyList();
        }
        return values.stream().map(String::valueOf).toList();
    }
}
