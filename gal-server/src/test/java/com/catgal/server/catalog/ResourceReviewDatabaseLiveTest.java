package com.catgal.server.catalog;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.catgal.common.context.UserContext;
import com.catgal.common.context.UserInfo;
import com.catgal.common.domain.dto.ResourceAddDTO;
import com.catgal.common.domain.query.UserHomeQueryDTO;
import com.catgal.common.exception.BusinessException;
import com.catgal.server.controller.ResourceController;
import com.catgal.server.controller.admin.AdminCatalogController;
import com.catgal.server.service.admin.AdminCatalogService;
import com.catgal.server.domain.dto.ResourceEditDTO;
import com.catgal.server.domain.dto.admin.ResourceReviewDTO;
import com.catgal.server.domain.po.ResourceReviewHistory;
import com.catgal.server.domain.po.Game;
import com.catgal.server.domain.query.admin.AdminPageQuery;
import com.catgal.server.domain.query.admin.AdminResourceReviewLogQuery;
import com.catgal.server.domain.vo.admin.AdminOperationLogVO;
import com.catgal.server.domain.vo.admin.AdminResourceVO;
import com.catgal.server.exception.GlobalExceptionHandler;
import com.catgal.server.mapper.*;
import com.catgal.server.service.*;
import com.catgal.server.service.impl.*;
import com.catgal.server.service.userhome.UserHomeGameLookup;
import com.catgal.server.service.userhome.UserResourceTabQueryStrategy;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 使用真实 MySQL、触发器与事务代理。每例创建自己的用户、游戏和资源，结束后按ID清理。
 * 统计工作器仅领取当前测试游戏，Redis失败通过隔离投影模拟，不修改共享Redis和生产任务。
 */
@SpringJUnitConfig(ResourceReviewDatabaseLiveTest.Config.class)
@EnabledIfEnvironmentVariable(named = "CATGAL_RESOURCE_REVIEW_DB_TEST", matches = "1")
class ResourceReviewDatabaseLiveTest {
    @Autowired IResourceService resources;
    @Autowired IResourceCountService counts;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transaction;
    @Autowired AtomicReference<Long> testGame;
    @Autowired FailureSwitches failures;
    @Autowired StringRedisTemplate redis;
    @Autowired GameMapper gameMapper;
    private String prefix;
    private Long creator;
    private Long administrator;
    private Long game;

    static class FailureSwitches {
        final AtomicBoolean history = new AtomicBoolean();
        final AtomicBoolean redis = new AtomicBoolean();
        final AtomicReference<Runnable> beforeGameLock = new AtomicReference<>();
    }

    @BeforeEach void setup() {
        failures.history.set(false);
        failures.redis.set(false);
        failures.beforeGameLock.set(null);
        prefix = "rv" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        creator = user("c", 1);
        administrator = user("a", 2);
        jdbc.update("INSERT INTO game(name,publisher_id,status) VALUES(?,?,1)", prefix, creator);
        game = jdbc.queryForObject("SELECT id FROM game WHERE name=?", Long.class, prefix);
        testGame.set(game);
        actAs(creator);
    }

    private Long user(String suffix, int role) {
        jdbc.update("INSERT INTO user(username,password,role,status) VALUES(?,'isolated-no-login',?,1)", prefix + suffix, role);
        return jdbc.queryForObject("SELECT id FROM user WHERE username=?", Long.class, prefix + suffix);
    }

    private void actAs(Long id) {
        UserContext.setUser(UserInfo.builder().userId(id).build());
    }

    private ResourceAddDTO input() {
        ResourceAddDTO dto = new ResourceAddDTO();
        dto.setGameId(game);
        dto.setName(prefix + "-resource");
        dto.setDownloadUrl("https://example.com/CaseSensitivePath");
        dto.setExtractCode("test-secret");
        dto.setPassword("test-password");
        dto.setRemark("isolated test");
        dto.setFileSize("1GB");
        dto.setTypes("pc");
        dto.setLanguages("中文");
        dto.setPlatforms("win");
        return dto;
    }

    private AdminResourceVO create() {
        resources.addResource(input());
        Long id = jdbc.queryForObject("SELECT id FROM resource WHERE game_id=? ORDER BY id DESC LIMIT 1", Long.class, game);
        return resources.getEditable(id);
    }

    private ResourceEditDTO edit(AdminResourceVO current) {
        ResourceEditDTO dto = new ResourceEditDTO();
        org.springframework.beans.BeanUtils.copyProperties(current, dto);
        return dto;
    }

    private ResourceReviewDTO review(AdminResourceVO current, int status, String reason) {
        ResourceReviewDTO dto = new ResourceReviewDTO();
        dto.setRevision(current.getRevision());
        dto.setStatus(status);
        dto.setReason(reason);
        return dto;
    }

    private int histories() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM resource_review_history WHERE game_id=?", Integer.class, game);
    }

    @AfterEach void cleanup() {
        UserContext.clear();
        testGame.set(null);
        failures.history.set(false);
        failures.redis.set(false);
        failures.beforeGameLock.set(null);
        if (game == null) return;
        transaction.executeWithoutResult(status -> {
            jdbc.update("DELETE FROM resource_review_history WHERE game_id=?", game);
            jdbc.update("DELETE FROM resource WHERE game_id=?", game);
            jdbc.update("DELETE FROM game WHERE id=? AND name=?", game, prefix);
            // 保留删除触发器生成的ES墓碑任务，让运行中的索引工作器清理可能已索引的测试文档。
            jdbc.update("DELETE FROM user WHERE id IN (?,?) AND username IN (?,?)", creator, administrator, prefix + "c", prefix + "a");
        });
        redis.delete("game:resource:" + game);
    }

    @Test void creatorEditRequiresNewReviewAndStaleReviewCannotPublishIt() {
        AdminResourceVO initial = create();
        assertEquals(2, initial.getStatus());
        assertEquals(0L, initial.getRevision());
        actAs(administrator);
        AdminResourceVO approved = resources.reviewResource(initial.getId(), review(initial, 1, "核验通过"));
        assertEquals(1L, approved.getRevision());
        actAs(creator);
        ResourceEditDTO dto = edit(approved);
        dto.setDownloadUrl("https://example.com/updated");
        AdminResourceVO changed = resources.editResource(initial.getId(), dto);
        assertEquals(2, changed.getStatus());
        assertEquals(2L, changed.getRevision());
        assertNull(changed.getReviewedBy());
        assertNull(changed.getReviewReason());
        actAs(administrator);
        assertEquals(409, assertThrows(BusinessException.class,
                () -> resources.reviewResource(initial.getId(), review(approved, 1, "旧页面"))).getHttpStatus());
        assertEquals(3, histories());
    }

    @Test void historyFailureRollsBackResourceAndTriggerTaskTogether() {
        actAs(administrator);
        failures.history.set(true);
        assertThrows(IllegalStateException.class, () -> resources.addResource(input()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM resource WHERE game_id=?", Integer.class, game));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM resource_count_outbox WHERE game_id=?", Integer.class, game));
        assertEquals(0, histories());
    }

    @Test void failedReviewHistoryRollsBackApprovalAndDownloadCountersKeepVersionStable() {
        AdminResourceVO initial = create();
        jdbc.update("UPDATE resource SET download_count=download_count+1,like_count=like_count+1 WHERE id=?", initial.getId());
        assertEquals(0L, resources.getEditable(initial.getId()).getRevision());
        actAs(administrator);
        failures.history.set(true);
        assertThrows(IllegalStateException.class, () -> resources.reviewResource(initial.getId(), review(initial, 1, "通过")));
        assertEquals(2, resources.getEditable(initial.getId()).getStatus());
        assertEquals(0L, resources.getEditable(initial.getId()).getRevision());
        assertEquals(1, histories());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM resource_count_outbox WHERE game_id=?", Integer.class, game));
    }

    @Test void rejectionRequiresReasonAndOwnerCanSubmitAgainWithoutChangingContent() {
        AdminResourceVO initial = create();
        actAs(administrator);
        assertEquals(400, assertThrows(BusinessException.class,
                () -> resources.reviewResource(initial.getId(), review(initial, 0, "  "))).getHttpStatus());
        AdminResourceVO rejected = resources.reviewResource(initial.getId(), review(initial, 0, "下载链接失效"));
        assertEquals(administrator, rejected.getReviewedBy());
        assertEquals("下载链接失效", rejected.getReviewReason());
        actAs(creator);
        AdminResourceVO pending = resources.submitResource(initial.getId(), rejected.getRevision());
        assertEquals(2, pending.getStatus());
        assertNull(pending.getReviewReason());
        assertEquals(pending.getRevision(), resources.submitResource(initial.getId(), pending.getRevision()).getRevision());
        assertEquals(3, histories());
    }

    @Test void countFailureRetainsDurableTaskAndRecoveryUsesLatestTruth() {
        AdminResourceVO initial = create();
        actAs(administrator);
        AdminResourceVO approved = resources.reviewResource(initial.getId(), review(initial, 1, "通过"));
        failures.redis.set(true);
        counts.synchronizePending();
        assertEquals(1, jdbc.queryForObject("SELECT pending FROM resource_count_outbox WHERE game_id=?", Integer.class, game));
        assertEquals(1, jdbc.queryForObject("SELECT attempts FROM resource_count_outbox WHERE game_id=?", Integer.class, game));
        // 新状态重置旧失败任务的退避，恢复时应校准为0，不能重放之前的+1。
        resources.reviewResource(initial.getId(), review(approved, 0, "链接失效"));
        failures.redis.set(false);
        counts.synchronizePending();
        assertEquals(0, jdbc.queryForObject("SELECT pending FROM resource_count_outbox WHERE game_id=?", Integer.class, game));
        assertEquals(0, jdbc.queryForObject("SELECT resource_count FROM game WHERE id=?", Integer.class, game));
        verify(redis.opsForValue()).set("game:resource:" + game, "0");
        assertEquals("0", redis.opsForValue().get("game:resource:" + game));
    }

    @Test void genericStatsCannotOverwriteResourceCountWithOldCacheSnapshot() {
        actAs(administrator);
        create();
        counts.synchronizePending();
        Game stale = new Game().setId(game).setResourceCount(999).setFavoriteCount(0)
                .setViewCount(0).setCommentCount(0).setReviewCount(0).setDownloadCount(0);
        gameMapper.batchUpdateGameStats(List.of(stale));
        assertEquals(1, jdbc.queryForObject("SELECT resource_count FROM game WHERE id=?", Integer.class, game));
        assertEquals("1", redis.opsForValue().get("game:resource:" + game));
    }

    @Test void emptyOptionalFormValuesDoNotResubmitApprovedContent() {
        AdminResourceVO initial = create();
        jdbc.update("UPDATE resource SET remark=NULL,extract_code=NULL,password=NULL WHERE id=?", initial.getId());
        actAs(administrator);
        AdminResourceVO before = resources.getEditable(initial.getId());
        AdminResourceVO approved = resources.reviewResource(initial.getId(), review(before, 1, "通过"));
        actAs(creator);
        ResourceEditDTO dto = edit(approved);
        dto.setRemark("");
        dto.setExtractCode("");
        dto.setPassword("");
        AdminResourceVO current = resources.editResource(initial.getId(), dto);
        assertEquals(1, current.getStatus());
        assertEquals(approved.getRevision(), current.getRevision());
        assertEquals(2, histories());
    }

    @Test void ownResourceListIncludesReviewFieldsAndPublicProfileOmitsThem() {
        AdminResourceVO initial = create();
        actAs(administrator);
        resources.reviewResource(initial.getId(), review(initial, 1, "仅本人可见的审核反馈"));
        var strategy = new UserResourceTabQueryStrategy(resources, new UserHomeGameLookup(gameMapper));
        var json = new ObjectMapper().findAndRegisterModules();
        var own = json.valueToTree(strategy.query(new UserHomeQueryDTO(), creator, true).getList().get(0));
        assertEquals("1", own.path("revision").asText());
        assertTrue(own.path("revision").isTextual());
        assertEquals("仅本人可见的审核反馈", own.path("reviewReason").asText());
        var visible = json.valueToTree(strategy.query(new UserHomeQueryDTO(), creator, false).getList().get(0));
        assertFalse(visible.has("revision"));
        assertFalse(visible.has("reviewedAt"));
        assertFalse(visible.has("reviewReason"));
    }

    @Test void anotherCreatorAndBannedOwnerCannotEdit() {
        AdminResourceVO initial = create();
        jdbc.update("UPDATE user SET role=1 WHERE id=?", administrator);
        actAs(administrator);
        assertEquals(403, assertThrows(BusinessException.class, () -> resources.getEditable(initial.getId())).getHttpStatus());
        assertEquals(403, assertThrows(BusinessException.class, () -> resources.editResource(initial.getId(), edit(initial))).getHttpStatus());
        actAs(creator);
        jdbc.update("UPDATE user SET status=0 WHERE id=?", creator);
        assertEquals(40301, assertThrows(BusinessException.class, () -> resources.editResource(initial.getId(), edit(initial))).getCode());
        assertEquals(1, histories());
    }

    @Test void caseSensitiveLinkChangeAdvancesVersionEvenWhileAlreadyPending() {
        AdminResourceVO initial = create();
        ResourceEditDTO dto = edit(initial);
        dto.setDownloadUrl("https://example.com/casesensitivepath");
        AdminResourceVO changed = resources.editResource(initial.getId(), dto);
        assertEquals(1L, changed.getRevision(), "大小写不同的外链必须产生新业务版本");
        assertEquals(409, assertThrows(BusinessException.class, () -> resources.editResource(initial.getId(), dto)).getHttpStatus());
    }

    @Test void deleteRetainsPrivateHistoryAndCountsAreRecomputed() {
        actAs(administrator);
        AdminResourceVO initial = create();
        resources.deleteResource(initial.getId());
        assertEquals(2, resources.history(initial.getId(), new AdminPageQuery()).getTotal());
        assertEquals(2, histories());
        String history = jdbc.queryForObject("SELECT GROUP_CONCAT(changed_fields) FROM resource_review_history WHERE game_id=?", String.class, game);
        assertFalse(history.contains("test-secret"));
        assertFalse(history.contains("example.com"));
        counts.synchronizePending();
        assertEquals(0, jdbc.queryForObject("SELECT resource_count FROM game WHERE id=?", Integer.class, game));
    }

    @Test void adminAuditReusesReviewHistoryAfterResourceDeletion() {
        var initial = create();
        actAs(administrator);
        var approved = resources.reviewResource(initial.getId(), review(initial, 1, null));
        var requeued = resources.reviewResource(initial.getId(), review(approved, 2, null));
        var rejected = resources.reviewResource(initial.getId(), review(requeued, 0, "隔离验收原因"));
        resources.deleteResource(initial.getId());
        assertNull(resources.getById(initial.getId()));

        var query = new AdminResourceReviewLogQuery();
        query.setAdminUserId(administrator);
        query.setTargetId(initial.getId());
        query.setPageSize(2);
        var first = resources.pageAdminReviewOperations(query);
        assertEquals(3L, first.getTotal());
        assertEquals(List.of("REJECT", "REQUEUE"), first.getList().stream().map(AdminOperationLogVO::getOperation).toList());
        query.setPageNo(2);
        assertEquals("APPROVE", resources.pageAdminReviewOperations(query).getList().get(0).getOperation());
        query.setPageNo(1);

        var row = first.getList().get(0);
        assertEquals("resource", row.getTargetType());
        assertEquals(initial.getId().toString(), row.getTargetId());
        assertEquals(administrator, row.getAdminUserId());
        assertEquals("隔离验收原因", row.getReason());
        assertNull(row.getRequestId());
        assertNull(row.getIpAddress());
        assertEquals(Map.of("gameId", game.toString(), "revision", requeued.getRevision().toString(), "status", 2), row.getBeforeData());
        assertEquals(Map.of("gameId", game.toString(), "revision", rejected.getRevision().toString(), "status", 0), row.getAfterData());

        query.setOperation("REJECT");
        query.setFrom(row.getCreateTime().withOffsetSameInstant(ZoneOffset.UTC));
        query.setTo(row.getCreateTime());
        assertEquals(1L, resources.pageAdminReviewOperations(query).getTotal());
        query.setAdminUserId(creator);
        assertEquals(0L, resources.pageAdminReviewOperations(query).getTotal());
        assertEquals(5, histories(), "查询使用原记录，不额外写入历史");
    }

    @Test void crossResourceAuditRequiresCurrentAdminAndValidTimeWindow() {
        var initial = create();
        var query = new AdminResourceReviewLogQuery();
        query.setTargetId(initial.getId());
        assertEquals(1L, resources.history(initial.getId(), new AdminPageQuery()).getTotal());
        assertEquals(403, assertThrows(BusinessException.class,
                () -> resources.pageAdminReviewOperations(query)).getHttpStatus());

        actAs(administrator);
        query.setFrom(OffsetDateTime.parse("2026-11-20T00:00:00+08:00"));
        query.setTo(OffsetDateTime.parse("2026-11-18T00:00:00+08:00"));
        assertEquals(400, assertThrows(BusinessException.class,
                () -> resources.pageAdminReviewOperations(query)).getHttpStatus());
    }

    @Test void concurrentEditsAcceptOnlyOneOldVersion() throws Exception {
        AdminResourceVO initial = create();
        ExecutorService executor = Executors.newFixedThreadPool(6);
        try {
            List<Callable<Integer>> work = new ArrayList<>();
            for (int index = 0; index < 12; index++) {
                int sequence = index;
                work.add(() -> {
                    actAs(creator);
                    try {
                        ResourceEditDTO dto = edit(initial);
                        dto.setName(prefix + "-edit-" + sequence);
                        resources.editResource(initial.getId(), dto);
                        return 200;
                    } catch (BusinessException failure) { return failure.getHttpStatus(); }
                    finally { UserContext.clear(); }
                });
            }
            List<Integer> codes = new ArrayList<>();
            for (Future<Integer> future : executor.invokeAll(work)) codes.add(future.get(20, TimeUnit.SECONDS));
            assertEquals(1, codes.stream().filter(code -> code == 200).count());
            assertEquals(11, codes.stream().filter(code -> code == 409).count());
            assertEquals(2, histories());
        } finally { executor.shutdownNow(); }
    }

    @Test void reviewUsesLockedGameStateAfterAnEarlierRepeatableReadSnapshot() throws Exception {
        AdminResourceVO initial = create();
        CountDownLatch snapshotRead = new CountDownLatch(1);
        CountDownLatch gameWithdrawn = new CountDownLatch(1);
        failures.beforeGameLock.set(() -> {
            snapshotRead.countDown();
            try {
                if (!gameWithdrawn.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test game update timed out");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
        });
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Integer> response = executor.submit(() -> {
                actAs(administrator);
                try {
                    resources.reviewResource(initial.getId(), review(initial, 1, "通过"));
                    return 200;
                } catch (BusinessException failure) { return failure.getHttpStatus(); }
                finally { UserContext.clear(); }
            });
            assertTrue(snapshotRead.await(10, TimeUnit.SECONDS));
            jdbc.update("UPDATE game SET status=2 WHERE id=?", game);
            gameWithdrawn.countDown();
            assertEquals(409, response.get(15, TimeUnit.SECONDS));
            assertEquals(2, resources.getEditable(initial.getId()).getStatus());
            assertEquals(1, histories());
        } finally {
            gameWithdrawn.countDown();
            executor.shutdownNow();
        }
    }

    @Test void normalHttpEntryRequiresVersionAndBindsStringRevision() throws Exception {
        AdminResourceVO initial = create();
        var mvc = MockMvcBuilders.standaloneSetup(new ResourceController(resources))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(post("/resource/{id}/submit", initial.getId()).contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/resource/{id}/submit", initial.getId()).contentType("application/json").content("{\"revision\":\"0\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.revision").value("0"));
        actAs(administrator);
        var admin = MockMvcBuilders.standaloneSetup(new AdminCatalogController(mock(AdminCatalogService.class), resources))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        admin.perform(patch("/admin/resources/{id}/status", initial.getId())
                .contentType("application/json").content("{\"status\":1}"))
                .andExpect(status().isBadRequest());
        admin.perform(patch("/admin/resources/{id}/status", initial.getId())
                .contentType("application/json").content("{\"status\":1,\"revision\":\"0\",\"reason\":\"核验通过\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.revision").value("1"));
    }

    @Configuration @EnableTransactionManagement @MapperScan(basePackageClasses = UserMapper.class)
    static class Config {
        @Bean DataSource source() { return new DriverManagerDataSource(System.getenv("MYSQL_URL"), System.getenv("MYSQL_USERNAME"), System.getenv("MYSQL_PASSWORD")); }
        @Bean DataSourceTransactionManager transactionManager(DataSource source) { return new DataSourceTransactionManager(source); }
        @Bean TransactionTemplate transaction(DataSourceTransactionManager manager) { return new TransactionTemplate(manager); }
        @Bean JdbcTemplate jdbc(DataSource source) { return new JdbcTemplate(source); }
        @Bean AtomicReference<Long> testGame() { return new AtomicReference<>(); }
        @Bean FailureSwitches failures() { return new FailureSwitches(); }
        @Bean SqlSessionFactory sqlSessionFactory(DataSource source) throws Exception {
            var config = new MybatisConfiguration();
            config.setMapUnderscoreToCamelCase(true);
            config.setLogImpl(org.apache.ibatis.logging.nologging.NoLoggingImpl.class);
            var factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(source);
            factory.setConfiguration(config);
            factory.setMapperLocations(new ClassPathResource("mapper/GameMapper.xml"));
            var plugin = new MybatisPlusInterceptor();
            plugin.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
            factory.setPlugins(plugin);
            return factory.getObject();
        }
        @Bean IUserAccountStatusService accounts(UserMapper users) { return new UserAccountStatusServiceImpl(users); }
        @Bean IResourceService resources(UserMapper users, GameMapper games, ResourceReviewHistoryMapper history,
                IUserAccountStatusService accounts, FailureSwitches failures) {
            ResourceReviewHistoryMapper checked = mock(ResourceReviewHistoryMapper.class, delegatesTo(history));
            doAnswer(call -> {
                if (failures.history.get()) throw new IllegalStateException("isolated history failure");
                return history.insert((ResourceReviewHistory) call.getArgument(0));
            }).when(checked).insert(any(ResourceReviewHistory.class));
            IGameService lookup = mock(IGameService.class);
            when(lookup.getById(anyLong())).thenAnswer(call -> games.selectById((Long) call.getArgument(0)));
            GameMapper lockingGames = mock(GameMapper.class, delegatesTo(games));
            doAnswer(call -> {
                Runnable before = failures.beforeGameLock.getAndSet(null);
                if (before != null) before.run();
                return games.selectForUpdate(call.getArgument(0));
            }).when(lockingGames).selectForUpdate(anyLong());
            return new ResourceServiceImpl(users, lookup, mock(ILikeRecordService.class),
                    mock(IGameStatsService.class), mock(RedisCounterService.class), lockingGames, checked, accounts);
        }
        @Bean(destroyMethod = "destroy") LettuceConnectionFactory redisConnection() {
            var config = new RedisStandaloneConfiguration(System.getenv("REDIS_HOST"), Integer.parseInt(System.getenv("REDIS_PORT")));
            config.setDatabase(Integer.parseInt(Optional.ofNullable(System.getenv("REDIS_DATABASE")).orElse("0")));
            String password = System.getenv("REDIS_PASSWORD");
            if (password != null && !password.isBlank()) config.setPassword(password);
            return new LettuceConnectionFactory(config);
        }
        @Bean StringRedisTemplate redis(FailureSwitches failures, LettuceConnectionFactory connection) {
            StringRedisTemplate actual = new StringRedisTemplate(connection);
            StringRedisTemplate redis = mock(StringRedisTemplate.class, delegatesTo(actual));
            ValueOperations<String, String> values = mock(ValueOperations.class, delegatesTo(actual.opsForValue()));
            when(redis.opsForValue()).thenReturn(values);
            doAnswer(call -> {
                if (failures.redis.get()) throw new IllegalStateException("isolated Redis failure");
                actual.opsForValue().set(call.getArgument(0), call.getArgument(1));
                return null;
            }).when(values).set(anyString(), anyString());
            return redis;
        }
        @Bean IResourceCountService counts(GameMapper games, ResourceMapper resources, ResourceCountOutboxMapper outbox,
                StringRedisTemplate redis, DataSourceTransactionManager manager, AtomicReference<Long> testGame) {
            ResourceCountOutboxMapper isolated = mock(ResourceCountOutboxMapper.class, delegatesTo(outbox));
            when(isolated.dueGames()).thenAnswer(call -> testGame.get() == null ? List.of() : List.of(testGame.get()));
            return new ResourceCountServiceImpl(games, resources, isolated, redis, manager);
        }
    }
}
