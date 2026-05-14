package com.catgal.common.domain;

import com.catgal.common.constants.Constant;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.slf4j.MDC;

import static com.catgal.common.constants.ErrorInfo.Code.FAILED;
import static com.catgal.common.constants.ErrorInfo.Code.SUCCESS;
import static com.catgal.common.constants.ErrorInfo.Msg.OK;

@Data
@NoArgsConstructor
@Schema(description = "通用响应结果")
public class R<T> {

    @Schema(description = "业务状态码，200-成功，其它-失败", example = "200")
    private int code;

    @Schema(description = "响应消息", example = "success")
    private String msg;

    @Schema(description = "响应数据")
    private T data;

    @Schema(description = "请求追踪ID", example = "1af123c11412e")
    private String requestId;

    /**
     * 成功响应（无数据）
     */
    public static R<Void> ok() {
        return new R<>(SUCCESS, OK, null);
    }

    /**
     * 成功响应（有数据）
     */
    public static <T> R<T> ok(T data) {
        return new R<>(SUCCESS, OK, data);
    }

    /**
     * 成功响应（自定义消息）
     */
    public static <T> R<T> ok(String msg, T data) {
        return new R<>(SUCCESS, msg, data);
    }

    /**
     * 失败响应（默认消息）
     */
    public static <T> R<T> error() {
        return new R<>(FAILED, "操作失败", null);
    }

    /**
     * 失败响应（自定义消息）
     */
    public static <T> R<T> error(String msg) {
        return new R<>(FAILED, msg, null);
    }

    /**
     * 失败响应（自定义状态码和消息）
     */
    public static <T> R<T> error(int code, String msg) {
        return new R<>(code, msg, null);
    }

    /**
     * 构造方法
     */
    public R(int code, String msg, T data) {
        this.code = code;
        this.msg = msg;
        this.data = data;
        this.requestId = MDC.get(Constant.REQUEST_ID_HEADER);
    }

    /**
     * 判断是否成功
     */
    public boolean success() {
        return code == SUCCESS;
    }

    /**
     * 设置请求ID（链式调用）
     */
    public R<T> requestId(String requestId) {
        this.requestId = requestId;
        return this;
    }
}