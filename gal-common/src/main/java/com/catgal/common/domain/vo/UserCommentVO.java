package com.catgal.common.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Schema(description = "用户评论")
public class UserCommentVO {

    @Schema(description = "评论ID", example = "1")
    private Long id;

    @Schema(description = "游戏ID", example = "100")
    private Long gameId;

    @Schema(description = "游戏名称", example = "苏菲亚的谎言与代价")
    private String gameName;

    @Schema(description = "评论内容", example = "不错")
    private String content;

    @Schema(description = "点赞数", example = "0")
    private Integer likeCount;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

}