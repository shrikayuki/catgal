/*
 * 阅读说明
 * 业务失败的跨层载体，同时保存业务码与 HTTP 状态。
 * Service 抛出后由 GlobalExceptionHandler 统一转换为客户端可识别的响应。
 */
package com.catgal.common.exception;

import lombok.Getter;

import static com.catgal.common.constants.ErrorInfo.Code.*;

/**
 * 可预期的业务异常。
 *
 * <p>业务层只描述错误码、HTTP 状态和消息，不依赖 Web 层的具体响应实现。</p>
 */
@Getter
public class BusinessException extends RuntimeException {

    private final int code;
    private final int httpStatus;

    public BusinessException(String message) {
        this(BAD_REQUEST, BAD_REQUEST, message);
    }

    public BusinessException(int code, String message) {
        this(code, BAD_REQUEST, message);
    }

    public BusinessException(int code, int httpStatus, String message) {
        super(message);
        this.code = code;
        this.httpStatus = httpStatus;
    }

    public BusinessException(int code, int httpStatus, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.httpStatus = httpStatus;
    }

    public static BusinessException badRequest(String message) {
        return new BusinessException(BAD_REQUEST, BAD_REQUEST, message);
    }

    public static BusinessException unauthorized(String message) {
        return new BusinessException(UNAUTHORIZED, UNAUTHORIZED, message);
    }

    public static BusinessException forbidden(String message) {
        return new BusinessException(FORBIDDEN, FORBIDDEN, message);
    }

    public static BusinessException notFound(String message) {
        return new BusinessException(NOT_FOUND, NOT_FOUND, message);
    }

    public static BusinessException conflict(String message) {
        return new BusinessException(CONFLICT, CONFLICT, message);
    }

    public static BusinessException internalError(String message) {
        return new BusinessException(SERVER_ERROR, SERVER_ERROR, message);
    }

    public static BusinessException internalError(String message, Throwable cause) {
        return new BusinessException(SERVER_ERROR, SERVER_ERROR, message, cause);
    }
}
