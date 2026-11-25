package com.catgal.server.catalog;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.catgal.common.context.*;
import com.catgal.common.exception.BusinessException;
import com.catgal.server.domain.AdminRedisOperation;
import com.catgal.server.domain.AdminRedisOperation.Outcome;
import com.catgal.server.domain.dto.admin.AdminUserStatusUpdateDTO;
import com.catgal.server.domain.query.admin.*;
import com.catgal.server.mapper.UserMapper;
import com.catgal.server.security.CurrentUserProvider;
import com.catgal.server.service.*;
import com.catgal.server.service.impl.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
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
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 仅写入随机测试账号与这些账号产生的审计记录，不修改已有真实管理员。 */
@SpringJUnitConfig(AdminUserDatabaseLiveTest.Config.class)
@EnabledIfEnvironmentVariable(named = "CATGAL_ACCOUNT_DB_TEST", matches = "1")
class AdminUserDatabaseLiveTest {
    @Autowired IAdminUserService users;
    @Autowired IAdminOperationLogService audit;
    @Autowired IUserAccountStatusService status;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transaction;
    private Long first;
    private Long second;
    private Long target;
    private String prefix;

    @BeforeEach void setup() {
        prefix = "am" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        first = insert("a", 2); second = insert("b", 2); target = insert("c", 0);
        actAs(first);
    }
    private Long insert(String suffix, int role) {
        jdbc.update("INSERT INTO user(username,password,role,status,phone) VALUES (?, 'isolated-no-login', ?, 1, 'test-private')", prefix + suffix, role);
        return jdbc.queryForObject("SELECT id FROM user WHERE username=?", Long.class, prefix + suffix);
    }
    private void actAs(Long id) { UserContext.setUser(UserInfo.builder().userId(id).role(2).build()); }
    private AdminUserStatusUpdateDTO request(Long id, int value) {
        var dto = new AdminUserStatusUpdateDTO(); dto.setStatus(value); dto.setReason("isolated moderation reason");
        dto.setStateVersion(users.getUser(id).getStateVersion()); return dto;
    }
    private int audits(Long id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM admin_operation_log WHERE module='user' AND target_type='user' AND target_id=? AND admin_user_id IN (?,?)", Integer.class, id.toString(), first, second);
    }
    @AfterEach void cleanup() throws Exception {
        UserContext.clear();
        AdminOperationLogServiceImpl actual = AopTestUtils.getUltimateTargetObject(audit);
        doCallRealMethod().when(actual).appendUserChange(anyLong(), anyString(), anyLong(), anyMap(), anyMap(), any());
        if (first != null && second != null && target != null) transaction.executeWithoutResult(tx -> {
            jdbc.update("DELETE FROM admin_operation_log WHERE module='user' AND target_type='user' AND admin_user_id IN (?,?) AND target_id IN (?,?,?)", first, second, first.toString(), second.toString(), target.toString());
            jdbc.update("DELETE FROM admin_operation_log WHERE module='redis_consistency' AND admin_user_id=?", first);
            for (Long id : List.of(first, second, target)) {
                jdbc.update("DELETE FROM user WHERE id=? AND username LIKE ? AND password='isolated-no-login'", id, prefix + "%");
                assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM user WHERE id=?", Integer.class, id));
            }
        });
    }

    @Test void auditCannotBeCommittedOutsideItsBusinessTransaction() {
        assertThrows(org.springframework.transaction.IllegalTransactionStateException.class,
                () -> audit.appendUserChange(first, "USER_BAN", target, Map.of("status", 1), Map.of("status", 0), "test"));
        assertEquals(0, audits(target));
    }

    @Test void redisAuditPersistsAttemptBeforeExecutingAndFiltersTerminalOutcomes() {
        var query = new AdminRedisOperationLogQuery();
        query.setAdminUserId(first);
        query.setOperation(AdminRedisOperation.GAME_STATS_REPAIR);
        query.setTargetId(target);
        query.setOutcome(Outcome.UNCONFIRMED);
        assertEquals("repaired", audit.auditRedisMaintenance(first, AdminRedisOperation.GAME_STATS_REPAIR, target, () -> {
            var attempt = audit.pageRedisOperations(query).getList().get(0);
            assertEquals(Outcome.UNCONFIRMED, attempt.getOutcome());
            assertEquals(target.toString(), attempt.getTargetId());
            assertEquals("game", attempt.getTargetType());
            return "repaired";
        }));
        assertTrue(audit.pageRedisOperations(query).getList().isEmpty());
        query.setOutcome(Outcome.COMPLETED);
        assertEquals(1, audit.pageRedisOperations(query).getList().size());

        var failure = new IllegalStateException("isolated private Redis diagnostic");
        assertSame(failure, assertThrows(IllegalStateException.class, () -> audit.auditRedisMaintenance(
                first, AdminRedisOperation.COUNTERS_SYNC, null, () -> { throw failure; })));
        query.setOutcome(Outcome.FAILED);
        query.setOperation(AdminRedisOperation.COUNTERS_SYNC);
        query.setTargetId(null);
        var failed = audit.pageRedisOperations(query).getList().get(0);
        assertNull(failed.getTargetId());
        assertEquals("global", failed.getTargetType());
        assertEquals(Outcome.FAILED, failed.getOutcome());
        assertNull(failed.getReason());
        assertEquals(Map.of(), failed.getAfterData());
        assertEquals("REDIS_EXECUTION_FAILED", jdbc.queryForObject(
                "SELECT error_message FROM admin_operation_log WHERE id=?", String.class, failed.getId()));
    }

    @Test void banAndUnbanCommitTheirAuditAndClearNullableColumns() {
        var banned = users.updateStatus(target, request(target, 0));
        assertEquals(0, banned.getStatus()); assertNull(banned.getBannedUntil()); assertEquals(1, audits(target));
        assertEquals(40301, assertThrows(BusinessException.class, () -> status.requireActiveUser(target)).getCode());
        var restored = users.updateStatus(target, request(target, 1));
        assertEquals(1, restored.getStatus()); assertNull(restored.getBanReason()); assertNull(restored.getBannedUntil());
        assertNotNull(status.requireActiveUser(target)); assertEquals(2, audits(target));
        List<String> json = jdbc.queryForList("SELECT CONCAT(before_data, after_data) FROM admin_operation_log WHERE admin_user_id=? AND target_id=?", String.class, first, target.toString());
        for (String snapshot : json) { assertFalse(snapshot.contains("password")); assertFalse(snapshot.contains("test-private")); assertFalse(snapshot.contains("phone")); }
    }

    @Test void failedAuditInsertRollsBackTheUsersStatus() throws Exception {
        AdminOperationLogServiceImpl actual = AopTestUtils.getUltimateTargetObject(audit);
        doThrow(new IllegalStateException("isolated audit outage")).when(actual)
                .appendUserChange(eq(first), eq("USER_BAN"), eq(target), anyMap(), anyMap(), any());
        assertThrows(IllegalStateException.class, () -> users.updateStatus(target, request(target, 0)));
        assertEquals(1, jdbc.queryForObject("SELECT status FROM user WHERE id=?", Integer.class, target));
        assertEquals(0, audits(target));
    }

    @Test void outerTransactionRollbackAlsoRemovesTheAuditEntry() {
        transaction.executeWithoutResult(tx -> { users.updateRole(target, 1, null); assertEquals(1, audits(target)); tx.setRollbackOnly(); });
        assertEquals(0, jdbc.queryForObject("SELECT role FROM user WHERE id=?", Integer.class, target));
        assertEquals(0, audits(target));
    }

    @Test void concurrentSameVersionRequestsProduceExactlyOneChangeAndAudit() throws Exception {
        var input = request(target, 0);
        var pool = Executors.newFixedThreadPool(6);
        try {
            List<Callable<Integer>> calls = new ArrayList<>();
            for (int i = 0; i < 16; i++) calls.add(() -> {
                actAs(first);
                try { users.updateStatus(target, input); return 200; }
                catch (BusinessException failure) { assertEquals(409, failure.getHttpStatus()); return 409; }
                finally { UserContext.clear(); }
            });
            int successful = 0;
            for (var result : pool.invokeAll(calls)) if (result.get(15, TimeUnit.SECONDS) == 200) successful++;
            assertEquals(1, successful); assertEquals(1, audits(target));
            assertEquals(0, jdbc.queryForObject("SELECT status FROM user WHERE id=?", Integer.class, target));
        } finally { pool.shutdownNow(); }
    }

    @Test void simultaneousMutualDemotionLeavesOneActorAuthorized() throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            List<Callable<Integer>> calls = List.of(() -> demote(first, second, start), () -> demote(second, first, start));
            List<Future<Integer>> results = new ArrayList<>();
            for (var call : calls) results.add(pool.submit(call));
            start.countDown();
            int successes = 0;
            for (var result : results) if (result.get(15, TimeUnit.SECONDS) == 200) successes++;
            assertEquals(1, successes);
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM user WHERE id IN (?,?) AND role=2 AND status=1", Integer.class, first, second));
            assertEquals(1, audits(first) + audits(second));
        } finally { start.countDown(); pool.shutdownNow(); }
    }
    private int demote(Long actor, Long other, CountDownLatch start) throws Exception {
        actAs(actor); start.await();
        try { users.updateRole(other, 0, null); return 200; }
        catch (BusinessException failure) { assertEquals(403, failure.getHttpStatus()); return 403; }
        finally { UserContext.clear(); }
    }

    @Test void expiredBansMatchTheActiveFilterAndRemainReadableWithoutAWrite() {
        jdbc.update("UPDATE user SET status=0,ban_reason='timed fixture',banned_until=? WHERE id=?", status.now().minusMinutes(1), target);
        assertNotNull(status.requireActiveUser(target));
        assertEquals(0, jdbc.queryForObject("SELECT status FROM user WHERE id=?", Integer.class, target));
        var query = new AdminPageQuery(); query.setKeyword(prefix); query.setStatus(1);
        assertEquals(3, users.pageUsers(query).getList().size());
        assertTrue(users.getUser(target).getBanExpired());
        query.setStatus(0); assertTrue(users.pageUsers(query).getList().isEmpty());
        jdbc.update("UPDATE user SET banned_until=? WHERE id=?", status.now().plusMinutes(1), target);
        assertEquals(40301, assertThrows(BusinessException.class, () -> status.requireActiveUser(target)).getCode());
        assertEquals(List.of(target), users.pageUsers(query).getList().stream().map(value -> value.getId()).toList());
    }

    @Test void aRoleChangedAndRestoredStillInvalidatesTheOriginalForm() {
        String original = users.getUser(target).getStateVersion();
        users.updateRole(target, 1, null); users.updateRole(target, 0, null);
        assertEquals(0, users.getUser(target).getRole());
        assertNotEquals(original, users.getUser(target).getStateVersion());
        var oldForm = request(target, 0); oldForm.setStateVersion(original);
        assertEquals(409, assertThrows(BusinessException.class, () -> users.updateStatus(target, oldForm)).getHttpStatus());
        assertEquals(2, audits(target));
    }

    @Test void auditingFiltersAndPaginationKeepTheRequestedTargetScope() {
        for (int index = 0; index < 25; index++) users.updateRole(target, index % 2 == 0 ? 1 : 0, null);
        var query = new AdminOperationLogQuery(); query.setAdminUserId(first); query.setTargetUserId(target);
        query.setOperation("USER_ROLE_CHANGE"); query.setPageNo(1); query.setPageSize(20);
        var page1 = audit.pageUserOperations(query); assertEquals(25L, page1.getTotal()); assertEquals(20, page1.getList().size());
        query.setPageNo(2); var page2 = audit.pageUserOperations(query); assertEquals(5, page2.getList().size());
        Set<Long> ids = new HashSet<>();
        for (var row : page1.getList()) { assertEquals(target.toString(), row.getTargetId()); assertTrue(ids.add(row.getId())); }
        for (var row : page2.getList()) { assertEquals(first, row.getAdminUserId()); assertTrue(ids.add(row.getId())); }
        query.setTargetUserId(second); assertEquals(0L, audit.pageUserOperations(query).getTotal());
    }

    @Configuration @EnableTransactionManagement @MapperScan(basePackageClasses = UserMapper.class)
    static class Config {
        @Bean DataSource source() { return new DriverManagerDataSource(System.getenv("MYSQL_URL"), System.getenv("MYSQL_USERNAME"), System.getenv("MYSQL_PASSWORD")); }
        @Bean DataSourceTransactionManager transactionManager(DataSource source) { return new DataSourceTransactionManager(source); }
        @Bean TransactionTemplate transaction(DataSourceTransactionManager manager) { return new TransactionTemplate(manager); }
        @Bean JdbcTemplate jdbc(DataSource source) { return new JdbcTemplate(source); }
        @Bean ObjectMapper json() { return new ObjectMapper().findAndRegisterModules(); }
        @Bean SqlSessionFactory sqlSessionFactory(DataSource source) throws Exception {
            var configuration = new MybatisConfiguration(); configuration.setMapUnderscoreToCamelCase(true);
            configuration.setLogImpl(org.apache.ibatis.logging.nologging.NoLoggingImpl.class);
            var paging = new MybatisPlusInterceptor(); paging.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
            var factory = new MybatisSqlSessionFactoryBean(); factory.setDataSource(source); factory.setConfiguration(configuration); factory.setPlugins(paging);
            return factory.getObject();
        }
        @Bean IUserAccountStatusService accountStatus(UserMapper users) { return new UserAccountStatusServiceImpl(users); }
        @Bean IAdminOperationLogService audit(ObjectMapper json, IUserAccountStatusService status,
                                              DataSourceTransactionManager manager) {
            return spy(new AdminOperationLogServiceImpl(json, status, manager));
        }
        @Bean IAdminUserService users(UserMapper mapper, IUserAccountStatusService status, IAdminOperationLogService audit, ObjectMapper json) {
            return new AdminUserServiceImpl(mapper, status, audit, new CurrentUserProvider(), json);
        }
    }
}
