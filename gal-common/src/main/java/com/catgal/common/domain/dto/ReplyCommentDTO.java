package com.catgal.common.domain.dto;


import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "回复评论")
public class ReplyCommentDTO {

    @Schema(description = "游戏ID", required = true, example = "1001")
    private Long gameId;

    @Schema(description = "父级评论ID（一级评论ID）", required = true, example = "5001")
    private Long parentId;

    @Schema(description = "回复内容", required = true, example = "你说得对！")
    private String comment;

    @Schema(description = "被回复的用户ID", example = "2001")
    private Long replyUserId;

    @Schema(description = "被回复的评论ID", example = "5002")
    private Long replyCommentId;
}
