/*
 * 阅读说明
 * AdminOperationLogVO的响应视图。
 * 业务服务将数据库记录及关联信息装配为此类型，再由 Controller 放入统一响应。
 */
package com.catgal.server.domain.vo.admin;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.catgal.server.domain.AdminRedisOperation.Outcome;
import java.time.OffsetDateTime;
import java.util.Map;
import lombok.Data;

@Data
public class AdminOperationLogVO {
    private Long id;
    private Long adminUserId;
    private String operation;
    // 内容接口区分评论和评价，两张业务表的编号可能重合。
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String targetType;
    private String targetId;
    /** Redis 运维请求的执行结果。其他操作记录沿用原响应结构。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Outcome outcome;
    private Map<String, Object> beforeData;
    private Map<String, Object> afterData;
    private String reason;
    private String requestId;
    private String ipAddress;
    private OffsetDateTime createTime;
}
