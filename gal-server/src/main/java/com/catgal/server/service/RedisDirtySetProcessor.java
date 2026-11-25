/*
 * 阅读说明
 * 脏任务通用处理器，pending 领取到 processing，数据库成功后确认，失败重排。
 * 锁 token 标识批次所有者，过期实例停止确认或释放其他实例的任务锁。
 */
package com.catgal.server.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.Collections;
import java.util.ArrayList;
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

    // 覆盖一个批次的最长执行时间，超时后其他实例可接管。修改时需结合真实批次耗时验证。
    private static final Duration LOCK_TTL = Duration.ofMinutes(5);
    private static final DefaultRedisScript<List> CLAIM_SCRIPT =
            load("lua/dirty-set/claim.lua", List.class);
    private static final DefaultRedisScript<Long> REQUEUE_ALL_SCRIPT =
            load("lua/dirty-set/requeue-all.lua", Long.class);
    private static final DefaultRedisScript<Long> REQUEUE_SCRIPT =
            load("lua/dirty-set/requeue.lua", Long.class);
    private static final DefaultRedisScript<Long> ACK_SCRIPT =
            load("lua/dirty-set/ack.lua", Long.class);
    private static final DefaultRedisScript<Long> RELEASE_LOCK_SCRIPT =
            load("lua/common/release-lock.lua", Long.class);

    private final StringRedisTemplate redisTemplate;
    private final TransactionTemplate transactionTemplate;

    /**
     * @param pendingKey 待处理业务 ID 集合，处理中集合自动使用同名加 :processing
     * @param batchSize 本次最多领取的目标数量
     * @param transactional true 时整批数据库操作加入事务，失败后回滚再重新排队
     * @param consumer 处理函数需要支持重试，外部消息的持久化确认由具体业务保证
     */
    public int processBatch(String pendingKey,
                            int batchSize,
                            boolean transactional,
                            Consumer<List<String>> consumer) {
        if (pendingKey == null || pendingKey.isBlank() || batchSize <= 0 || consumer == null) {
            throw new IllegalArgumentException("脏任务需要有效的键、正数批次和处理函数");
        }
        String processingKey = pendingKey + ":processing";
        String lockKey = "lock:dirty-set:" + pendingKey;
        String token = UUID.randomUUID().toString();
        Boolean locked = redisTemplate.opsForValue().setIfAbsent(lockKey, token, LOCK_TTL);
        if (!Boolean.TRUE.equals(locked)) {
            log.debug("脏集合已有实例处理, key={}", pendingKey);
            return 0;
        }

        List<String> claimed = Collections.emptyList();
        RuntimeException failure = null;
        try {
            // 持有本次 token 后才恢复遗留 processing，旧实例无法确认新实例已领取的任务。
            requireOwnership(redisTemplate.execute(REQUEUE_ALL_SCRIPT,
                    List.of(pendingKey, processingKey, lockKey), token));
            claimed = claim(pendingKey, processingKey, lockKey, token, batchSize);
            if (claimed.isEmpty()) {
                return 0;
            }
            List<String> immutableClaimed = List.copyOf(claimed);
            requireCurrentOwner(lockKey, token);
            if (transactional) {
                transactionTemplate.executeWithoutResult(status -> {
                    consumer.accept(immutableClaimed);
                    // 提交前再次检查锁，降低超时实例继续写入的风险。Redis 与 MySQL 仍分属两次提交。
                    requireCurrentOwner(lockKey, token);
                });
            } else {
                consumer.accept(immutableClaimed);
            }
            // 数据库提交成功后再确认 processing，同步中产生的新 pending 成员会继续保留。
            requireOwnership(redisTemplate.execute(ACK_SCRIPT,
                    List.of(processingKey, lockKey), arguments(token, immutableClaimed)));
            return immutableClaimed.size();
        } catch (RuntimeException exception) {
            failure = exception;
            if (!claimed.isEmpty()) {
                try {
                    requireOwnership(redisTemplate.execute(REQUEUE_SCRIPT,
                            List.of(pendingKey, processingKey, lockKey), arguments(token, claimed)));
                } catch (RuntimeException requeueFailure) {
                    if (requeueFailure != exception) exception.addSuppressed(requeueFailure);
                }
            }
            throw exception;
        } finally {
            try {
                redisTemplate.execute(RELEASE_LOCK_SCRIPT, List.of(lockKey), token);
            } catch (RuntimeException releaseFailure) {
                if (failure == null) throw releaseFailure;
                if (releaseFailure != failure) failure.addSuppressed(releaseFailure);
            }
        }
    }

    @SuppressWarnings("unchecked")
    // Lua 同时验证锁所有者并把 pending 业务 ID 移至 processing，处理失败可重新排队。
    private List<String> claim(String pendingKey, String processingKey, String lockKey, String token, int batchSize) {
        List<?> values = redisTemplate.execute(CLAIM_SCRIPT,
                List.of(pendingKey, processingKey, lockKey), token, String.valueOf(batchSize));
        if (values == null || values.isEmpty()) {
            return Collections.emptyList();
        }
        return values.stream().map(String::valueOf).toList();
    }

    private Object[] arguments(String token, List<String> members) {
        List<String> values = new ArrayList<>(members.size() + 1);
        values.add(token);
        values.addAll(members);
        return values.toArray();
    }

    private void requireOwnership(Long result) {
        if (result == null) throw new IllegalStateException("脏任务脚本未返回结果");
        if (result < 0) throw new IllegalStateException("脏任务锁已失效，停止当前批次");
    }

    // 长批次提交前复查 token，租约过期后停止当前批次继续确认。
    private void requireCurrentOwner(String lockKey, String token) {
        if (!token.equals(redisTemplate.opsForValue().get(lockKey))) {
            throw new IllegalStateException("脏任务锁已失效，停止当前批次");
        }
    }
}
