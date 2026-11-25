/*
 * 阅读说明
 * 当前用户的萌萌点流水的响应视图。
 * 业务服务将数据库记录及关联信息装配为此类型，再由 Controller 放入统一响应。
 */
package com.catgal.server.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "当前用户的萌萌点流水")
public record CutePointsRecordVO(Long id, Integer cutePoints, Integer type, String bizId,
                                 Integer beforePoints, Integer afterPoints, String remark,
                                 LocalDateTime createTime) { }
