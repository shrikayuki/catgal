package com.catgal.server.exception;

import com.catgal.common.domain.R;
import com.catgal.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import static com.catgal.common.constants.ErrorInfo.Code.CONFLICT;
import static com.catgal.common.constants.ErrorInfo.Code.SERVER_ERROR;
import static com.catgal.common.constants.ErrorInfo.Msg.SERVER_INTER_ERROR;
import static org.junit.jupiter.api.Assertions.assertEquals;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void businessExceptionKeepsStatusCodeAndMessage() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/test");

        ResponseEntity<R<Void>> response = handler.handleBusinessException(
                new BusinessException(CONFLICT, CONFLICT, "数据冲突"), request);

        assertEquals(CONFLICT, response.getStatusCode().value());
        assertEquals(CONFLICT, response.getBody().getCode());
        assertEquals("数据冲突", response.getBody().getMsg());
    }

    @Test
    void unexpectedExceptionDoesNotExposeInternalMessage() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/test");

        ResponseEntity<R<Void>> response = handler.handleUnexpectedException(
                new IllegalStateException("sensitive detail"), request);

        assertEquals(SERVER_ERROR, response.getStatusCode().value());
        assertEquals(SERVER_ERROR, response.getBody().getCode());
        assertEquals(SERVER_INTER_ERROR, response.getBody().getMsg());
    }
}
