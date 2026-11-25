/*
 * 阅读说明
 * 创建收藏夹请求的请求数据模型。
 * 字段上的 Bean Validation 注解在调用入口校验，权限、关联存在性与并发状态由 Service 核验。
 */
package com.catgal.common.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(description = "创建收藏夹请求")
public class CreateFolderDTO {

    @NotBlank(message = "收藏夹名称不能为空")
    @Size(max = 50)
    @Schema(description = "收藏夹名称", example = "我的最爱")
    private String name;

    @Schema(description = "收藏夹描述", example = "最喜欢的游戏")
    @Size(max = 200)
    private String description;

    @Schema(description = "是否公开", example = "false")
    private Boolean isPublic = false;
}
