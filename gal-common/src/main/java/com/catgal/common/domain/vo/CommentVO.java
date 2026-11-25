/*
 * 阅读说明
 * 评论信息VO的响应视图。
 * 业务服务将数据库记录及关联信息装配为此类型，再由 Controller 放入统一响应。
 */
package com.catgal.common.domain.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Schema(description = "评论信息VO")
public class CommentVO {

    @Schema(description = "评论ID", example = "1")
    private Long id;

    @Schema(description = "评论用户ID", example = "1001")
    private Long userId;

    @Schema(description = "评论用户昵称", example = "张三")
    private String username;

    @Schema(description = "根评论ID")
    private Long parentId;

    @Schema(description = "被回复的人Id")
    private Long replyUserId;

    @Schema(description = "被回复的评论Id")
    private Long replyCommentId;

    @Schema(description = "评论用户头像URL", example = "https://example.com/avatar.jpg")
    private String avatarUrl;

    @Schema(description = "评论内容", example = "这个游戏真好玩！")
    private String content;

    @Schema(description = "点赞数", example = "10", defaultValue = "0")
    private Integer likeCount;

    @Schema(description = "创建时间", example = "2024-01-01 12:00:00")
    private LocalDateTime createTime;

    @Schema(description = "是否为当前用户的评论", example = "true")
    private Boolean isMyComment;

    @Schema(description = "是否被当前用户点赞", example = "false")
    private Boolean isLike;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String replyUsername;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Long replyCount;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private List<CommentVO> replyPreview;
}
