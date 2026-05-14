package com.catgal.common.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
@Schema(description = "创建收藏夹请求")
public class CreateFolderDTO {

    @NotBlank(message = "收藏夹名称不能为空")
    @Schema(description = "收藏夹名称", example = "我的最爱")
    private String name;

    @Schema(description = "收藏夹描述", example = "最喜欢的游戏")
    private String description;

    @Schema(description = "是否公开", example = "false")
    private Boolean isPublic = false;
}
