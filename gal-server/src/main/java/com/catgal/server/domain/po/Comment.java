/*
 * 阅读说明
 * 游戏评论的数据库映射对象，对应 comment 表。
 * MyBatis-Plus 通过字段与映射注解读写记录，Controller 使用专用视图承载展示字段。
 */
package com.catgal.server.domain.po;

import com.baomidou.mybatisplus.annotation.*;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * <p>
 * 游戏评论表
 * </p>
 *
 * @author rance
 * @since 2026-05-03
 */
@Data
@Accessors(chain = true)
@TableName("comment")
@Schema(description = "游戏评论")
public class Comment implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    @Schema(description = "评论ID", example = "1")
    private Long id;

    @Schema(description = "评论用户ID", example = "1001")
    private Long userId;

    @Schema(description = "被评论的游戏ID", example = "1")
    private Long gameId;

    @Schema(description = "父评论ID（用于回复）", example = "100")
    private Long parentId;

    @Schema(description = "被回复的用户ID", example = "1002")
    private Long replyUserId;

    @Schema(description = "被回复的评论ID", example = "101")
    private Long replyCommentId;

    @Schema(description = "评论内容", example = "这个游戏真不错！")
    private String content;

    @Schema(description = "点赞数", example = "0")
    private Integer likeCount;

    @Schema(description = "状态：0-已删除，1-正常", example = "1")
    private Integer status;

    @TableField(fill = FieldFill.INSERT)
    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    @Schema(description = "更新时间")
    private LocalDateTime updateTime;
}