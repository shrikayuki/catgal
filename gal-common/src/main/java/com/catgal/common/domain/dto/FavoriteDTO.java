/*
 * 阅读说明
 * 收藏接口DTO的请求数据模型。
 * 字段上的 Bean Validation 注解在调用入口校验，权限、关联存在性与并发状态由 Service 核验。
 */
package com.catgal.common.domain.dto;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
@Schema(description = "收藏接口DTO")
public class FavoriteDTO {

    @Schema(description = "收藏夹id")
    @NotNull
    private Long folderId;

    @Schema(description = "游戏id")
    @NotNull
    private Long gameId;
}
