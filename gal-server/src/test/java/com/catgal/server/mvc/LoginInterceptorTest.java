package com.catgal.server.mvc;

import com.catgal.common.context.UserContext;
import com.catgal.common.exception.BusinessException;
import com.catgal.common.utils.JwtUtils;
import com.catgal.server.domain.po.User;
import com.catgal.server.mapper.RevokedAuthTokenMapper;
import com.catgal.server.service.IUserAccountStatusService;
import com.catgal.server.service.impl.AuthSessionServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.any;

class LoginInterceptorTest {

    private final JwtUtils jwtUtils = new JwtUtils(Base64.getEncoder().encodeToString(
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.US_ASCII)), 300000L);
    private final IUserAccountStatusService accountStatus = mock(IUserAccountStatusService.class);
    private final RevokedAuthTokenMapper revoked = mock(RevokedAuthTokenMapper.class);
    private final AuthSessionServiceImpl sessions = new AuthSessionServiceImpl(jwtUtils, accountStatus, revoked);
    private final LoginInterceptor interceptor = new LoginInterceptor(sessions);

    @AfterEach
    void clearContext() {
        UserContext.clear();
    }

    @Test
    void allowsAnonymousRequestWithoutAuthorizationHeader() {
        assertTrue(preHandle(new MockHttpServletRequest()));
        assertFalse(UserContext.isLogin());
    }

    @Test
    void clearsAnyStaleContextBeforeAnonymousRequest() {
        UserContext.setUser(com.catgal.common.context.UserInfo.builder().userId(99L).build());

        assertTrue(preHandle(new MockHttpServletRequest()));

        assertFalse(UserContext.isLogin());
    }

    @Test
    void rejectsMalformedAuthorizationHeader() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "token-value");

        BusinessException exception = assertThrows(BusinessException.class,
                () -> preHandle(request));

        assertEquals(401, exception.getHttpStatus());
    }

    @Test
    void rejectsInvalidBearerToken() {
        MockHttpServletRequest request = bearerRequest("expired");

        BusinessException exception = assertThrows(BusinessException.class,
                () -> preHandle(request));

        assertEquals(401, exception.getHttpStatus());
    }

    @Test
    void populatesAndClearsUserContextForValidToken() {
        MockHttpServletRequest request = bearerRequest(legacyToken());
        when(accountStatus.requireActiveUser(7L)).thenReturn(account(2, 0L));

        assertTrue(preHandle(request));
        assertEquals(7L, UserContext.getUserId());
        assertEquals(2, UserContext.getRole());

        interceptor.afterCompletion(request, new MockHttpServletResponse(), new Object(), null);
        assertFalse(UserContext.isLogin());
    }

    @Test
    void bannedAccountRejectsAnOtherwiseValidOldToken() {
        when(accountStatus.requireActiveUser(7L)).thenThrow(new BusinessException(40301, 403, "账号已被封禁"));
        String token = legacyToken();
        BusinessException failure = assertThrows(BusinessException.class, () -> preHandle(bearerRequest(token)));
        assertEquals(40301, failure.getCode()); assertFalse(UserContext.isLogin());
    }

    @Test
    void databaseOutageIsNotMisreportedAsAnInvalidToken() {
        when(accountStatus.requireActiveUser(7L)).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("isolated database failure"));
        String token = legacyToken();
        assertThrows(org.springframework.dao.DataAccessResourceFailureException.class, () -> preHandle(bearerRequest(token)));
        assertFalse(UserContext.isLogin());
    }

    @Test
    void databaseRoleOverridesAStaleAdministratorClaim() {
        when(accountStatus.requireActiveUser(7L)).thenReturn(account(0, 0L));
        assertTrue(preHandle(bearerRequest(legacyToken())));
        assertEquals(0, UserContext.getRole());
    }

    @Test
    void passwordVersionInvalidatesLegacyAndVersionedCredentials() {
        String legacy = legacyToken();
        String before = jwtUtils.generateToken(7L, "member", 0, 0L);
        String current = jwtUtils.generateToken(7L, "member", 0, 1L);
        when(accountStatus.requireActiveUser(7L)).thenReturn(account(0, 1L));
        for (String token : new String[]{legacy, before}) {
            assertEquals(401, assertThrows(BusinessException.class, () -> preHandle(bearerRequest(token))).getHttpStatus());
        }
        assertTrue(preHandle(bearerRequest(current)));
        assertEquals(1L, UserContext.getUser().getAuthVersion());
    }

    @Test
    void logoutRevokesOnlyTheCredentialPresented() {
        when(accountStatus.requireActiveUser(7L)).thenReturn(account(0, 0L));
        String first = jwtUtils.generateToken(7L, "member", 0, 0L);
        String second = jwtUtils.generateToken(7L, "member", 0, 0L);
        assertTrue(preHandle(bearerRequest(first)));
        sessions.logout(first);
        var fingerprint = ArgumentCaptor.forClass(String.class);
        verify(revoked).revoke(fingerprint.capture(), any(LocalDateTime.class));
        assertEquals(64, fingerprint.getValue().length());
        when(revoked.isRevoked(fingerprint.getValue())).thenReturn(true);
        assertEquals(401, assertThrows(BusinessException.class, () -> preHandle(bearerRequest(first))).getHttpStatus());
        assertTrue(preHandle(bearerRequest(second)));
    }

    private String legacyToken() {
        return jwtUtils.generateToken(7L, "member", 2);
    }

    private User account(int role, long authVersion) {
        User current = new User();
        current.setId(7L);
        current.setUsername("member");
        current.setRole(role);
        current.setStatus(1);
        current.setAuthVersion(authVersion);
        return current;
    }

    private MockHttpServletRequest bearerRequest(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        return request;
    }

    private boolean preHandle(MockHttpServletRequest request) {
        return interceptor.preHandle(request, new MockHttpServletResponse(), new Object());
    }
}
