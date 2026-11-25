/*
 * 阅读说明
 * 发根评论dto的请求数据模型。
 * 字段上的 Bean Validation 注解在调用入口校验，权限、关联存在性与并发状态由 Service 核验。
 */
package com.catgal.common.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(description = "发根评论dto")
public class CommentAddDTO {
    @NotNull(message = "游戏ID不能为空")
    @Min(1)
    @Schema(description = "游戏ID", required = true, example = "1")
    private Long gameId;

    @NotBlank(message = "评论内容不能为空")
    @Size(max = 500, message = "评论内容最多500个字符")
    @Schema(description = "评论内容", required = true, example = "这个游戏真不错！")
    private String content;

}
