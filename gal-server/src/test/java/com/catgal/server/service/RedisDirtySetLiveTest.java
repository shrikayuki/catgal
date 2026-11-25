package com.catgal.server.service;

import com.catgal.common.autoconfigure.mq.RabbitMqHelper;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import static com.catgal.server.redis.RedisScriptLoader.load;
import static com.catgal.common.constants.Constant.REQUEST_ID_HEADER;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

@EnabledIfEnvironmentVariable(named = "CATGAL_DIRTY_SET_REDIS_TEST", matches = "1")
class RedisDirtySetLiveTest {
    private LettuceConnectionFactory factory;
    private StringRedisTemplate redis;
    private RedisDirtySetProcessor processor;
    private String pending;
    private String processing;
    private String lock;

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
        processor = new RedisDirtySetProcessor(redis, mock(TransactionTemplate.class));
        pending = "catgal:test:dirty:" + UUID.randomUUID();
        processing = pending + ":processing";
        lock = "lock:dirty-set:" + pending;
        assertEquals(0L, redis.countExistingKeys(List.of(pending, processing, lock)));
    }

    @AfterEach
    void cleanup() {
        try {
            if (redis != null && pending != null) redis.delete(List.of(pending, processing, lock));
        } finally {
            if (factory != null) factory.destroy();
        }
    }

    @Test
    void recoversCrashedProcessingAndConsumesOnlyTheRequestedBatch() {
        redis.opsForSet().add(pending, "1", "2", "3");
        redis.opsForSet().add(processing, "crashed");
        assertEquals(2, processor.processBatch(pending, 2, false, members -> assertEquals(2, members.size())));
        assertEquals(2L, redis.opsForSet().size(pending));
        assertEquals(0L, redis.opsForSet().size(processing));
        assertFalse(Boolean.TRUE.equals(redis.hasKey(lock)));
    }

    @Test
    void requeueAfterFailureRetainsAllClaimedWork() {
        redis.opsForSet().add(pending, "1", "2");
        RuntimeException original = new IllegalStateException("isolated processing failure");
        assertSame(original, assertThrows(RuntimeException.class,
                () -> processor.processBatch(pending, 10, false, members -> { throw original; })));
        assertEquals(Set.of("1", "2"), redis.opsForSet().members(pending));
        assertEquals(0L, redis.opsForSet().size(processing));
        assertFalse(Boolean.TRUE.equals(redis.hasKey(lock)));
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "CATGAL_LIKE_MQ_TEST", matches = "1")
    void unroutablePublishKeepsTheBatchAndSuccessfulRetryAcknowledgesIt() {
        var rabbit = new CachingConnectionFactory(System.getenv("RABBITMQ_HOST"),
                Integer.parseInt(System.getenv().getOrDefault("RABBITMQ_PORT", "5672")));
        rabbit.setUsername(System.getenv("RABBITMQ_USERNAME"));
        rabbit.setPassword(System.getenv("RABBITMQ_PASSWORD"));
        rabbit.setVirtualHost(System.getenv().getOrDefault("RABBITMQ_VHOST", "/"));
        rabbit.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        rabbit.setPublisherReturns(true);
        rabbit.setConnectionTimeout(5000);
        var template = new RabbitTemplate(rabbit);
        var publisher = new RabbitMqHelper(template);
        var admin = new RabbitAdmin(rabbit);
        String queueName = "catgal.test.like-confirm." + UUID.randomUUID();
        try {
            redis.opsForSet().add(pending, "20");
            var failure = assertThrows(AmqpException.class, () -> processor.processBatch(pending, 40, false,
                    ids -> publisher.sendConfirmed("", queueName, ids.get(0))));
            assertEquals("消息未路由到队列", failure.getMessage());
            assertEquals(Set.of("20"), redis.opsForSet().members(pending));
            assertEquals(0L, redis.opsForSet().size(processing));

            admin.declareQueue(new Queue(queueName, false, true, true));
            assertEquals(1, processor.processBatch(pending, 40, false,
                    ids -> publisher.sendConfirmed("", queueName, ids.get(0))));
            assertEquals(0L, redis.opsForSet().size(pending));
            assertEquals(0L, redis.opsForSet().size(processing));
            var delivered = template.receive(queueName, 5000);
            assertNotNull(delivered);
            assertEquals("20", template.getMessageConverter().fromMessage(delivered));
            assertNotNull(delivered.getMessageProperties().getHeader(REQUEST_ID_HEADER));
            assertEquals(MessageDeliveryMode.PERSISTENT, delivered.getMessageProperties().getReceivedDeliveryMode());
        } finally {
            // 排他队列随测试连接关闭自动删除，不与正式消费者竞争。
            rabbit.destroy();
        }
    }

    @Test
    void lostOwnerCannotAcknowledgeOrRequeueASuccessorsProcessing() {
        redis.opsForSet().add(pending, "1");
        assertThrows(IllegalStateException.class, () -> processor.processBatch(pending, 10, false, members -> {
            redis.opsForValue().set(lock, "successor");
            redis.opsForSet().add(processing, "new-work");
            redis.opsForSet().add(pending, "new-pending");
        }));
        assertEquals("successor", redis.opsForValue().get(lock));
        assertEquals(Set.of("1", "new-work"), redis.opsForSet().members(processing));
        assertEquals(Set.of("new-pending"), redis.opsForSet().members(pending));
    }

    @Test
    void expiredOwnerLeavesWorkForTheNextSuccessfulRecovery() {
        redis.opsForSet().add(pending, "1");
        assertThrows(IllegalStateException.class, () -> processor.processBatch(pending, 10, false,
                members -> redis.delete(lock)));
        assertEquals(Set.of("1"), redis.opsForSet().members(processing));
        assertEquals(1, processor.processBatch(pending, 10, false,
                members -> assertEquals(List.of("1"), members)));
        assertEquals(0L, redis.opsForSet().size(processing));
        assertEquals(0L, redis.opsForSet().size(pending));
    }

    @Test
    void pendingChangesAddedDuringProcessingSurviveAcknowledgement() {
        redis.opsForSet().add(pending, "1");
        assertEquals(1, processor.processBatch(pending, 10, false, members -> {
            assertEquals(0, processor.processBatch(pending, 10, false, ignored -> fail("Lock is busy")));
            redis.opsForSet().add(pending, "1");
        }));
        assertEquals(Set.of("1"), redis.opsForSet().members(pending));
        assertEquals(0L, redis.opsForSet().size(processing));
    }

    @Test
    void allQueueScriptsRejectAnOldTokenBeforeMutatingSets() {
        redis.opsForValue().set(lock, "current");
        redis.opsForSet().add(pending, "pending-work");
        redis.opsForSet().add(processing, "processing-work");
        List<String> keys = List.of(pending, processing, lock);
        assertThrows(RuntimeException.class, () -> redis.execute(load("lua/dirty-set/claim.lua", List.class),
                keys, "stale", "10"));
        assertEquals(-1L, redis.execute(load("lua/dirty-set/requeue-all.lua", Long.class), keys, "stale"));
        assertEquals(-1L, redis.execute(load("lua/dirty-set/requeue.lua", Long.class), keys, "stale", "processing-work"));
        assertEquals(-1L, redis.execute(load("lua/dirty-set/ack.lua", Long.class),
                List.of(processing, lock), "stale", "processing-work"));
        assertEquals(Set.of("pending-work"), redis.opsForSet().members(pending));
        assertEquals(Set.of("processing-work"), redis.opsForSet().members(processing));
    }

    @Test
    void wrongProcessingTypeCannotLoseAPoppedPendingMember() {
        redis.opsForValue().set(lock, "current");
        redis.opsForSet().add(pending, "1");
        redis.opsForValue().set(processing, "wrong-type");
        assertThrows(RuntimeException.class, () -> redis.execute(load("lua/dirty-set/claim.lua", List.class),
                List.of(pending, processing, lock), "current", "10"));
        assertEquals(Set.of("1"), redis.opsForSet().members(pending));
        assertEquals("wrong-type", redis.opsForValue().get(processing));
    }

    @Test
    void transactionManagerReceivesRollbackAfterOwnerChangesDuringWork() {
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        TransactionStatus status = mock(TransactionStatus.class);
        when(manager.getTransaction(any())).thenReturn(status);
        RedisDirtySetProcessor transactional = new RedisDirtySetProcessor(redis, new TransactionTemplate(manager));
        redis.opsForSet().add(pending, "1");
        assertThrows(IllegalStateException.class, () -> transactional.processBatch(pending, 10, true,
                members -> redis.opsForValue().set(lock, "successor")));
        verify(manager).rollback(status);
        verify(manager, never()).commit(status);
        assertEquals(Set.of("1"), redis.opsForSet().members(processing));
        assertEquals("successor", redis.opsForValue().get(lock));
    }
}
