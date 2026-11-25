package com.catgal.server.catalog;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.catgal.common.context.*;
import com.catgal.common.exception.BusinessException;
import com.catgal.server.domain.dto.admin.AdminPrizeSaveDTO;
import com.catgal.server.domain.po.RewardOutbox;
import com.catgal.server.controller.PrizePoolController;
import com.catgal.server.controller.SignController;
import com.catgal.server.exception.GlobalExceptionHandler;
import com.catgal.server.mapper.*;
import com.catgal.server.service.*;
import com.catgal.server.service.impl.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import javax.sql.DataSource;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 真实 MySQL 并发测试。只使用随机账号与独立 209912 奖池，不切换共享 reward_runtime。
 * 测试事件延迟一天投递，防止线上定时任务领取临时夹具。清理严格限定本例账号与奖品 ID。
 */
@SpringJUnitConfig(RewardTransactionDatabaseLiveTest.Config.class)
@EnabledIfEnvironmentVariable(named = "CATGAL_REWARD_DB_TEST", matches = "1")
class RewardTransactionDatabaseLiveTest {
    @Autowired IRewardTransactionService rewards;
    @Autowired IAdminPrizeService administration;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transaction;
    @Autowired AtomicBoolean failOutbox;
    @Autowired PrizePoolMapper prizeMapper;
    @Autowired DrawRecordMapper drawMapper;
    @Autowired IRewardControlService control;
    @Autowired IAdminOperationLogService audit;
    @Autowired ObjectMapper json;
    @Autowired IPrizePoolService legacyPool;
    @Autowired StringRedisTemplate legacyRedis;
    private Long first;
    private Long second;
    private Long prizeId;
    private Long extraPrizeId;
    private String prefix;

    /** 真实 MVC 参数绑定与生产入口，固定时钟只作用于本测试容器，奖池仍使用独立未来月份。 */
    private MockMvc http() {
        var pool = new PrizePoolServiceImpl(mock(StringRedisTemplate.class), mock(IUserCutePointsService.class),
                mock(IConfirmedRewardPublisher.class), prizeMapper, drawMapper, mock(RedisDirtySetProcessor.class), control, rewards);
        var sign = new SignServiceImpl(mock(StringRedisTemplate.class), mock(IConfirmedRewardPublisher.class), control, rewards);
        return MockMvcBuilders.standaloneSetup(new PrizePoolController(pool, rewards, control), new SignController(sign))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @BeforeEach void setup() {
        failOutbox.set(false);
        reset(control, legacyPool, legacyRedis);
        when(control.isActive()).thenReturn(true);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM prize_pool WHERE month='209912'", Integer.class),
                "专用未来月份必须为空，测试不会清理既有奖品");
        prefix = "rw" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        first = insertUser("a", 2); second = insertUser("b", 0);
        jdbc.update("INSERT INTO prize_pool(prize_name,prize_image,prize_type,month,weight,total_stock,remain_stock,status) "
                + "VALUES(?,'',4,'209912',100,100,100,1)", prefix);
        prizeId = jdbc.queryForObject("SELECT id FROM prize_pool WHERE prize_name=?", Long.class, prefix);
        actAs(first);
    }
    private Long insertUser(String suffix, int role) {
        jdbc.update("INSERT INTO user(username,password,role,status) VALUES(?,'isolated-no-login',?,1)", prefix + suffix, role);
        Long id = jdbc.queryForObject("SELECT id FROM user WHERE username=?", Long.class, prefix + suffix);
        jdbc.update("INSERT INTO user_cute_points(user_id,cute_points,total_earned,total_spent) VALUES(?,20,20,0)", id);
        return id;
    }
    private void actAs(Long id) { UserContext.setUser(UserInfo.builder().userId(id).role(2).build()); }
    private int balance(Long id) { return jdbc.queryForObject("SELECT cute_points FROM user_cute_points WHERE user_id=?", Integer.class, id); }
    private int stock() { return jdbc.queryForObject("SELECT remain_stock FROM prize_pool WHERE id=?", Integer.class, prizeId); }
    private int count(String table, Long id) {
        assertTrue(Set.of("reward_request", "cute_points_record", "draw_record").contains(table));
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE user_id=?", Integer.class, id);
    }

    @AfterEach void cleanup() {
        failOutbox.set(false); UserContext.clear();
        if (first == null) return;
        Long other = second == null ? first : second;
        transaction.executeWithoutResult(status -> {
            jdbc.update("DELETE e FROM reward_outbox e JOIN reward_request r ON r.id=e.request_id WHERE r.user_id IN (?,?)", first, other);
            for (String table : List.of("reward_request", "cute_points_record", "draw_record", "user_cute_points"))
                jdbc.update("DELETE FROM " + table + " WHERE user_id IN (?,?)", first, other);
            if (prizeId != null) cleanPrize(prizeId, prefix);
            if (extraPrizeId != null) cleanPrize(extraPrizeId, prefix + "-audit");
            jdbc.update("DELETE FROM user WHERE id IN (?,?) AND username IN (?,?)", first, other, prefix + "a", prefix + "b");
        });
    }

    private void cleanPrize(Long id, String name) {
        jdbc.update("DELETE FROM admin_operation_log WHERE module='prize' AND target_type='prize' AND target_id=? AND admin_user_id=?",
                id.toString(), first);
        jdbc.update("DELETE FROM prize_pool WHERE id=? AND prize_name=? AND month='209912'", id, name);
    }

    private List<String> prizeOperations(Long id) {
        return jdbc.queryForList("SELECT operation FROM admin_operation_log "
                        + "WHERE module='prize' AND target_type='prize' AND admin_user_id=? AND target_id=? ORDER BY id",
                String.class, first, id.toString());
    }

    private AdminPrizeSaveDTO prizeInput(String name) {
        var dto = new AdminPrizeSaveDTO();
        dto.setMonth("2099-12"); dto.setPrizeName(name);
        dto.setPrizeImage("https://example.invalid/prize.png?fixture=private-image");
        dto.setPrizeType(4); dto.setWeight(100); dto.setStatus(1);
        dto.setTotalStock(100); dto.setRemainStock(100);
        return dto;
    }

    private List<Integer> concurrent(int total, java.util.function.IntFunction<Integer> action) throws Exception {
        var executor = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Integer>> work = new ArrayList<>();
            for (int i = 0; i < total; i++) { int index = i; work.add(() -> action.apply(index)); }
            List<Integer> result = new ArrayList<>();
            for (Future<Integer> future : executor.invokeAll(work)) result.add(future.get(20, TimeUnit.SECONDS));
            return result;
        } finally { executor.shutdownNow(); }
    }

    @Test void concurrentDuplicateSignCommitsOneCreditLedgerRequestAndEvent() throws Exception {
        concurrent(24, index -> { rewards.signIn(first); return 200; });
        assertEquals(21, balance(first)); assertEquals(1, count("cute_points_record", first));
        assertEquals(1, count("reward_request", first));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM reward_outbox e JOIN reward_request r ON r.id=e.request_id WHERE r.user_id=?", Integer.class, first));
        assertEquals(List.of(11L), rewards.signRecords(first).getSignedDays());
    }

    @Test void duplicateDrawReturnsOneSavedResultAndOneDebitUnderConcurrency() throws Exception {
        String key = UUID.randomUUID().toString();
        List<Integer> ids = concurrent(24, index -> rewards.draw(first, key).getId().intValue());
        assertTrue(ids.stream().allMatch(id -> id.longValue() == prizeId));
        assertEquals(15, balance(first)); assertEquals(99, stock());
        assertEquals(1, count("draw_record", first)); assertEquals(1, count("cute_points_record", first));
        assertTrue(rewards.drawResult(first, key).getReplayed());
    }

    @Test void differentRequestsCannotSpendMoreThanAuthoritativeBalance() throws Exception {
        List<Integer> codes = concurrent(24, index -> {
            try { rewards.draw(first, UUID.randomUUID().toString()); return 200; }
            catch (BusinessException failure) { return failure.getCode(); }
        });
        assertEquals(4L, codes.stream().filter(code -> code == 200).count());
        assertTrue(codes.stream().allMatch(code -> code == 200 || code == 40921));
        assertEquals(0, balance(first)); assertEquals(96, stock());
        assertEquals(4, count("draw_record", first)); assertEquals(4, count("cute_points_record", first));
        assertEquals(24, count("reward_request", first));
    }

    @Test void twoUsersCannotOversellThreeRemainingPrizes() throws Exception {
        jdbc.update("UPDATE prize_pool SET total_stock=3,remain_stock=3 WHERE id=?", prizeId);
        List<Integer> codes = concurrent(24, index -> {
            Long user = index % 2 == 0 ? first : second;
            try { rewards.draw(user, UUID.randomUUID().toString()); return 200; }
            catch (BusinessException failure) { return failure.getCode(); }
        });
        assertEquals(3L, codes.stream().filter(code -> code == 200).count());
        assertEquals(0, stock()); assertEquals(25, balance(first) + balance(second));
        assertEquals(3, count("draw_record", first) + count("draw_record", second));
    }

    @Test void outboxFailureRollsBackDebitStockDrawAndLedgerTogether() {
        failOutbox.set(true);
        assertThrows(IllegalStateException.class, () -> rewards.draw(first, UUID.randomUUID().toString()));
        assertEquals(20, balance(first)); assertEquals(100, stock());
        assertEquals(0, count("draw_record", first)); assertEquals(0, count("cute_points_record", first));
        assertEquals(0, count("reward_request", first));
    }

    @Test void signOutboxFailureRollsBackBalanceAndRequest() {
        failOutbox.set(true);
        assertThrows(IllegalStateException.class, () -> rewards.signIn(first));
        assertEquals(20, balance(first)); assertEquals(0, count("cute_points_record", first));
        assertEquals(0, count("reward_request", first));
    }

    @Test void rejectionIsPersistedAndSameKeyCannotChargeAfterTopUp() {
        jdbc.update("UPDATE user_cute_points SET cute_points=0,total_spent=20 WHERE user_id=?", first);
        String key = UUID.randomUUID().toString();
        assertEquals(40921, assertThrows(BusinessException.class, () -> rewards.draw(first, key)).getCode());
        jdbc.update("UPDATE user_cute_points SET cute_points=20,total_spent=0 WHERE user_id=?", first);
        assertEquals(40921, assertThrows(BusinessException.class, () -> rewards.draw(first, key)).getCode());
        assertEquals(20, balance(first)); assertEquals(100, stock()); assertEquals(1, count("reward_request", first));
    }

    @Test void recoveryCannotReadAnotherUsersResult() {
        String key = UUID.randomUUID().toString(); rewards.draw(first, key);
        assertEquals(40421, assertThrows(BusinessException.class, () -> rewards.drawResult(second, key)).getCode());
    }

    @Test void staleAdminFormCannotRestoreStockConsumedByDraw() {
        var dto = new AdminPrizeSaveDTO(); dto.setMonth("2099-12"); dto.setPrizeName(prefix);
        dto.setPrizeImage(""); dto.setPrizeType(4); dto.setWeight(100); dto.setStatus(1);
        dto.setTotalStock(100); dto.setRemainStock(100); dto.setRevision("0");
        rewards.draw(second, UUID.randomUUID().toString());
        assertEquals(409, assertThrows(BusinessException.class, () -> administration.updatePrize(prizeId, dto)).getHttpStatus());
        assertEquals(99, stock());
        assertTrue(prizeOperations(prizeId).isEmpty());
        dto.setRevision("1"); dto.setRemainStock(99);
        assertEquals("209912", administration.updatePrize(prizeId, dto).getMonth()); assertEquals(99, stock());
        assertEquals(List.of("PRIZE_UPDATE"), prizeOperations(prizeId));
    }

    @Test void activePrizeWritesRecordPersistedSnapshotsAndOmitImageAccessData() throws Exception {
        var dto = prizeInput(prefix + "-audit");
        var created = administration.createPrize(dto);
        extraPrizeId = created.getId();
        dto.setRevision(created.getRevision().toString());
        dto.setRemainStock(87);
        var updated = administration.updatePrize(extraPrizeId, dto);
        var disabled = administration.updateStatus(extraPrizeId, 0, updated.getRevision().toString());
        assertEquals(disabled.getRevision(),
                administration.updateStatus(extraPrizeId, 0, disabled.getRevision().toString()).getRevision());
        administration.updateStatus(extraPrizeId, 1, disabled.getRevision().toString());
        assertEquals(List.of("PRIZE_CREATE", "PRIZE_UPDATE", "PRIZE_DISABLE", "PRIZE_ENABLE"), prizeOperations(extraPrizeId));

        var history = jdbc.queryForList("SELECT before_data, after_data FROM admin_operation_log "
                + "WHERE module='prize' AND admin_user_id=? AND target_id=? ORDER BY id", first, extraPrizeId.toString());
        assertTrue(json.readTree((String) history.get(0).get("before_data")).isEmpty());
        var before = json.readTree((String) history.get(1).get("before_data"));
        var after = json.readTree((String) history.get(1).get("after_data"));
        assertEquals(100, before.path("remainStock").asInt());
        assertEquals(87, after.path("remainStock").asInt());
        assertEquals("1", after.path("revision").textValue());
        assertFalse(history.toString().contains("private-image"));
        assertFalse(history.toString().contains("prizeImage"));
    }

    @Test void failedAuditRollsBackPrizeStockAndRevision() {
        AdminOperationLogServiceImpl writer = AopTestUtils.getUltimateTargetObject(audit);
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new IllegalStateException("isolated prize audit outage");
        }).when(writer).appendPrizeChange(eq(first), eq("PRIZE_UPDATE"), any(), any());
        try {
            var dto = prizeInput(prefix);
            dto.setRemainStock(25);
            dto.setRevision("0");
            assertThrows(IllegalStateException.class, () -> administration.updatePrize(prizeId, dto));
            assertEquals(100, stock());
            assertEquals(0L, prizeMapper.selectById(prizeId).getRevision());
            assertTrue(prizeOperations(prizeId).isEmpty());
        } finally {
            reset(writer);
        }
    }

    @Test void legacyAuditUsesSyncedStockAndSkipsRepeatedStatus() throws Exception {
        legacyMode(true);
        var dto = prizeInput(prefix + "-audit");
        var created = administration.createPrize(dto);
        extraPrizeId = created.getId();
        simulateLegacyStockFlush(extraPrizeId, 94);
        dto.setRemainStock(87);
        var updated = administration.updatePrize(extraPrizeId, dto);
        assertEquals(0L, updated.getRevision());
        administration.updateStatus(extraPrizeId, 0, null);
        administration.updateStatus(extraPrizeId, 0, null);
        administration.updateStatus(extraPrizeId, 1, null);
        assertEquals(List.of("PRIZE_CREATE", "PRIZE_UPDATE", "PRIZE_DISABLE", "PRIZE_ENABLE"), prizeOperations(extraPrizeId));

        String original = jdbc.queryForObject("SELECT before_data FROM admin_operation_log "
                + "WHERE module='prize' AND operation='PRIZE_UPDATE' AND admin_user_id=? AND target_id=?",
                String.class, first, extraPrizeId.toString());
        assertEquals(94, json.readTree(original).path("remainStock").asInt());
        verify(legacyPool, times(5)).refreshPrizeCache("209912");
    }

    @Test void legacyAuditRollbackKeepsPreviouslyFlushedStockWithoutRefreshingCache() {
        legacyMode(false);
        simulateLegacyStockFlush(prizeId, 95);
        var writer = AopTestUtils.<AdminOperationLogServiceImpl>getUltimateTargetObject(audit);
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new IllegalStateException("isolated legacy audit outage");
        }).when(writer).appendPrizeChange(eq(first), eq("PRIZE_UPDATE"), any(), any());
        try {
            var dto = prizeInput(prefix);
            dto.setRemainStock(25);
            assertThrows(IllegalStateException.class, () -> administration.updatePrize(prizeId, dto));
            assertEquals(95, stock());
            assertTrue(prizeOperations(prizeId).isEmpty());
            verify(legacyPool, never()).refreshPrizeCache(anyString());
            verify(legacyRedis).execute(any(RedisScript.class), anyList(), anyString());
        } finally {
            reset(writer);
        }
    }

    private void legacyMode(boolean withControlTransaction) {
        when(control.isActive()).thenReturn(false);
        doAnswer(invocation -> {
            Supplier<?> action = invocation.getArgument(0);
            return withControlTransaction ? transaction.execute(status -> action.get()) : action.get();
        }).when(control).legacySource(any());
        when(legacyRedis.opsForValue().setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
    }

    private void simulateLegacyStockFlush(Long id, int remaining) {
        doAnswer(invocation -> {
            // 模拟原库存同步独立提交，测试库不切换共享奖励模式也不写当月 Redis。
            var independent = new TransactionTemplate(transaction.getTransactionManager());
            independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            independent.executeWithoutResult(status ->
                    jdbc.update("UPDATE prize_pool SET remain_stock=? WHERE id=?", remaining, id));
            return null;
        }).when(legacyPool).prizeStockToDB(anyString(), anyInt());
    }

    @Test void bannedUserCannotDrawAndMissingBalanceCanBeInitializedOnce() {
        jdbc.update("UPDATE user SET status=0 WHERE id=?", first);
        assertEquals(40301, assertThrows(BusinessException.class, () -> rewards.draw(first, UUID.randomUUID().toString())).getCode());
        jdbc.update("DELETE FROM user_cute_points WHERE user_id=?", second);
        rewards.signIn(second); rewards.signIn(second);
        assertEquals(1, balance(second)); assertEquals(1, count("cute_points_record", second));
    }

    @Test void httpUuidBindingAndRecoveryKeepOneDrawAndSavedResult() throws Exception {
        MockMvc mvc = http();
        String key = UUID.randomUUID().toString();
        mvc.perform(post("/prize/draw").header("Idempotency-Key", key))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.requestKey").value(key))
                .andExpect(jsonPath("$.data.pointsBalance").value(15));
        mvc.perform(post("/prize/draw").header("Idempotency-Key", key.toUpperCase(Locale.ROOT)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.replayed").value(true));
        mvc.perform(get("/prize/draw/requests/{key}", key))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(prizeId.intValue()));
        assertEquals(15, balance(first)); assertEquals(99, stock()); assertEquals(1, count("draw_record", first));
        actAs(second);
        mvc.perform(get("/prize/draw/requests/{key}", key))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(40421));
    }

    @Test void httpLegacyCallerReceivesGeneratedUuidAndInvalidHeaderCannotWrite() throws Exception {
        MockMvc mvc = http();
        String body = mvc.perform(post("/prize/draw"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String key = new ObjectMapper().readTree(body).path("data").path("requestKey").asText();
        assertEquals(key, UUID.fromString(key).toString());
        mvc.perform(get("/prize/draw/requests/{key}", key)).andExpect(status().isOk());
        mvc.perform(post("/prize/draw").header("Idempotency-Key", "invalid"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
        assertEquals(15, balance(first)); assertEquals(1, count("reward_request", first));
    }

    @Test void httpSignAndRejectedDrawUsePersistentResults() throws Exception {
        MockMvc mvc = http();
        mvc.perform(post("/sign")).andExpect(status().isOk());
        mvc.perform(post("/sign")).andExpect(status().isOk());
        mvc.perform(get("/sign")).andExpect(status().isOk()).andExpect(jsonPath("$.data.signedDays[0]").value(11));
        assertEquals(21, balance(first)); assertEquals(1, count("cute_points_record", first));
        jdbc.update("UPDATE user_cute_points SET cute_points=0,total_spent=20 WHERE user_id=?", second);
        actAs(second);
        String key = UUID.randomUUID().toString();
        mvc.perform(post("/prize/draw").header("Idempotency-Key", key))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(40921));
        mvc.perform(get("/prize/draw/requests/{key}", key))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(40921));
        assertEquals(0, balance(second)); assertEquals(100, stock());
    }

    @Configuration @EnableTransactionManagement @MapperScan(basePackageClasses = UserMapper.class)
    static class Config {
        @Bean DataSource source() { return new DriverManagerDataSource(System.getenv("MYSQL_URL"), System.getenv("MYSQL_USERNAME"), System.getenv("MYSQL_PASSWORD")); }
        @Bean DataSourceTransactionManager transactionManager(DataSource source) { return new DataSourceTransactionManager(source); }
        @Bean TransactionTemplate transactions(DataSourceTransactionManager manager) { return new TransactionTemplate(manager); }
        @Bean JdbcTemplate jdbc(DataSource source) { return new JdbcTemplate(source); }
        @Bean ObjectMapper json() { return new ObjectMapper().findAndRegisterModules(); }
        @Bean Clock clock() { return Clock.fixed(Instant.parse("2099-12-11T12:00:00Z"), IUserAccountStatusService.TIME_ZONE); }
        @Bean AtomicBoolean failOutbox() { return new AtomicBoolean(); }
        @Bean SqlSessionFactory sqlSessionFactory(DataSource source) throws Exception {
            var configuration = new MybatisConfiguration(); configuration.setMapUnderscoreToCamelCase(true);
            configuration.setLogImpl(org.apache.ibatis.logging.nologging.NoLoggingImpl.class);
            var factory = new MybatisSqlSessionFactoryBean(); factory.setDataSource(source); factory.setConfiguration(configuration);
            return factory.getObject();
        }
        @Bean IRewardControlService control() {
            IRewardControlService service = mock(IRewardControlService.class); when(service.isActive()).thenReturn(true); return service;
        }
        @Bean IUserAccountStatusService accounts(UserMapper users) { return new UserAccountStatusServiceImpl(users); }
        @Bean IAdminOperationLogService audit(ObjectMapper json, IUserAccountStatusService accounts,
                                              DataSourceTransactionManager transactionManager) {
            return spy(new AdminOperationLogServiceImpl(json, accounts, transactionManager));
        }
        @Bean IRewardTransactionService rewards(IRewardControlService control, UserMapper users, UserCutePointsMapper balances,
                CutePointsRecordMapper ledger, PrizePoolMapper prizes, DrawRecordMapper draws, RewardRequestMapper requests,
                RewardOutboxMapper outbox, IUserAccountStatusService accounts, ObjectMapper json, TransactionTemplate transactions,
                Clock clock, AtomicBoolean failOutbox) {
            RewardOutboxMapper delayed = mock(RewardOutboxMapper.class, delegatesTo(outbox));
            doAnswer(invocation -> {
                if (failOutbox.get()) throw new IllegalStateException("isolated Outbox outage");
                RewardOutbox event = invocation.getArgument(0);
                event.setNextAttemptAt(LocalDateTime.now(ZoneOffset.UTC).plusDays(1));
                return outbox.insert(event);
            }).when(delayed).insert(any(RewardOutbox.class));
            return new RewardTransactionServiceImpl(control, users, balances, ledger, prizes, draws, requests,
                    delayed, accounts, json, transactions, clock);
        }
        @Bean IPrizePoolService legacyPool() { return mock(IPrizePoolService.class); }
        @Bean StringRedisTemplate legacyRedis() { return mock(StringRedisTemplate.class, RETURNS_DEEP_STUBS); }
        @Bean IAdminPrizeService administration(PrizePoolMapper prizes, IRewardControlService control, TransactionTemplate transactions,
                UserMapper users, IUserAccountStatusService accounts, IAdminOperationLogService audit,
                IPrizePoolService legacyPool, StringRedisTemplate legacyRedis, Clock clock) {
            return new AdminPrizeServiceImpl(prizes, legacyPool, legacyRedis, control, transactions, users, accounts, audit, clock);
        }
    }
}
