package com.catgal.server.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.catgal.server.domain.AdminRedisOperation;
import com.catgal.server.domain.po.AdminOperationLog;
import com.catgal.server.domain.query.admin.AdminContentOperationLogQuery;
import com.catgal.server.domain.query.admin.AdminOperationLogQuery;
import com.catgal.server.mapper.AdminOperationLogMapper;
import com.catgal.server.service.IUserAccountStatusService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.*;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.web.context.request.*;
import static com.catgal.common.constants.Constant.REQUEST_ID_HEADER;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AdminOperationLogServiceImplTest {
    private final AdminOperationLogMapper logs = mock(AdminOperationLogMapper.class);
    private final IUserAccountStatusService status = mock(IUserAccountStatusService.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final AdminOperationLogServiceImpl service = new AdminOperationLogServiceImpl(new ObjectMapper(), status, transactions);
    @BeforeEach void setup() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), AdminOperationLog.class);
        ReflectionTestUtils.setField(service, "baseMapper", logs);
        when(status.now()).thenReturn(LocalDateTime.parse("2026-11-16T21:00:00")); when(logs.insert(any(AdminOperationLog.class))).thenReturn(1);
    }
    @AfterEach void cleanup() { MDC.clear(); RequestContextHolder.resetRequestAttributes(); }
    @Test void secretFieldsAreRejectedBeforeAnyDatabaseWrite() {
        assertThrows(IllegalArgumentException.class, () -> service.appendUserChange(1L, "USER_BAN", 2L,
                Map.of("password", "isolated-secret"), Map.of("status", 0), "test"));
        verifyNoInteractions(logs);
    }
    @Test void traceAndActualPeerAreRecordedWithoutTrustingAForwardedHeader() {
        MDC.put(REQUEST_ID_HEADER, "owned-request");
        var request = new MockHttpServletRequest(); request.setRemoteAddr("127.0.0.1"); request.addHeader("X-Forwarded-For", "203.0.113.10");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        service.appendUserChange(1L, "USER_ROLE_CHANGE", 2L, Map.of("role", 0), Map.of("role", 1), null);
        var entry = ArgumentCaptor.forClass(AdminOperationLog.class); verify(logs).insert(entry.capture());
        assertEquals("owned-request", entry.getValue().getRequestId()); assertEquals("127.0.0.1", entry.getValue().getIpAddress());
        assertEquals("{\"role\":0}", entry.getValue().getBeforeData()); assertEquals(1, entry.getValue().getResult());
    }
    @Test void queryAlsoFiltersAccidentalSensitiveFieldsFromStoredSnapshots() {
        AdminOperationLog row = new AdminOperationLog(); row.setId(1L); row.setAdminUserId(2L); row.setTargetId("3");
        row.setCreateTime(LocalDateTime.parse("2026-11-16T21:00:00")); row.setOperation("USER_BAN");
        row.setBeforeData("{\"password\":\"isolated-secret\",\"role\":0}"); row.setAfterData("{\"phone\":\"private\",\"status\":0}");
        Page<AdminOperationLog> page = new Page<>(); page.setRecords(List.of(row)); page.setTotal(1);
        when(logs.selectPage(any(), any())).thenReturn(page);
        var view = service.pageUserOperations(new AdminOperationLogQuery()).getList().get(0);
        assertEquals(Map.of("role", 0), view.getBeforeData()); assertEquals(Map.of("status", 0), view.getAfterData());
    }
    @Test void userRevisionsAreReadWithOneBoundedBatchAndEmptyPagesAvoidSql() {
        assertTrue(service.userRevisions(List.of()).isEmpty());
        verify(logs, never()).userRevisions(anyCollection());
        when(logs.userRevisions(List.of("2", "3"))).thenReturn(List.of(new AdminOperationLogMapper.UserRevision("2", 17L)));
        assertEquals(Map.of(2L, 17L), service.userRevisions(List.of(2L, 3L)));
        verify(logs, times(1)).userRevisions(List.of("2", "3"));
    }

    @Test void contentSnapshotsKeepNullLegacyStatusAndLargeIdsWithoutCopyingText() throws Exception {
        service.appendContentStatusChange(1L, "comment", 2L, 9007199254740993L, null, 1);
        var entry = ArgumentCaptor.forClass(AdminOperationLog.class);
        verify(logs).insert(entry.capture());
        var row = entry.getValue();
        assertEquals("content", row.getModule());
        assertEquals("comment", row.getTargetType());
        assertEquals("COMMENT_RESTORE", row.getOperation());
        assertNull(row.getReason());
        var before = new ObjectMapper().readTree(row.getBeforeData());
        assertEquals(2, before.size());
        assertTrue(before.get("status").isNull());
        assertEquals("9007199254740993", before.get("gameId").textValue());
    }

    @Test void storedContentTextIsOmittedFromContentAuditResponses() {
        AdminOperationLog row = new AdminOperationLog();
        row.setTargetType("review");
        row.setCreateTime(LocalDateTime.parse("2026-11-19T12:00:00"));
        row.setBeforeData("{\"content\":\"isolated review text\",\"status\":1,\"gameId\":\"3\"}");
        row.setAfterData("{\"username\":\"private\",\"status\":0,\"gameId\":\"3\"}");
        row.setReason("isolated unexpected reason");
        Page<AdminOperationLog> page = new Page<>();
        page.setRecords(List.of(row));
        page.setTotal(1);
        when(logs.selectPage(any(), any())).thenReturn(page);
        var view = service.pageContentOperations(new AdminContentOperationLogQuery()).getList().get(0);
        assertEquals("review", view.getTargetType());
        assertEquals(Map.of("gameId", "3", "status", 1), view.getBeforeData());
        assertEquals(Map.of("gameId", "3", "status", 0), view.getAfterData());
        assertNull(view.getReason());
    }

    @Test void invalidTimeWindowIsRejectedBeforeTheQuery() {
        var query = new AdminOperationLogQuery(); query.setFrom(java.time.OffsetDateTime.parse("2026-11-17T00:00:00+08:00"));
        query.setTo(java.time.OffsetDateTime.parse("2026-11-16T00:00:00+08:00"));
        assertThrows(com.catgal.common.exception.BusinessException.class, () -> service.pageUserOperations(query)); verifyNoInteractions(logs);
    }

    @Test void redisOperationIsNotRunIfTheAuditAttemptCannotCommit() {
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(logs.insert(any(AdminOperationLog.class))).thenAnswer(call -> {
            call.<AdminOperationLog>getArgument(0).setId(8L);
            return 1;
        });
        var failure = new IllegalStateException("isolated audit commit error");
        doThrow(failure).when(transactions).commit(any());
        Runnable action = mock(Runnable.class);
        assertSame(failure, assertThrows(IllegalStateException.class, () -> service.auditRedisMaintenance(
                1L, AdminRedisOperation.GAME_STATS_REPAIR, 2L, () -> { action.run(); return null; })));
        verifyNoInteractions(action);
    }

    @Test void redisFinishUpdateFailurePreservesTheOriginalOperationResult() {
        when(transactions.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus());
        when(logs.insert(any(AdminOperationLog.class))).thenAnswer(call -> {
            AdminOperationLog row = call.getArgument(0);
            row.setId(8L);
            assertEquals("REDIS_OUTCOME_UNCONFIRMED", row.getErrorMessage());
            return 1;
        });
        when(logs.update(isNull(), any(Wrapper.class))).thenThrow(new IllegalStateException("isolated update error"));
        assertEquals("completed", service.auditRedisMaintenance(1L, AdminRedisOperation.RESOURCE_DOWNLOAD_REPAIR,
                2L, () -> "completed"));
        verify(transactions).commit(any());
        verify(transactions).rollback(any());
    }
}
