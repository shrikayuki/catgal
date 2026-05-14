package com.catgal.common.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
@Schema(description = "点赞请求")
public class LikeDTO {

    @NotBlank(message = "类型不能为空")
    @Pattern(regexp = "^(comment|review|resource)$", message = "类型只能是 comment、review 或 resource")
    @Schema(description = "类型：comment/review/resource", example = "comment", allowableValues = {"comment", "review", "resource"})
    private String type;

    @NotNull(message = "目标ID不能为空")
    @Min(value = 1, message = "目标ID必须大于0")
    @Schema(description = "目标ID", example = "100")
    private Long targetId;
}