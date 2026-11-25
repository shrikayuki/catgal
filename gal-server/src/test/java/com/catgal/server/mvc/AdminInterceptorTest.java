package com.catgal.server.mvc;

import com.catgal.common.context.UserContext;
import com.catgal.common.context.UserInfo;
import com.catgal.common.exception.BusinessException;
import com.catgal.server.domain.po.User;
import com.catgal.server.mapper.UserMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static com.catgal.common.constants.UserRoleConstant.ADMIN;
import static com.catgal.common.constants.UserRoleConstant.USER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdminInterceptorTest {

    private final UserMapper userMapper = mock(UserMapper.class);
    private final AdminInterceptor interceptor = new AdminInterceptor(userMapper);

    @AfterEach
    void clearContext() {
        UserContext.clear();
    }

    @Test
    void rejectsAnonymousUser() {
        BusinessException exception = assertThrows(BusinessException.class, this::preHandle);
        assertEquals(401, exception.getHttpStatus());
    }

    @Test
    void rejectsNonAdminUser() {
        UserContext.setUser(UserInfo.builder().userId(1L).role(USER).build());
        when(userMapper.selectById(1L)).thenReturn(user(1L, USER));
        BusinessException exception = assertThrows(BusinessException.class, this::preHandle);
        assertEquals(403, exception.getHttpStatus());
    }

    @Test
    void allowsAdminUser() {
        UserContext.setUser(UserInfo.builder().userId(2L).role(ADMIN).build());
        when(userMapper.selectById(2L)).thenReturn(user(2L, ADMIN));
        assertTrue(preHandle());
    }

    @Test
    void rejectsStaleAdminTokenAfterDatabaseDemotion() {
        UserContext.setUser(UserInfo.builder().userId(3L).role(ADMIN).build());
        when(userMapper.selectById(3L)).thenReturn(user(3L, USER));
        BusinessException exception = assertThrows(BusinessException.class, this::preHandle);
        assertEquals(403, exception.getHttpStatus());
    }

    private User user(Long id, Integer role) {
        User user = new User();
        user.setId(id);
        user.setRole(role);
        return user;
    }

    private boolean preHandle() {
        return interceptor.preHandle(new MockHttpServletRequest(),
                new MockHttpServletResponse(), new Object());
    }
}
