package com.catgal.server.mvc;

import com.catgal.common.context.UserContext;
import com.catgal.common.exception.BusinessException;
import com.catgal.common.utils.JwtUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LoginInterceptorTest {

    private final JwtUtils jwtUtils = mock(JwtUtils.class);
    private final LoginInterceptor interceptor = new LoginInterceptor(jwtUtils);

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
        when(jwtUtils.validateToken("expired")).thenReturn(false);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> preHandle(request));

        assertEquals(401, exception.getHttpStatus());
    }

    @Test
    void populatesAndClearsUserContextForValidToken() {
        MockHttpServletRequest request = bearerRequest("valid-token");
        when(jwtUtils.validateToken("valid-token")).thenReturn(true);
        when(jwtUtils.getUserId("valid-token")).thenReturn(7L);
        when(jwtUtils.getUsername("valid-token")).thenReturn("shinku");
        when(jwtUtils.getRole("valid-token")).thenReturn(2);

        assertTrue(preHandle(request));
        assertEquals(7L, UserContext.getUserId());
        assertEquals(2, UserContext.getRole());

        interceptor.afterCompletion(request, new MockHttpServletResponse(), new Object(), null);
        assertFalse(UserContext.isLogin());
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
