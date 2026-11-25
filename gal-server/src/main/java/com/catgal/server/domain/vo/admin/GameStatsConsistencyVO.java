/*
 * 阅读说明
 * 单个游戏的 Redis/数据库统计一致性的响应视图。
 * 业务服务将数据库记录及关联信息装配为此类型，再由 Controller 放入统一响应。
 */
package com.catgal.server.domain.vo.admin;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
@Schema(description = "单个游戏的 Redis/数据库统计一致性")
public class GameStatsConsistencyVO {
    private Long gameId;
    @Schema(description = "game 表当前冗余统计")
    private CounterValuesVO database;
    @Schema(description = "Redis 当前统计；字段为 null 表示 Key 缺失或值非法")
    private CounterValuesVO redis;
    @Schema(description = "可从关系表重新计算的收藏、评论、评价、资源数量")
    private CounterValuesVO relationTruth;
    private List<String> issues;
    private boolean consistent;
}
