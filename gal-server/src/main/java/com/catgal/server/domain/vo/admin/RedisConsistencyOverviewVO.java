/*
 * 阅读说明
 * Redis 最终一致性任务概览的响应视图。
 * 业务服务将数据库记录及关联信息装配为此类型，再由 Controller 放入统一响应。
 */
package com.catgal.server.domain.vo.admin;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.util.Map;

@Data
@Builder
@Schema(description = "Redis 最终一致性任务概览")
public class RedisConsistencyOverviewVO {
    @Schema(description = "各脏数据集合当前待处理数量")
    private Map<String, Long> pendingCounts;
    @Schema(description = "历史错误代码可能写出的未格式化 Key 及其 Redis 类型")
    private Map<String, String> suspiciousLiteralKeys;
    private long totalPending;
    private boolean backlogDetected;
}
