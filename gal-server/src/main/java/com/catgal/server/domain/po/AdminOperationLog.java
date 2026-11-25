/*
 * 阅读说明
 * 管理员操作审计的数据库映射对象，对应 admin_operation_log 表。
 * MyBatis-Plus 通过字段与映射注解读写记录，Controller 使用专用视图承载展示字段。
 */
package com.catgal.server.domain.po;

import com.baomidou.mybatisplus.annotation.*;
import java.time.LocalDateTime;
import lombok.Data;

/** 管理员业务审计，JSON 只保存白名单字段，实体不直接作为 HTTP 响应。 */
@Data
@TableName("admin_operation_log")
public class AdminOperationLog {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long adminUserId;
    private String module;
    private String operation;
    private String targetType;
    private String targetId;
    private String beforeData;
    private String afterData;
    private String reason;
    private String requestId;
    private String ipAddress;
    private Integer result;
    private String errorMessage;
    private LocalDateTime createTime;
}
