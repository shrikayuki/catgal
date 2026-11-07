package com.catgal.server.service.userhome;

import com.catgal.common.domain.dto.PageDTO;
import com.catgal.common.domain.query.UserHomeQueryDTO;
import com.catgal.common.enums.UserHomeTabEnum;
import com.catgal.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserHomeTabQueryRegistryTest {

    @Test
    void routesQueryToMatchingStrategy() {
        UserHomeTabQueryStrategy strategy = mock(UserHomeTabQueryStrategy.class);
        when(strategy.supports()).thenReturn(UserHomeTabEnum.REVIEW);
        PageDTO<?> expected = mock(PageDTO.class);
        UserHomeQueryDTO query = new UserHomeQueryDTO();
        query.setTab(UserHomeTabEnum.REVIEW.getCode());
        doReturn(expected).when(strategy).query(query, 8L, false);

        UserHomeTabQueryRegistry registry = new UserHomeTabQueryRegistry(List.of(strategy));

        assertSame(expected, registry.query(query, 8L, false));
        verify(strategy).query(query, 8L, false);
    }

    @Test
    void rejectsInvalidTabInsteadOfSilentlyFallingBackToComment() {
        UserHomeTabQueryRegistry registry = new UserHomeTabQueryRegistry(List.of());
        UserHomeQueryDTO query = new UserHomeQueryDTO();
        query.setTab(99);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> registry.query(query, 8L, false));

        assertEquals(400, exception.getHttpStatus());
    }

    @Test
    void rejectsDuplicateStrategiesAtStartup() {
        UserHomeTabQueryStrategy first = mock(UserHomeTabQueryStrategy.class);
        UserHomeTabQueryStrategy second = mock(UserHomeTabQueryStrategy.class);
        when(first.supports()).thenReturn(UserHomeTabEnum.COMMENT);
        when(second.supports()).thenReturn(UserHomeTabEnum.COMMENT);

        assertThrows(IllegalStateException.class,
                () -> new UserHomeTabQueryRegistry(List.of(first, second)));
    }
}
