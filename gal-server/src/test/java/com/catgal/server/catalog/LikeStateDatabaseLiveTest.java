package com.catgal.server.catalog;

import com.catgal.common.autoconfigure.mq.RabbitMqHelper;
import com.catgal.common.context.UserContext;
import com.catgal.common.context.UserInfo;
import com.catgal.common.domain.dto.LikeDTO;
import com.catgal.common.exception.BusinessException;
import com.catgal.server.mapper.CommentMapper;
import com.catgal.server.mapper.GameReviewMapper;
import com.catgal.server.mapper.ResourceMapper;
import com.catgal.server.mapper.LikeRecordMapper;
import Message.LikeCountMessage;
import com.catgal.server.service.listener.LikeCountListener;
import com.catgal.server.security.CurrentUserProvider;
import com.catgal.server.service.ILikeRecordService;
import com.catgal.server.service.LikeTargetValidator;
import com.catgal.server.service.RedisDirtySetProcessor;
import com.catgal.server.service.impl.LikeRecordServiceImpl;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static com.catgal.server.redis.RedisScriptLoader.load;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringJUnitConfig({CatalogDatabaseLiveTest.Config.class, LikeStateDatabaseLiveTest.Config.class})
@EnabledIfEnvironmentVariable(named = "CATGAL_LIKE_DB_TEST", matches = "1")
@SuppressWarnings({"unchecked", "rawtypes"})
class LikeStateDatabaseLiveTest {
    @Autowired ILikeRecordService service;
    @Autowired StringRedisTemplate redis;
    @Autowired RabbitMqHelper mq;
    @Autowired RedisDirtySetProcessor processor;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired LikeRecordMapper mapper;
    private long owner;
    private long game;
    private long comment;
    private String name;
    private final AtomicReference<List<?>> snapshot = new AtomicReference<>();
    private final AtomicReference<Long> hydration = new AtomicReference<>();

    @BeforeEach void setup() {
        reset(redis, mq, processor);
        name = "lp" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        jdbc.update("INSERT INTO user(username,password,role) VALUES (?, 'isolated-no-login', 0)", name);
        owner = jdbc.queryForObject("SELECT id FROM user WHERE username=?", Long.class, name);
        jdbc.update("INSERT INTO game(name,publisher_id,status) VALUES (?,?,0)", name, owner);
        game = jdbc.queryForObject("SELECT id FROM game WHERE name=?", Long.class, name);
        jdbc.update("INSERT INTO comment(user_id,game_id,content,status) VALUES (?,?,?,1)", owner, game, name);
        comment = jdbc.queryForObject("SELECT id FROM comment WHERE game_id=?", Long.class, game);
        jdbc.update("INSERT INTO like_record(user_id,type,target_id) VALUES (?, 'comment', ?)", owner, comment);
        UserContext.setUser(UserInfo.builder().userId(owner).role(0).build());
        snapshot.set(List.of(0L));
        hydration.set(1L);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenAnswer(call -> {
            RedisScript<?> script = call.getArgument(0);
            if (script.getSha1().equals(load("lua/like/snapshot.lua", List.class).getSha1())) return snapshot.get();
            if (script.getSha1().equals(load("lua/like/inspect.lua", List.class).getSha1())) return List.of(-1L, 0L);
            if (script.getSha1().equals(load("lua/like/hydrate.lua", Long.class).getSha1())) return hydration.get();
            return 1L;
        });
        when(redis.executePipelined(any(org.springframework.data.redis.core.RedisCallback.class))).thenReturn(List.of());
        when(processor.processBatch(anyString(), anyInt(), anyBoolean(), any())).thenAnswer(call -> {
            Consumer<List<String>> callback = call.getArgument(3);
            if (call.getArgument(2, Boolean.class)) {
                new TransactionTemplate(manager).executeWithoutResult(status -> callback.accept(List.of(Long.toString(comment))));
            } else callback.accept(List.of(Long.toString(comment)));
            return 1;
        });
    }

    @AfterEach void cleanup() {
        UserContext.clear();
        jdbc.update("DELETE FROM like_record WHERE type='comment' AND target_id=?", comment);
        jdbc.update("DELETE FROM comment WHERE id=? AND content=?", comment, name);
        jdbc.update("DELETE FROM game WHERE id=? AND name=?", game, name);
        jdbc.update("DELETE FROM user WHERE id=? AND username=?", owner, name);
    }

    private LikeDTO request() { LikeDTO dto = new LikeDTO(); dto.setType("comment"); dto.setTargetId(comment); return dto; }
    private long rows() { return jdbc.queryForObject("SELECT COUNT(*) FROM like_record WHERE type='comment' AND target_id=?", Long.class, comment); }

    @Test void lostSetCannotDeletePersistedRelationships() {
        assertEquals(503, assertThrows(BusinessException.class,
                () -> service.fullSyncLikeRecordsToDB("comment", 100, 100)).getHttpStatus());
        assertEquals(1L, rows());
    }

    @Test void verifiedEmptyCanClearRelationships() {
        snapshot.set(List.of(1L));
        service.fullSyncLikeRecordsToDB("comment", 100, 100);
        assertEquals(0L, rows());
    }

    @Test void verifiedOwnersAreWrittenUsingTheExistingEntityService() {
        snapshot.set(List.of(1L, Long.toString(owner)));
        service.fullSyncLikeRecordsToDB("comment", 100, 100);
        assertEquals(1L, rows());
        assertEquals(owner, jdbc.queryForObject("SELECT user_id FROM like_record WHERE type='comment' AND target_id=?", Long.class, comment));
    }

    @Test void invalidOwnerFailsBeforeAnyDatabaseReplacement() {
        snapshot.set(List.of(1L, "0"));
        assertThrows(IllegalStateException.class, () -> service.fullSyncLikeRecordsToDB("comment", 100, 100));
        assertEquals(1L, rows());
    }

    @Test void readsFallBackToDatabaseCountsAndCurrentMembership() {
        assertEquals(1L, service.getLikeCounts(List.of(comment, comment), "comment").get(comment));
        assertTrue(service.batchIsLiked(owner, "comment", List.of(comment)).get(comment));
        assertFalse(service.batchIsLiked(owner + 1000000, "comment", List.of(comment)).get(comment));
        when(redis.executePipelined(any(org.springframework.data.redis.core.RedisCallback.class)))
                .thenThrow(new IllegalStateException("isolated Redis outage"));
        assertEquals(1L, service.getLikeCounts(List.of(comment), "comment").get(comment));
    }

    @Test void cleanHydrationAndDirtyCacheRejectionPreservePersistedRows() {
        assertTrue(service.like(request()));
        assertEquals(1L, rows());
        hydration.set(0L);
        assertEquals(503, assertThrows(BusinessException.class, () -> service.unlike(request())).getHttpStatus());
        assertEquals(1L, rows());
    }

    @Test void lostLiveCountsCannotPublishAZeroMessage() {
        assertEquals(503, assertThrows(BusinessException.class,
                () -> service.readLikedTimesAndSendMessage("comment", 100)).getHttpStatus());
        verifyNoInteractions(mq);
    }

    @Test void deletionRollbackPreservesRelationshipsAndDoesNotClearRedis() {
        assertThrows(IllegalStateException.class, () -> new TransactionTemplate(manager).executeWithoutResult(status -> {
            jdbc.update("DELETE FROM comment WHERE id=?", comment);
            service.clearLikeCache("comment", comment);
            assertEquals(0L, rows());
            throw new IllegalStateException("isolated rollback");
        }));
        assertEquals(1L, rows());
        verifyNoInteractions(redis);
    }

    @Test void committedDeletionAndLateRecordSyncCannotRecreateRows() {
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            jdbc.update("DELETE FROM comment WHERE id=?", comment);
            service.clearLikeCache("comment", comment);
        });
        assertEquals(0L, rows());
        snapshot.set(List.of(1L, Long.toString(owner)));
        service.fullSyncLikeRecordsToDB("comment", 100, 100);
        assertEquals(0L, rows());
        assertFalse(service.unlike(request()));
    }

    @Test void concurrentMutationsAreSerializedByTheTargetRowLock() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        snapshot.set(List.of(1L));
        doAnswer(call -> { entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)); return List.of(0L, 0L); })
                .when(redis).execute(argThat(script -> script != null && script.getSha1().equals(
                        load("lua/like/inspect.lua", List.class).getSha1())), anyList(), eq(""));
        var executor = Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<Boolean> operation = () -> {
                UserContext.setUser(UserInfo.builder().userId(owner).build());
                try { return service.like(request()); } finally { UserContext.clear(); }
            };
            var first = executor.submit(operation);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var second = executor.submit(operation);
            assertThrows(TimeoutException.class, () -> second.get(150, TimeUnit.MILLISECONDS));
            release.countDown();
            assertTrue(first.get(5, TimeUnit.SECONDS));
            assertTrue(second.get(5, TimeUnit.SECONDS));
        } finally { release.countDown(); executor.shutdownNow(); }
    }

    @Test void aPostCommitCacheFailureQueuesCleanupWithoutMisreportingTheCommittedDeletion() {
        var sets = mock(org.springframework.data.redis.core.SetOperations.class);
        when(redis.opsForSet()).thenReturn(sets);
        doThrow(new IllegalStateException("isolated cache cleanup failure"))
                .when(redis).execute(argThat(script -> script != null && script.getSha1().equals(
                        load("lua/like/clear.lua", Long.class).getSha1())), anyList(), eq(Long.toString(comment)));
        assertDoesNotThrow(() -> new TransactionTemplate(manager).executeWithoutResult(status -> {
            jdbc.update("DELETE FROM comment WHERE id=?", comment);
            service.clearLikeCache("comment", comment);
        }));
        assertEquals(0L, rows());
        verify(sets).add("like:record:change:comment", Long.toString(comment));
    }

    @Test void delayedAndRepeatedCountMessagesReconcileTheCurrentValue() {
        when(redis.executePipelined(any(org.springframework.data.redis.core.RedisCallback.class)))
                .thenReturn(List.of(List.of(2L, 0L)));
        var listener = new LikeCountListener(service);
        var oldMessage = LikeCountMessage.builder().bizType("comment").bizId(comment).likeCount(99L).build();
        listener.listenCommentLikeTimesChange(List.of(oldMessage));
        assertEquals(2, jdbc.queryForObject("SELECT like_count FROM comment WHERE id=?", Integer.class, comment));
        jdbc.update("UPDATE comment SET update_time='2021-01-01 00:00:00' WHERE id=?", comment);
        listener.listenCommentLikeTimesChange(List.of(oldMessage));
        assertEquals(2, jdbc.queryForObject("SELECT like_count FROM comment WHERE id=?", Integer.class, comment));
        assertEquals(2021, jdbc.queryForObject("SELECT YEAR(update_time) FROM comment WHERE id=?", Integer.class, comment));
    }

    @Test void confirmedEmptyCountsCanResetAnExistingNonzeroValue() {
        jdbc.update("UPDATE comment SET like_count=8 WHERE id=?", comment);
        when(redis.executePipelined(any(org.springframework.data.redis.core.RedisCallback.class)))
                .thenReturn(List.of(List.of(0L, 0L)));
        service.syncLikeCounts("comment", List.of(comment));
        assertEquals(0, jdbc.queryForObject("SELECT like_count FROM comment WHERE id=?", Integer.class, comment));
    }

    @Test void missingStatePreservesCountsForVisibleAndHiddenContent() {
        for (int visibility : List.of(0, 1)) {
            jdbc.update("UPDATE comment SET status=?,like_count=8 WHERE id=?", visibility, comment);
            assertEquals(503, assertThrows(BusinessException.class,
                    () -> service.syncLikeCounts("comment", List.of(comment))).getHttpStatus());
            assertEquals(8, jdbc.queryForObject("SELECT like_count FROM comment WHERE id=?", Integer.class, comment));
        }
    }

    @Test void aDeletedTargetCanAcknowledgeAnOldCountNotification() {
        jdbc.update("DELETE FROM comment WHERE id=?", comment);
        assertDoesNotThrow(() -> service.syncLikeCounts("comment", List.of(comment)));
        verifyNoInteractions(redis);
    }

    @Test void invalidStateInABatchPreservesEveryExistingCounter() {
        jdbc.update("INSERT INTO comment(game_id,user_id,content,status,like_count) VALUES (?,?,?,1,9)", game, owner, name + "extra");
        long second = jdbc.queryForObject("SELECT id FROM comment WHERE game_id=? AND content=?", Long.class, game, name + "extra");
        try {
            jdbc.update("UPDATE comment SET like_count=7 WHERE id=?", comment);
            when(redis.executePipelined(any(org.springframework.data.redis.core.RedisCallback.class)))
                    .thenReturn(List.of(List.of(2L, 0L), List.of(-1L, 0L)));
            assertEquals(503, assertThrows(BusinessException.class,
                    () -> service.syncLikeCounts("comment", List.of(second, comment))).getHttpStatus());
            assertEquals(7, jdbc.queryForObject("SELECT like_count FROM comment WHERE id=?", Integer.class, comment));
            assertEquals(9, jdbc.queryForObject("SELECT like_count FROM comment WHERE id=?", Integer.class, second));
        } finally { jdbc.update("DELETE FROM comment WHERE id=? AND game_id=?", second, game); }
    }

    @Test void aDatabaseWriteFailureRollsBackTheWholeBatchAndReachesTheListener() throws Exception {
        jdbc.update("INSERT INTO comment(game_id,user_id,content,status,like_count) VALUES (?,?,?,1,9)", game, owner, name + "extra");
        long second = jdbc.queryForObject("SELECT id FROM comment WHERE game_id=? AND content=?", Long.class, game, name + "extra");
        Object target = org.springframework.test.util.AopTestUtils.getTargetObject(service);
        LikeRecordMapper failingMapper = spy(mapper);
        org.springframework.test.util.ReflectionTestUtils.setField(target, "baseMapper", failingMapper);
        try {
            jdbc.update("UPDATE comment SET like_count=7 WHERE id=?", comment);
            when(redis.executePipelined(any(org.springframework.data.redis.core.RedisCallback.class)))
                    .thenReturn(List.of(List.of(2L, 0L), List.of(3L, 0L)));
            doReturn(0).when(failingMapper).updateTargetLikeCount(eq("comment"), eq(second), eq(3), any());
            assertEquals(500, assertThrows(BusinessException.class, () -> new LikeCountListener(service)
                    .listenCommentLikeTimesChange(List.of(
                            LikeCountMessage.builder().bizType("comment").bizId(comment).likeCount(2L).build(),
                            LikeCountMessage.builder().bizType("comment").bizId(second).likeCount(3L).build()))).getHttpStatus());
            assertEquals(7, jdbc.queryForObject("SELECT like_count FROM comment WHERE id=?", Integer.class, comment));
            assertEquals(9, jdbc.queryForObject("SELECT like_count FROM comment WHERE id=?", Integer.class, second));
        } finally {
            org.springframework.test.util.ReflectionTestUtils.setField(target, "baseMapper", mapper);
            jdbc.update("DELETE FROM comment WHERE id=? AND game_id=?", second, game);
        }
    }

    @Test void currentCountReconciliationSupportsReviewsAndResources() {
        jdbc.update("INSERT INTO game_review(game_id,user_id,brief_review,score,play_status,recommend_level,status,like_count) VALUES (?,?,?,5,1,1,0,9)", game, owner, name);
        long review = jdbc.queryForObject("SELECT id FROM game_review WHERE game_id=? AND user_id=?", Long.class, game, owner);
        jdbc.update("INSERT INTO resource(game_id,user_id,name,download_url,types,languages,platforms,status,like_count) VALUES (?,?,?,'https://assets.example/count-test','pc','zh','win',2,9)", game, owner, name);
        long resource = jdbc.queryForObject("SELECT id FROM resource WHERE game_id=? AND name=?", Long.class, game, name);
        try {
            when(redis.executePipelined(any(org.springframework.data.redis.core.RedisCallback.class)))
                    .thenReturn(List.of(List.of(3L, 0L)));
            service.syncLikeCounts("review", List.of(review));
            service.syncLikeCounts("resource", List.of(resource));
            assertEquals(3, jdbc.queryForObject("SELECT like_count FROM game_review WHERE id=?", Integer.class, review));
            assertEquals(3, jdbc.queryForObject("SELECT like_count FROM resource WHERE id=?", Integer.class, resource));
        } finally {
            jdbc.update("DELETE FROM resource WHERE id=? AND game_id=?", resource, game);
            jdbc.update("DELETE FROM game_review WHERE id=? AND game_id=?", review, game);
        }
    }

    @Test void countReconciliationSerializesWithConcurrentUserMutations() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(redis.executePipelined(any(org.springframework.data.redis.core.RedisCallback.class))).thenAnswer(call -> {
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return List.of(List.of(2L, 0L));
        });
        var executor = Executors.newFixedThreadPool(2);
        try {
            var count = executor.submit(() -> service.syncLikeCounts("comment", List.of(comment)));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var user = executor.submit(() -> {
                UserContext.setUser(UserInfo.builder().userId(owner).build());
                try { return service.unlike(request()); } finally { UserContext.clear(); }
            });
            assertThrows(TimeoutException.class, () -> user.get(150, TimeUnit.MILLISECONDS));
            release.countDown();
            count.get(5, TimeUnit.SECONDS);
            assertTrue(user.get(5, TimeUnit.SECONDS));
        } finally { release.countDown(); executor.shutdownNow(); }
    }

    @Configuration static class Config {
        @Bean StringRedisTemplate redis() { return mock(StringRedisTemplate.class); }
        @Bean RabbitMqHelper mq() { return mock(RabbitMqHelper.class); }
        @Bean RedisDirtySetProcessor processor() { return mock(RedisDirtySetProcessor.class); }
        @Bean ILikeRecordService likes(StringRedisTemplate redis, RabbitMqHelper mq, RedisDirtySetProcessor processor,
                                      CommentMapper comments, GameReviewMapper reviews, ResourceMapper resources) {
            return new LikeRecordServiceImpl(redis, mq, processor,
                    new LikeTargetValidator(comments, reviews, resources), new CurrentUserProvider());
        }
    }
}
