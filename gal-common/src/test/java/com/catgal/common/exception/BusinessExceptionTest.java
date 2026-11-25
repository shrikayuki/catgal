package com.catgal.common.exception;

import org.junit.jupiter.api.Test;

import static com.catgal.common.constants.ErrorInfo.Code.BAD_REQUEST;
import static com.catgal.common.constants.ErrorInfo.Code.CONFLICT;
import static com.catgal.common.constants.ErrorInfo.Code.FORBIDDEN;
import static com.catgal.common.constants.ErrorInfo.Code.NOT_FOUND;
import static com.catgal.common.constants.ErrorInfo.Code.SERVER_ERROR;
import static com.catgal.common.constants.ErrorInfo.Code.UNAUTHORIZED;
import static org.junit.jupiter.api.Assertions.assertEquals;

class BusinessExceptionTest {

    @Test
    void messageConstructorUsesBadRequestDefaults() {
        BusinessException exception = new BusinessException("参数错误");

        assertEquals(BAD_REQUEST, exception.getCode());
        assertEquals(BAD_REQUEST, exception.getHttpStatus());
        assertEquals("参数错误", exception.getMessage());
    }

    @Test
    void explicitCodeAndStatusArePreserved() {
        BusinessException exception = new BusinessException(CONFLICT, CONFLICT, "数据冲突");

        assertEquals(CONFLICT, exception.getCode());
        assertEquals(CONFLICT, exception.getHttpStatus());
        assertEquals("数据冲突", exception.getMessage());
    }

    @Test
    void factoryMethodsUseMatchingBusinessAndHttpCodes() {
        assertEquals(BAD_REQUEST, BusinessException.badRequest("bad").getHttpStatus());
        assertEquals(UNAUTHORIZED, BusinessException.unauthorized("unauthorized").getHttpStatus());
        assertEquals(FORBIDDEN, BusinessException.forbidden("forbidden").getHttpStatus());
        assertEquals(NOT_FOUND, BusinessException.notFound("not found").getHttpStatus());
        assertEquals(CONFLICT, BusinessException.conflict("conflict").getHttpStatus());
        assertEquals(SERVER_ERROR, BusinessException.internalError("error").getHttpStatus());
    }
}
