/*
 * 阅读说明
 * 将参数、权限、业务和未处理异常转换为统一响应。
 * 这里决定 HTTP 状态与安全错误文案，原始异常留在受控日志中诊断。
 */
package com.catgal.server.exception;

import com.catgal.common.domain.R;
import com.catgal.common.exception.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import static com.catgal.common.constants.ErrorInfo.Code.BAD_REQUEST;
import static com.catgal.common.constants.ErrorInfo.Code.NOT_FOUND;
import static com.catgal.common.constants.ErrorInfo.Code.SERVER_ERROR;
import static com.catgal.common.constants.ErrorInfo.Msg.REQUEST_PARAM_ILLEGAL;
import static com.catgal.common.constants.ErrorInfo.Msg.SERVER_INTER_ERROR;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<R<Void>> handleBusinessException(BusinessException exception,
                                                            HttpServletRequest request) {
        if (exception.getHttpStatus() >= 500) {
            log.error("业务执行失败: path={}, code={}, message={}",
                    request.getRequestURI(), exception.getCode(), exception.getMessage(), exception);
        } else {
            log.warn("业务异常: path={}, code={}, message={}",
                    request.getRequestURI(), exception.getCode(), exception.getMessage());
        }
        return ResponseEntity.status(exception.getHttpStatus())
                .contentType(MediaType.APPLICATION_JSON)
                .body(R.error(exception.getCode(), exception.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<R<Void>> handleMethodArgumentNotValid(MethodArgumentNotValidException exception) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .orElse(REQUEST_PARAM_ILLEGAL);
        return badRequest(message);
    }

    @ExceptionHandler(BindException.class)
    public ResponseEntity<R<Void>> handleBindException(BindException exception) {
        String message = exception.getBindingResult().getAllErrors().stream()
                .map(DefaultMessageSourceResolvable::getDefaultMessage)
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElse(REQUEST_PARAM_ILLEGAL);
        return badRequest(message);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<R<Void>> handleConstraintViolation(ConstraintViolationException exception) {
        String message = exception.getConstraintViolations().stream()
                .findFirst()
                .map(violation -> violation.getMessage())
                .orElse(REQUEST_PARAM_ILLEGAL);
        return badRequest(message);
    }

    @ExceptionHandler({
            MissingServletRequestParameterException.class,
            MissingServletRequestPartException.class,
            MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class
    })
    public ResponseEntity<R<Void>> handleBadRequest(Exception exception) {
        log.debug("请求参数解析失败: {}", exception.getClass().getSimpleName());
        return badRequest(REQUEST_PARAM_ILLEGAL);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<R<Void>> handleUploadTooLarge(MaxUploadSizeExceededException exception) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(R.error(HttpStatus.PAYLOAD_TOO_LARGE.value(), "上传文件或请求体过大"));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<R<Void>> handleNoResourceFound(NoResourceFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_JSON)
                .body(R.error(NOT_FOUND, "请求地址不存在"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<R<Void>> handleUnexpectedException(Exception exception,
                                                              HttpServletRequest request) {
        log.error("未处理异常: path={}", request.getRequestURI(), exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(MediaType.APPLICATION_JSON)
                .body(R.error(SERVER_ERROR, SERVER_INTER_ERROR));
    }

    private ResponseEntity<R<Void>> badRequest(String message) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON)
                .body(R.error(BAD_REQUEST, message));
    }
}
