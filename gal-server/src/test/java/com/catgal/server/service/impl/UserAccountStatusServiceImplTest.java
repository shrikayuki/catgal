package com.catgal.server.service.impl;

import com.catgal.common.exception.BusinessException;
import com.catgal.server.domain.po.User;
import com.catgal.server.mapper.UserMapper;
import java.time.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserAccountStatusServiceImplTest {
    private final UserMapper users = mock(UserMapper.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-16T13:00:00Z"), ZoneOffset.UTC);
    private final UserAccountStatusServiceImpl service = new UserAccountStatusServiceImpl(users, clock);
    private User user(Integer status, LocalDateTime until) { User user = new User(); user.setStatus(status); user.setBannedUntil(until); return user; }

    @Test void normalAccountIgnoresHistoricalBanMetadata() {
        User user = user(1, service.now().plusDays(1));
        assertDoesNotThrow(() -> service.requireActive(user)); assertEquals(1, service.effectiveStatus(user));
    }
    @Test void permanentBanUsesSpecificCodeWithoutLeakingItsReason() {
        User user = user(0, null); user.setBanReason("isolated private reason");
        BusinessException failure = assertThrows(BusinessException.class, () -> service.requireActive(user));
        assertEquals(403, failure.getHttpStatus()); assertEquals(40301, failure.getCode());
        assertFalse(failure.getMessage().contains(user.getBanReason()));
    }
    @Test void futureBanIsRejected() { assertThrows(BusinessException.class, () -> service.requireActive(user(0, service.now().plusSeconds(1)))); }
    @Test void exactDeadlineAndPastDeadlineRestoreAccessWithoutUpdatingTheDatabase() {
        for (LocalDateTime until : new LocalDateTime[]{service.now(), service.now().minusSeconds(1)}) {
            User user = user(0, until); assertTrue(service.isBanExpired(user)); assertEquals(1, service.effectiveStatus(user));
            assertDoesNotThrow(() -> service.requireActive(user)); assertEquals(0, user.getStatus());
        }
        verifyNoInteractions(users);
    }
    @Test void invalidStatusCannotObtainAccess() {
        for (Integer status : new Integer[]{null, -1, 2}) assertThrows(BusinessException.class, () -> service.requireActive(user(status, null)));
    }
    @Test void missingDatabaseUserInvalidatesItsOldToken() {
        when(users.selectAccountState(7L)).thenReturn(null);
        assertEquals(401, assertThrows(BusinessException.class, () -> service.requireActiveUser(7L)).getHttpStatus());
    }
    @Test void invalidIdentityDoesNotReadTheDatabase() {
        for (Long id : new Long[]{null, 0L, -1L}) assertThrows(BusinessException.class, () -> service.requireActiveUser(id));
        verifyNoInteractions(users);
    }
    @Test void businessTimeIsExplicitlyShanghai() { assertEquals(LocalDateTime.parse("2026-11-16T21:00:00"), service.now()); }
}
