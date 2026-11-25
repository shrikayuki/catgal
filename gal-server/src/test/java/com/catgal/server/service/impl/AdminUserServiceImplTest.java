package com.catgal.server.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.catgal.common.context.*;
import com.catgal.common.exception.BusinessException;
import com.catgal.server.domain.dto.admin.AdminUserStatusUpdateDTO;
import com.catgal.server.domain.po.User;
import com.catgal.server.mapper.UserMapper;
import com.catgal.server.security.CurrentUserProvider;
import com.catgal.server.service.IAdminOperationLogService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AdminUserServiceImplTest {
    private final UserMapper users = mock(UserMapper.class);
    private final IAdminOperationLogService audit = mock(IAdminOperationLogService.class);
    private final UserAccountStatusServiceImpl status = new UserAccountStatusServiceImpl(users,
            Clock.fixed(Instant.parse("2026-11-16T13:00:00Z"), ZoneOffset.UTC));
    private final AdminUserServiceImpl service = new AdminUserServiceImpl(users, status, audit, new CurrentUserProvider(), new ObjectMapper());
    private User actor;
    private User target;

    private User user(long id, int role) { User user = new User(); user.setId(id); user.setUsername("isolated-" + id); user.setRole(role); user.setStatus(1); return user; }
    @BeforeEach void setup() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), User.class);
        actor = user(1L, 2); target = user(2L, 0);
        UserContext.setUser(UserInfo.builder().userId(1L).role(2).build());
        when(users.selectAdminIdsForUpdate()).thenReturn(List.of(1L));
        when(users.selectForUpdate(1L)).thenReturn(actor); when(users.selectForUpdate(2L)).thenReturn(target);
        when(users.selectById(2L)).thenReturn(target); when(users.update(any(), any())).thenReturn(1);
    }
    @AfterEach void cleanup() { UserContext.clear(); }
    private AdminUserStatusUpdateDTO request(int value) {
        var dto = new AdminUserStatusUpdateDTO(); dto.setStatus(value); dto.setReason("isolated reason");
        dto.setStateVersion(service.getUser(2L).getStateVersion()); return dto;
    }
    @Test void banUsesSharedLockOrderAndAppendsWhitelistedBeforeAndAfterSnapshots() {
        var dto = request(0); dto.setBannedUntil(OffsetDateTime.parse("2026-11-17T21:00:00+08:00"));
        var view = service.updateStatus(2L, dto);
        assertEquals(0, view.getStatus()); assertEquals("isolated reason", target.getBanReason());
        var order = inOrder(users, audit);
        order.verify(users).selectAdminIdsForUpdate(); order.verify(users).selectForUpdate(1L); order.verify(users).selectForUpdate(2L);
        order.verify(users).update(any(), any());
        order.verify(audit).appendUserChange(eq(1L), eq("USER_BAN"), eq(2L),
                argThat(before -> before.get("status").equals(1) && !before.containsKey("password") && !before.containsKey("phone")),
                argThat(after -> after.get("status").equals(0)), eq("isolated reason"));
    }
    @Test void unbanExplicitlyClearsReasonAndDeadline() {
        target.setStatus(0); target.setBanReason("old"); target.setBannedUntil(status.now().plusDays(1));
        assertEquals(1, service.updateStatus(2L, request(1)).getStatus());
        assertNull(target.getBanReason()); assertNull(target.getBannedUntil());
        verify(users).update(isNull(), argThat(update -> update.getSqlSet().contains("ban_reason") && update.getSqlSet().contains("banned_until")));
        verify(audit).appendUserChange(eq(1L), eq("USER_UNBAN"), eq(2L), anyMap(), anyMap(), eq("isolated reason"));
    }
    @Test void staleStateVersionCannotOverwriteOrCreateAnAuditEntry() {
        var dto = request(0); target.setRole(1);
        assertEquals(409, assertThrows(BusinessException.class, () -> service.updateStatus(2L, dto)).getHttpStatus());
        verify(users, never()).update(any(), any());
        verify(audit, never()).appendUserChange(anyLong(), anyString(), anyLong(), anyMap(), anyMap(), any());
    }
    @Test void returningToTheSameFieldsStillRejectsAnOlderAuditRevision() {
        when(audit.userRevisions(anyCollection())).thenReturn(Map.of(2L, 10L));
        var input = request(0);
        when(audit.userRevisions(anyCollection())).thenReturn(Map.of(2L, 12L));
        assertEquals(409, assertThrows(BusinessException.class, () -> service.updateStatus(2L, input)).getHttpStatus());
        verify(users, never()).update(any(), any());
        verify(audit, never()).appendUserChange(anyLong(), anyString(), anyLong(), anyMap(), anyMap(), any());
    }

    @Test void legacyRolePayloadStillProducesAnAuditEntry() {
        assertEquals(1, service.updateRole(2L, 1, null).getRole());
        verify(audit).appendUserChange(eq(1L), eq("USER_ROLE_CHANGE"), eq(2L), anyMap(), anyMap(), isNull());
    }
    @Test void matchingRoleIsANoOp() {
        assertEquals(0, service.updateRole(2L, 0, service.getUser(2L).getStateVersion()).getRole());
        verify(users, never()).update(any(), any());
        verify(audit, never()).appendUserChange(anyLong(), anyString(), anyLong(), anyMap(), anyMap(), any());
    }
    @Test void selfDemotionAndSelfBanAreRejected() {
        assertEquals(409, assertThrows(BusinessException.class, () -> service.updateRole(1L, 0, null)).getHttpStatus());
        when(users.selectById(1L)).thenReturn(actor);
        var dto = request(0); dto.setStateVersion(service.getUser(1L).getStateVersion());
        assertEquals(409, assertThrows(BusinessException.class, () -> service.updateStatus(1L, dto)).getHttpStatus());
        verify(users, never()).update(any(), any());
        verify(audit, never()).appendUserChange(anyLong(), anyString(), anyLong(), anyMap(), anyMap(), any());
    }
    @Test void actorIsRecheckedAfterItsRowsHaveBeenLocked() {
        actor.setStatus(0);
        assertEquals(40301, assertThrows(BusinessException.class, () -> service.updateRole(2L, 1, null)).getCode());
        actor.setStatus(1); actor.setRole(0);
        assertEquals(403, assertThrows(BusinessException.class, () -> service.updateRole(2L, 1, null)).getHttpStatus());
        verifyNoInteractions(audit);
    }
    @Test void pastDeadlineAndDeadlineOnUnbanAreRejected() {
        var dto = request(0); dto.setBannedUntil(OffsetDateTime.parse("2026-11-16T21:00:00+08:00"));
        assertEquals(400, assertThrows(BusinessException.class, () -> service.updateStatus(2L, dto)).getHttpStatus());
        dto.setStatus(1);
        assertEquals(400, assertThrows(BusinessException.class, () -> service.updateStatus(2L, dto)).getHttpStatus());
        verify(users, never()).update(any(), any());
        verify(audit, never()).appendUserChange(anyLong(), anyString(), anyLong(), anyMap(), anyMap(), any());
    }
    @Test void auditFailureIsPropagatedSoTheTransactionCanRollBack() {
        doThrow(new IllegalStateException("isolated audit failure")).when(audit).appendUserChange(anyLong(), anyString(), anyLong(), anyMap(), anyMap(), any());
        assertThrows(IllegalStateException.class, () -> service.updateStatus(2L, request(0)));
    }
    @Test void aMissingTargetCannotBeMutated() {
        when(users.selectForUpdate(2L)).thenReturn(null);
        assertEquals(404, assertThrows(BusinessException.class, () -> service.updateRole(2L, 1, null)).getHttpStatus());
        verifyNoInteractions(audit);
    }
}
