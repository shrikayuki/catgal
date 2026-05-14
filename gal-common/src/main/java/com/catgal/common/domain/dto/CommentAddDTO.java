package com.catgal.common.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
@Schema(description = "发根评论dto")
public class CommentAddDTO {
    @NotNull(message = "游戏ID不能为空")
    @Schema(description = "游戏ID", required = true, example = "1")
    private Long gameId;

    @NotNull(message = "评论内容不能为空")
    @Min(value = 1, message = "评论内容最少1个字符")
    @Max(value = 500, message = "评论内容最多500个字符")
    @Schema(description = "评论内容", required = true, example = "这个游戏真不错！")
    private String content;

}
