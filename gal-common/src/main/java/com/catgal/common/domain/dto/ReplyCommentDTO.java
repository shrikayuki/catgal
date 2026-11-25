/*
 * 阅读说明
 * 回复评论的请求数据模型。
 * 字段上的 Bean Validation 注解在调用入口校验，权限、关联存在性与并发状态由 Service 核验。
 */
package com.catgal.common.domain.dto;


import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

@Data
@Schema(description = "回复评论")
public class ReplyCommentDTO {

    @Schema(description = "游戏ID", required = true, example = "1001")
    @NotNull @Positive
    private Long gameId;

    @Schema(description = "父级评论ID（一级评论ID）", required = true, example = "5001")
    @NotNull @Positive
    private Long parentId;

    @Schema(description = "回复内容", required = true, example = "你说得对！")
    @NotBlank @Size(max = 500)
    private String content;

    @Schema(description = "被回复的用户ID", example = "2001")
    @Positive
    private Long replyUserId;

    @Schema(description = "被回复的评论ID", example = "5002")
    @Positive
    private Long replyCommentId;
}
