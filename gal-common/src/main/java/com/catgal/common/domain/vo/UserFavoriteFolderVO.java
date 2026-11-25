/*
 * 阅读说明
 * 用户收藏夹的响应视图。
 * 业务服务将数据库记录及关联信息装配为此类型，再由 Controller 放入统一响应。
 */
package com.catgal.common.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Schema(description = "用户收藏夹")
public class UserFavoriteFolderVO {

    @Schema(description = "收藏夹ID", example = "1")
    private Long id;

    @Schema(description = "收藏夹名称", example = "收藏")
    private String name;

    @Schema(description = "收藏夹描述")
    private String description;

    @Schema(description = "是否公开")
    private Boolean isPublic;

    @Schema(description = "收藏游戏数量", example = "3")
    private Integer gameCount;


    @Schema(description = "创建时间")
    private LocalDateTime createTime;
}