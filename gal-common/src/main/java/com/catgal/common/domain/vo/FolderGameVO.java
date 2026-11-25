/*
 * 阅读说明
 * 收藏夹内游戏的响应视图。
 * 业务服务将数据库记录及关联信息装配为此类型，再由 Controller 放入统一响应。
 */
package com.catgal.common.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "收藏夹内游戏")
public class FolderGameVO {

    @Schema(description = "游戏ID")
    private Long gameId;

    @Schema(description = "游戏名称")
    private String gameName;

    @Schema(description = "游戏封面")
    private String gameCoverUrl;
}