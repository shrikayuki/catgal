package com.catgal.server.security;

import com.catgal.common.context.UserContext;
import com.catgal.common.context.UserInfo;
import com.catgal.common.exception.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CurrentUserProviderTest {

    private final CurrentUserProvider provider = new CurrentUserProvider();

    @AfterEach
    void clearContext() {
        UserContext.clear();
    }

    @Test
    void rejectsAnonymousUser() {
        BusinessException exception = assertThrows(BusinessException.class, provider::requireUserId);
        assertEquals(401, exception.getHttpStatus());
    }

    @Test
    void rejectsUpdatingAnotherUser() {
        UserContext.setUser(UserInfo.builder().userId(10L).build());

        BusinessException exception = assertThrows(BusinessException.class,
                () -> provider.requireSelf(11L));

        assertEquals(403, exception.getHttpStatus());
    }

    @Test
    void returnsCurrentUserForSelfOperation() {
        UserContext.setUser(UserInfo.builder().userId(10L).build());
        assertEquals(10L, provider.requireSelf(10L));
    }
}
