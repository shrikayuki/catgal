package com.catgal.server.mvc;

import com.catgal.common.context.UserContext;
import com.catgal.common.context.UserInfo;
import com.catgal.common.exception.BusinessException;
import com.catgal.server.mvc.annotation.LoginRequired;
import com.catgal.server.security.CurrentUserProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoginRequiredInterceptorTest {

    private final LoginRequiredInterceptor interceptor =
            new LoginRequiredInterceptor(new CurrentUserProvider());
    private final TestController controller = new TestController();

    @AfterEach
    void clearContext() {
        UserContext.clear();
    }

    @Test
    void allowsAnonymousAccessToUnannotatedHandler() throws NoSuchMethodException {
        assertTrue(preHandle("publicEndpoint"));
    }

    @Test
    void rejectsAnonymousAccessToAnnotatedHandler() throws NoSuchMethodException {
        BusinessException exception = assertThrows(BusinessException.class,
                () -> preHandle("protectedEndpoint"));
        assertEquals(401, exception.getHttpStatus());
    }

    @Test
    void allowsLoggedInAccessToAnnotatedHandler() throws NoSuchMethodException {
        UserContext.setUser(UserInfo.builder().userId(1L).build());
        assertTrue(preHandle("protectedEndpoint"));
    }

    private boolean preHandle(String methodName) throws NoSuchMethodException {
        HandlerMethod handler = new HandlerMethod(controller,
                TestController.class.getDeclaredMethod(methodName));
        return interceptor.preHandle(new MockHttpServletRequest(),
                new MockHttpServletResponse(), handler);
    }

    static class TestController {
        void publicEndpoint() {
        }

        @LoginRequired
        void protectedEndpoint() {
        }
    }
}
