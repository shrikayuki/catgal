/*
 * 阅读说明
 * 游戏统计计数快照的响应视图。
 * 业务服务将数据库记录及关联信息装配为此类型，再由 Controller 放入统一响应。
 */
package com.catgal.server.domain.vo.admin;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@Schema(description = "游戏统计计数快照")
public class CounterValuesVO {
    private Integer favoriteCount;
    private Integer viewCount;
    private Integer commentCount;
    private Integer reviewCount;
    private Integer downloadCount;
    private Integer resourceCount;
}
