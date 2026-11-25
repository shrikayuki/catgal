package com.catgal.server.catalog;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.catgal.common.autoconfigure.mq.RabbitMqHelper;
import com.catgal.common.exception.BusinessException;
import com.catgal.server.mapper.FavoriteFolderMapper;
import com.catgal.server.mapper.FavoriteItemMapper;
import com.catgal.server.mapper.GameMapper;
import com.catgal.server.security.CurrentUserProvider;
import com.catgal.server.service.RedisDirtySetProcessor;
import com.catgal.server.service.IFavoriteItemService;
import com.catgal.server.service.impl.FavoriteItemServiceImpl;
import com.catgal.server.service.listener.FavoriteSyncConsumer;
import Message.FavoriteSyncMessage;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** All fixtures and trigger writes roll back. A savepoint allows checking an inner failed sync. */
@SpringJUnitConfig(FavoriteSyncDatabaseLiveTest.Config.class)
@EnabledIfEnvironmentVariable(named = "CATGAL_FAVORITE_SYNC_DB_TEST", matches = "1")
@Transactional
@SuppressWarnings({"unchecked", "rawtypes"})
class FavoriteSyncDatabaseLiveTest {
    @Autowired private IFavoriteItemService service;
    @Autowired private StringRedisTemplate redis;
    @Autowired private RedisDirtySetProcessor processor;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSourceTransactionManager transactions;
    @Autowired private FavoriteSyncConsumer messages;
    private Long folderId;
    private Long ownerId;
    private Long firstGame;
    private Long secondGame;

    @BeforeEach
    void setup() {
        reset(redis, processor);
        String prefix = "fs" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        jdbc.update("INSERT INTO user(username,password,role) VALUES (?, 'isolated-no-login', 0)", prefix);
        ownerId = jdbc.queryForObject("SELECT id FROM user WHERE username=?", Long.class, prefix);
        firstGame = insertGame(prefix + "-1");
        secondGame = insertGame(prefix + "-2");
        jdbc.update("INSERT INTO favorite_folder(user_id,name,is_public,game_count) VALUES (?, ?, 0, 1)", ownerId, prefix);
        folderId = jdbc.queryForObject("SELECT id FROM favorite_folder WHERE user_id=? AND name=?", Long.class, ownerId, prefix);
        jdbc.update("INSERT INTO favorite_item(folder_id,user_id,game_id) VALUES (?, ?, ?)", folderId, ownerId, firstGame);
        TransactionTemplate nested = new TransactionTemplate(transactions);
        nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
        doAnswer(invocation -> {
            Consumer<List<String>> callback = invocation.getArgument(3);
            nested.executeWithoutResult(status -> callback.accept(List.of(folderId.toString())));
            return 1;
        }).when(processor).processBatch(anyString(), eq(100), eq(true), any());
    }

    private Long insertGame(String name) {
        jdbc.update("INSERT INTO game(name,publisher_id,status,favorite_count) VALUES (?, ?, 1, 0)", name, ownerId);
        return jdbc.queryForObject("SELECT id FROM game WHERE name=?", Long.class, name);
    }

    private void snapshot(List<?> values) {
        when(redis.execute(any(DefaultRedisScript.class), anyList())).thenReturn(values);
    }

    private List<Long> gameIds() {
        return jdbc.queryForList("SELECT game_id FROM favorite_item WHERE folder_id=? ORDER BY game_id", Long.class, folderId);
    }

    @Test
    void lostCachePreservesExistingDatabaseRelationshipsAndFolderCount() {
        snapshot(List.of(0L));
        assertEquals(503, assertThrows(BusinessException.class,
                () -> service.fullSyncFavoriteItemsToDBTask(100)).getHttpStatus());
        assertEquals(List.of(firstGame), gameIds());
        assertEquals(1, jdbc.queryForObject("SELECT game_count FROM favorite_folder WHERE id=?", Integer.class, folderId));
    }

    @Test
    void completeSnapshotAndConfirmedEmptySynchroniseRelationshipsAndCount() {
        snapshot(List.of(1L, "2", firstGame.toString(), ownerId.toString(), secondGame.toString(), ownerId.toString()));
        service.fullSyncFavoriteItemsToDBTask(100);
        assertEquals(List.of(firstGame, secondGame), gameIds());
        assertEquals(2, jdbc.queryForObject("SELECT game_count FROM favorite_folder WHERE id=?", Integer.class, folderId));
        snapshot(List.of(1L, "0"));
        service.fullSyncFavoriteItemsToDBTask(100);
        assertTrue(gameIds().isEmpty());
        assertEquals(0, jdbc.queryForObject("SELECT game_count FROM favorite_folder WHERE id=?", Integer.class, folderId));
    }

    @Test
    void insertFailureRollsBackDeletionAndRetainsOriginalRelationships() {
        snapshot(List.of(1L, "1", secondGame.toString(), ownerId.toString()));
        FavoriteItemServiceImpl actual = org.springframework.test.util.AopTestUtils.getUltimateTargetObject(service);
        doReturn(false).when(actual).saveBatch(anyCollection());
        try {
            assertThrows(RuntimeException.class, () -> service.fullSyncFavoriteItemsToDBTask(100));
        } finally {
            doCallRealMethod().when(actual).saveBatch(anyCollection());
        }
        assertEquals(List.of(firstGame), gameIds());
        assertEquals(1, jdbc.queryForObject("SELECT game_count FROM favorite_folder WHERE id=?", Integer.class, folderId));
    }

    private FavoriteSyncMessage message(int operation, Long gameId) {
        return FavoriteSyncMessage.builder().userId(ownerId).folderId(folderId)
                .gameId(gameId).operation(operation).build();
    }

    @Test
    void invalidMessageOperationCannotDeleteAnExistingFavorite() {
        FavoriteSyncMessage invalid = message(0, firstGame);
        invalid.setOperation(null);
        assertThrows(AmqpRejectAndDontRequeueException.class, () -> messages.handleFavoriteSync(invalid));
        assertThrows(AmqpRejectAndDontRequeueException.class, () -> messages.handleFavoriteSync(message(2, firstGame)));
        assertEquals(List.of(firstGame), gameIds());
    }

    @Test
    void wrongOwnerMessageCannotChangeTheFoldersRows() {
        FavoriteSyncMessage invalid = message(0, firstGame);
        invalid.setUserId(ownerId + 1_000_000);
        assertThrows(AmqpRejectAndDontRequeueException.class, () -> messages.handleFavoriteSync(invalid));
        assertEquals(List.of(firstGame), gameIds());
    }

    @Test
    void lateMessageAfterFolderDeletionCannotRecreateRows() {
        jdbc.update("DELETE FROM favorite_item WHERE folder_id=?", folderId);
        jdbc.update("DELETE FROM favorite_folder WHERE id=?", folderId);
        messages.handleFavoriteSync(message(1, firstGame));
        assertTrue(gameIds().isEmpty());
        assertDoesNotThrow(() -> messages.handleFavoriteSync(message(0, firstGame)));
    }

    @Test
    void lateMessageForDeletedGameCannotCreateAnOrphan() {
        jdbc.update("DELETE FROM game WHERE id=?", secondGame);
        snapshot(List.of(1L, "1", firstGame.toString(), ownerId.toString()));
        messages.handleFavoriteSync(message(1, secondGame));
        assertEquals(List.of(firstGame), gameIds());
    }

    @Test
    void validDuplicateAndRemovalMessagesRetainExistingBehavior() {
        snapshot(List.of(1L, "2", firstGame.toString(), ownerId.toString(), secondGame.toString(), ownerId.toString()));
        messages.handleFavoriteSync(message(1, secondGame));
        messages.handleFavoriteSync(message(1, secondGame));
        assertEquals(List.of(firstGame, secondGame), gameIds());
        snapshot(List.of(1L, "1", secondGame.toString(), ownerId.toString()));
        messages.handleFavoriteSync(message(0, firstGame));
        messages.handleFavoriteSync(message(0, firstGame));
        assertEquals(List.of(secondGame), gameIds());
    }

    @Test
    void lostRedisOwnerRollsBackDatabaseChangesBeforeCommit() {
        ValueOperations<String, String> values = mock(ValueOperations.class);
        var owner = new java.util.concurrent.atomic.AtomicReference<String>();
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(), anyString(), any(java.time.Duration.class))).thenAnswer(invocation -> {
            owner.set(invocation.getArgument(1));
            return true;
        });
        when(values.get(anyString())).thenAnswer(invocation -> owner.get());
        String claimSha = com.catgal.server.redis.RedisScriptLoader.load("lua/dirty-set/claim.lua", List.class).getSha1();
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenAnswer(invocation -> {
            RedisScript<?> script = invocation.getArgument(0);
            if (claimSha.equals(script.getSha1())) return List.of(folderId.toString());
            return 0L;
        });
        TransactionTemplate nested = new TransactionTemplate(transactions);
        nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
        RedisDirtySetProcessor actual = new RedisDirtySetProcessor(redis, nested);
        assertThrows(IllegalStateException.class, () -> actual.processBatch("isolated:dirty:" + folderId, 1, true, members -> {
            jdbc.update("UPDATE favorite_folder SET game_count=99 WHERE id=?", folderId);
            owner.set("successor");
        }));
        assertEquals(1, jdbc.queryForObject("SELECT game_count FROM favorite_folder WHERE id=?", Integer.class, folderId));
        assertEquals(List.of(firstGame), gameIds());
    }

    @Configuration
    @EnableTransactionManagement
    @MapperScan(basePackageClasses = GameMapper.class)
    static class Config {
        @Bean DataSource dataSource() {
            return new DriverManagerDataSource(System.getenv("MYSQL_URL"), System.getenv("MYSQL_USERNAME"),
                    System.getenv("MYSQL_PASSWORD"));
        }
        @Bean DataSourceTransactionManager transactionManager(DataSource source) { return new DataSourceTransactionManager(source); }
        @Bean JdbcTemplate jdbcTemplate(DataSource source) { return new JdbcTemplate(source); }
        @Bean SqlSessionFactory sqlSessionFactory(DataSource source) throws Exception {
            MybatisConfiguration configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);
            configuration.setLogImpl(org.apache.ibatis.logging.nologging.NoLoggingImpl.class);
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(source);
            factory.setConfiguration(configuration);
            factory.setMapperLocations(new ClassPathResource("mapper/FavoriteItemMapper.xml"));
            return factory.getObject();
        }
        @Bean StringRedisTemplate redis() { return mock(StringRedisTemplate.class); }
        @Bean RedisDirtySetProcessor processor() { return mock(RedisDirtySetProcessor.class); }
        @Bean FavoriteSyncConsumer messages(IFavoriteItemService service) {
            return new FavoriteSyncConsumer(service);
        }
        @Bean IFavoriteItemService favorites(StringRedisTemplate redis, FavoriteFolderMapper folders,
                                               FavoriteItemMapper items, GameMapper games, RedisDirtySetProcessor processor, DataSourceTransactionManager transactions) {
            return spy(new FavoriteItemServiceImpl(redis, folders, mock(RabbitMqHelper.class), items, games,
                    new CurrentUserProvider(), processor, transactions));
        }
    }
}
