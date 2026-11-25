/*
 * 阅读说明
 * 统一错误码与错误文本目录，供全局异常响应使用。
 * HTTP 状态与业务码分别表达传输结果和具体业务失败原因。
 */
package com.catgal.common.constants;

public interface ErrorInfo {

    interface Msg {
        String OK = "OK";
        String INVALID_VERIFY_CODE = "验证码错误";


        String SERVER_INTER_ERROR = "服务器内部错误";

        String DB_SAVE_EXCEPTION = "数据新增失败";
        String DB_DELETE_EXCEPTION = "数据删除失败";
        String DB_BATCH_DELETE_EXCEPTION = "数据批量删除失败";
        String DB_UPDATE_EXCEPTION = "数据更新失败";
        String DB_SORT_FIELD_NOT_FOUND = "排序字段不存在";
        String OPERATE_FAILED = "操作失败";

        String REQUEST_PARAM_ILLEGAL = "请求参数不合法";
        String REQUEST_OPERATE_FREQUENTLY = "操作频繁,请稍后重试";
        String REQUEST_TIME_OUT = "请求超时";

        String USER_NOT_EXISTS = "用户信息不存在";
        String INVALID_USER_TYPE = "无效的用户类型";
    }

    interface Code {
        int SUCCESS = 200;
        int FAILED = 0;
        int BAD_REQUEST = 400;
        int UNAUTHORIZED = 401;
        int FORBIDDEN = 403;
        // HTTP 仍使用 403，独立业务码让客户端仅对账号封禁清理会话。
        int ACCOUNT_BANNED = 40301;
        int NOT_FOUND = 404;
        int CONFLICT = 409;
        int SERVER_ERROR = 500;
    }
}
