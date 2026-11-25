/*
 * 阅读说明
 * 单个资源下载计数一致性的响应视图。
 * 业务服务将数据库记录及关联信息装配为此类型，再由 Controller 放入统一响应。
 */
package com.catgal.server.domain.vo.admin;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
@Schema(description = "单个资源下载计数一致性")
public class ResourceDownloadConsistencyVO {
    private Long resourceId;
    private Integer databaseCount;
    private Integer redisCount;
    private List<String> issues;
    private boolean consistent;
}
