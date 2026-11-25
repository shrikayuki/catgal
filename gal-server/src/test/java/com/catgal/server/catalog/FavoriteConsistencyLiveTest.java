package com.catgal.server.catalog;

import Message.FavoriteSyncMessage;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.catgal.common.autoconfigure.mq.RabbitMqHelper;
import com.catgal.common.context.UserContext;
import com.catgal.common.context.UserInfo;
import com.catgal.common.domain.dto.FavoriteDTO;
import com.catgal.common.exception.BusinessException;
import com.catgal.server.mapper.*;
import com.catgal.server.security.CurrentUserProvider;
import com.catgal.server.service.*;
import com.catgal.server.service.impl.FavoriteItemServiceImpl;
import com.catgal.server.service.listener.FavoriteSyncConsumer;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.*;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import static com.catgal.common.constants.RedisConstant.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 真实 MySQL + Redis，所有写入限于随机测试账号、游戏、收藏夹，精确清理拥有的 ID。 */
@SpringJUnitConfig(FavoriteConsistencyLiveTest.Config.class)
@EnabledIfEnvironmentVariable(named = "CATGAL_FAVORITE_SYNC_DB_TEST", matches = "1")
@EnabledIfEnvironmentVariable(named = "CATGAL_FAVORITE_ATOMIC_REDIS_TEST", matches = "1")
@SuppressWarnings({"unchecked", "rawtypes"})
class FavoriteConsistencyLiveTest {
    @Autowired IFavoriteItemService service;
    @Autowired StringRedisTemplate redis;
    @Autowired RedisDirtySetProcessor processor;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transaction;
    private Long user;
    private Long game;
    private Long folder;
    private String hash;
    private String marker;
    private String counter;

    @BeforeEach void setup() {
        String name = "fc" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        jdbc.update("INSERT INTO user(username,password,role) VALUES (?, 'isolated-no-login', 0)", name);
        user = jdbc.queryForObject("SELECT id FROM user WHERE username=?", Long.class, name);
        jdbc.update("INSERT INTO game(name,publisher_id,status,favorite_count) VALUES (?, ?, 1, 0)", name, user);
        game = jdbc.queryForObject("SELECT id FROM game WHERE publisher_id=? AND name=?", Long.class, user, name);
        jdbc.update("INSERT INTO favorite_folder(user_id,name,is_public,game_count) VALUES (?, ?, 0, 0)", user, name);
        folder = jdbc.queryForObject("SELECT id FROM favorite_folder WHERE user_id=? AND name=?", Long.class, user, name);
        hash = com.catgal.common.utils.StringUtils.format(USER_FAVORITE_KEY, folder);
        marker = com.catgal.common.utils.StringUtils.format(USER_FAVORITE_EXPECTED_KEY, folder);
        counter = com.catgal.common.utils.StringUtils.format(FAVORITE_COUNT_KEY, game);
        redis.opsForValue().set(marker, "0");
        redis.opsForValue().set(counter, "0");
        UserContext.setUser(UserInfo.builder().userId(user).build());
        doAnswer(call -> {
            Consumer<List<String>> callback = call.getArgument(3);
            transaction.executeWithoutResult(status -> callback.accept(List.of(folder.toString())));
            return 1;
        }).when(processor).processBatch(eq(FAVORITE_CHANGE_SET_KEY), eq(100), eq(true), any());
    }

    @AfterEach void cleanup() {
        UserContext.clear();
        doCallRealMethod().when(redis).execute(any(RedisScript.class), anyList(), any(Object[].class));
        if (folder != null) {
            transaction.executeWithoutResult(status -> {
                jdbc.update("DELETE FROM favorite_item WHERE folder_id=?", folder);
                jdbc.update("DELETE FROM favorite_folder WHERE id=? AND user_id=?", folder, user);
                jdbc.update("DELETE FROM game WHERE id=? AND publisher_id=?", game, user);
                jdbc.update("DELETE FROM user WHERE id=? AND password='isolated-no-login'", user);
            });
            redis.delete(List.of(hash, marker, counter));
            redis.opsForSet().remove(FAVORITE_CHANGE_SET_KEY, folder.toString());
            redis.opsForSet().remove(FAVORITE_CHANGE_SET_KEY + ":processing", folder.toString());
            redis.opsForSet().remove(GAME_STATS_COUNT_CHANGE_SET_KEY, game.toString());
            assertEquals(0, rows());
        }
    }

    private FavoriteDTO request() {
        FavoriteDTO dto = new FavoriteDTO(); dto.setFolderId(folder); dto.setGameId(game); return dto;
    }
    private int rows() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM favorite_item WHERE folder_id=?", Integer.class, folder);
    }
    private void sync() { service.syncFavorite(folder, user, game); }
    private void clearPending() {
        redis.opsForSet().remove(FAVORITE_CHANGE_SET_KEY, folder.toString());
        redis.opsForSet().remove(FAVORITE_CHANGE_SET_KEY + ":processing", folder.toString());
    }
    private void deleteSqlParents() {
        transaction.executeWithoutResult(status -> {
            jdbc.update("DELETE FROM favorite_item WHERE folder_id=?", folder);
            jdbc.update("DELETE FROM favorite_folder WHERE id=?", folder);
        });
    }

    @Test void reorderedNotificationsAlwaysConvergeToTheCurrentState() {
        assertTrue(service.favorite(request())); sync(); assertEquals(1, rows());
        assertTrue(service.unfavorite(request())); sync(); assertEquals(0, rows());
        FavoriteSyncConsumer consumer = new FavoriteSyncConsumer(service);
        consumer.handleFavoriteSync(FavoriteSyncMessage.builder().userId(user).folderId(folder).gameId(game).operation(1).build());
        assertEquals(0, rows());
        assertTrue(service.favorite(request()));
        consumer.handleFavoriteSync(FavoriteSyncMessage.builder().userId(user).folderId(folder).gameId(game).operation(0).build());
        assertEquals(1, rows());
        assertEquals("1", redis.opsForValue().get(counter));
    }

    @Test void cleanMissingCacheHydratesDatabaseAndAllowsCancellation() {
        assertTrue(service.favorite(request())); sync(); clearPending();
        redis.delete(List.of(hash, marker));
        assertTrue(service.unfavorite(request())); sync();
        assertEquals(0, rows()); assertEquals("0", redis.opsForValue().get(marker));
        assertEquals("0", redis.opsForValue().get(counter));
    }

    @Test void damagedPendingCachePreservesSqlAndTheDirtyTask() {
        assertTrue(service.favorite(request())); sync();
        redis.delete(hash);
        assertEquals(503, assertThrows(BusinessException.class, () -> service.unfavorite(request())).getHttpStatus());
        assertEquals(503, assertThrows(BusinessException.class, this::sync).getHttpStatus());
        assertEquals(1, rows()); assertEquals("1", redis.opsForValue().get(counter));
        assertTrue(Boolean.TRUE.equals(redis.opsForSet().isMember(FAVORITE_CHANGE_SET_KEY, folder.toString())));
        // 恢复仅属于本测试的 Hash，避免运行中的应用定时任务领取受损夹后干扰其他验证。
        redis.opsForHash().put(hash, game.toString(), user.toString());
    }

    @Test void simultaneousDuplicateAddsAndRemovesOnlyChangeTheCounterOnce() throws Exception {
        var executor = Executors.newFixedThreadPool(8);
        try {
            for (boolean add : List.of(true, false)) {
                List<Callable<Boolean>> calls = new ArrayList<>();
                for (int index = 0; index < 24; index++) calls.add(() -> {
                    UserContext.setUser(UserInfo.builder().userId(user).build());
                    try { return add ? service.favorite(request()) : service.unfavorite(request()); }
                    finally { UserContext.clear(); }
                });
                long mutations = 0;
                for (var result : executor.invokeAll(calls)) if (result.get(15, TimeUnit.SECONDS)) mutations++;
                assertEquals(1, mutations);
                sync(); assertEquals(add ? 1 : 0, rows());
                assertEquals(add ? "1" : "0", redis.opsForValue().get(counter));
            }
        } finally { executor.shutdownNow(); }
    }

    @Test void deletionRollbackRetainsRelationsAndCounterAndRestoresTheJournal() {
        assertTrue(service.favorite(request())); sync();
        transaction.executeWithoutResult(status -> { service.deleteFolder(folder); status.setRollbackOnly(); });
        assertEquals(1, rows()); assertEquals("1", redis.opsForValue().get(counter));
        assertEquals("1", redis.opsForValue().get(marker));
        assertEquals(user.toString(), redis.opsForHash().get(hash, game.toString()));
    }

    @Test void deletionAndConcurrentAddCannotLeaveAnOrphanOrDoubleDeduction() throws Exception {
        assertTrue(service.favorite(request())); sync();
        var executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Callable<Void>> calls = List.of(() -> {
                UserContext.setUser(UserInfo.builder().userId(user).build()); ready.countDown(); start.await();
                try { service.deleteFolder(folder); return null; } finally { UserContext.clear(); }
            }, () -> {
                UserContext.setUser(UserInfo.builder().userId(user).build()); ready.countDown(); start.await();
                try { service.favorite(request()); }
                catch (BusinessException failure) { assertEquals(404, failure.getHttpStatus()); }
                finally { UserContext.clear(); }
                return null;
            });
            List<Future<Void>> futures = new ArrayList<>();
            for (var call : calls) futures.add(executor.submit(call));
            assertTrue(ready.await(5, TimeUnit.SECONDS)); start.countDown();
            for (var result : futures) result.get(20, TimeUnit.SECONDS);
            sync();
            assertEquals(0, rows()); assertEquals("0", redis.opsForValue().get(counter));
            assertFalse(Boolean.TRUE.equals(redis.hasKey(hash))); assertFalse(Boolean.TRUE.equals(redis.hasKey(marker)));
        } finally { start.countDown(); executor.shutdownNow(); }
    }

    @Test void crashBeforeSqlCommitRestoresPreparedStateForTheExistingFolder() {
        assertTrue(service.favorite(request())); sync();
        redis.opsForValue().set(marker, "D:" + user + ":1:isolated-crash");
        assertTrue(service.unfavorite(request())); sync();
        assertEquals(0, rows()); assertEquals("0", redis.opsForValue().get(counter));
    }

    @Test void crashAfterSqlCommitCanFinishFromTheRetainedJournal() {
        assertTrue(service.favorite(request())); sync();
        redis.opsForValue().set(marker, "D:" + user + ":1:isolated-crash");
        deleteSqlParents();
        redis.delete(counter);
        sync(); sync();
        assertEquals("0", redis.opsForValue().get(counter));
        assertFalse(Boolean.TRUE.equals(redis.hasKey(hash))); assertFalse(Boolean.TRUE.equals(redis.hasKey(marker)));
    }

    @Test void postCommitRedisFailureRetainsTheJournalUntilRetry() {
        assertTrue(service.favorite(request())); sync();
        doAnswer(call -> {
            RedisScript<?> script = call.getArgument(0);
            if (script.getScriptAsString().contains("完整预检后一次性")) throw new IllegalStateException("isolated Redis failure");
            return call.callRealMethod();
        }).when(redis).execute(any(RedisScript.class), anyList(), any(Object[].class));
        assertDoesNotThrow(() -> service.deleteFolder(folder));
        assertEquals(0, rows()); assertTrue(redis.opsForValue().get(marker).startsWith("D:"));
        assertEquals("1", redis.opsForValue().get(counter));
        doCallRealMethod().when(redis).execute(any(RedisScript.class), anyList(), any(Object[].class));
        sync(); assertEquals("0", redis.opsForValue().get(counter)); assertFalse(Boolean.TRUE.equals(redis.hasKey(marker)));
    }

    @Test void missingCounterIsRebuiltFromCurrentRelationsAndPendingChanges() {
        assertTrue(service.favorite(request()));
        // SQL 尚未同步，game.favorite_count 仍可能为 0，重算必须保留 pending 中的 1。
        redis.delete(counter);
        assertFalse(recoverCounter(() -> service.favorite(request())));
        assertEquals("1", redis.opsForValue().get(counter));
        assertTrue(service.unfavorite(request()));
        redis.delete(counter);
        assertFalse(recoverCounter(() -> service.unfavorite(request())));
        assertEquals("0", redis.opsForValue().get(counter));
    }

    /**
     * 共享 VM 的后台同步也会确认刚结束夹具的 processing。只重试 503，最多 1 秒，其他错误直接失败。
     * 等待期间计数必须仍缺失，Hash 和标记必须保留，最终仍严格验证去重结果与精确计数。
     */
    private Boolean recoverCounter(java.util.function.Supplier<Boolean> action) {
        for (int attempt = 0; attempt < 20; attempt++) {
            try { return action.get(); }
            catch (BusinessException failure) {
                if (failure.getHttpStatus() != 503 || attempt == 19) throw failure;
                assertNull(redis.opsForValue().get(counter));
                assertNotNull(redis.opsForValue().get(marker));
                try { Thread.sleep(50); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted);
                }
            }
        }
        throw new AssertionError("Counter recovery attempts exhausted");
    }

    @Test void atomicBatchClearRetainsConfirmedZeroAndUpdatesTheDatabase() {
        assertTrue(service.favorite(request())); sync();
        assertTrue(service.unfavoriteBatch(folder));
        assertEquals("0", redis.opsForValue().get(marker));
        assertEquals("0", redis.opsForValue().get(counter));
        service.fullSyncFavoriteItemsToDBTask(100);
        assertEquals(0, rows());
        assertEquals(0, jdbc.queryForObject("SELECT game_count FROM favorite_folder WHERE id=?", Integer.class, folder));
    }

    @Test void fullSyncRemovesPhysicallyDeletedGamesFromCacheAndSql() {
        assertTrue(service.favorite(request())); sync();
        jdbc.update("DELETE FROM game WHERE id=? AND publisher_id=?", game, user);
        service.fullSyncFavoriteItemsToDBTask(100);
        assertEquals(0, rows());
        assertEquals("0", redis.opsForValue().get(marker));
        assertEquals(0L, redis.opsForHash().size(hash));
        assertEquals(0, jdbc.queryForObject("SELECT game_count FROM favorite_folder WHERE id=?", Integer.class, folder));
    }

    @Test void oldOrphanRowsAreCommittedBeforeTheCacheIsFinalized() {
        assertTrue(service.favorite(request())); sync();
        jdbc.update("DELETE FROM favorite_folder WHERE id=? AND user_id=?", folder, user);
        assertEquals(1, rows());
        sync();
        assertEquals(0, rows()); assertEquals("0", redis.opsForValue().get(counter));
        assertFalse(Boolean.TRUE.equals(redis.hasKey(marker)));
    }

    @Configuration @EnableTransactionManagement @MapperScan(basePackageClasses = GameMapper.class)
    static class Config {
        @Bean DataSource dataSource() { return new DriverManagerDataSource(System.getenv("MYSQL_URL"),
                System.getenv("MYSQL_USERNAME"), System.getenv("MYSQL_PASSWORD")); }
        @Bean DataSourceTransactionManager transactionManager(DataSource source) { return new DataSourceTransactionManager(source); }
        @Bean TransactionTemplate transactionTemplate(DataSourceTransactionManager manager) { return new TransactionTemplate(manager); }
        @Bean JdbcTemplate jdbcTemplate(DataSource source) { return new JdbcTemplate(source); }
        @Bean SqlSessionFactory sqlSessionFactory(DataSource source) throws Exception {
            MybatisConfiguration config = new MybatisConfiguration(); config.setMapUnderscoreToCamelCase(true);
            config.setLogImpl(org.apache.ibatis.logging.nologging.NoLoggingImpl.class);
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(source); factory.setConfiguration(config);
            factory.setMapperLocations(new ClassPathResource("mapper/FavoriteItemMapper.xml"));
            return factory.getObject();
        }
        @Bean(destroyMethod = "destroy") LettuceConnectionFactory connection() {
            var config = new RedisStandaloneConfiguration(System.getenv("REDIS_HOST"),
                    Integer.parseInt(System.getenv().getOrDefault("REDIS_PORT", "6379")));
            String password = System.getenv("REDIS_PASSWORD");
            if (password != null && !password.isEmpty()) config.setPassword(RedisPassword.of(password));
            config.setDatabase(Integer.parseInt(System.getenv().getOrDefault("REDIS_DATABASE", "0")));
            return new LettuceConnectionFactory(config);
        }
        @Bean StringRedisTemplate redis(LettuceConnectionFactory connection) { return spy(new StringRedisTemplate(connection)); }
        @Bean RedisDirtySetProcessor processor() { return mock(RedisDirtySetProcessor.class); }
        @Bean IFavoriteItemService favorites(StringRedisTemplate redis, FavoriteFolderMapper folders, FavoriteItemMapper items,
                GameMapper games, RedisDirtySetProcessor processor, DataSourceTransactionManager manager) {
            return new FavoriteItemServiceImpl(redis, folders, mock(RabbitMqHelper.class), items, games,
                    new CurrentUserProvider(), processor, manager);
        }
    }
}
