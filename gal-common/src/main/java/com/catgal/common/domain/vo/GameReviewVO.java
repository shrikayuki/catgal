package com.catgal.common.domain.vo;

import com.catgal.common.enums.PlayStatusEnum;
import com.catgal.common.enums.RecommendLevelEnum;
import com.catgal.common.enums.SpoilerLevelEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Schema(description = "游戏评价响应")
public class GameReviewVO {

    @Schema(description = "评价ID", example = "1")
    private Long id;

    @Schema(description = "评价用户ID", example = "1001")
    private Long userId;

    @Schema(description = "评价用户名", example = "玩家小明")
    private String username;

    @Schema(description = "用户头像URL", example = "https://example.com/avatar.jpg")
    private String avatarUrl;

    @Schema(description = "评分", example = "9")
    private Integer score;

    @Schema(description = "推荐程度", example = "4")
    private RecommendLevelEnum recommendLevel;

    @Schema(description = "游玩状态", example = "5")
    private PlayStatusEnum playStatus;

    @Schema(description = "剧透等级", example = "1")
    private SpoilerLevelEnum spoilerLevel;

    @Schema(description = "点赞数", example = "42")
    private Integer likeCount;

    @Schema(description = "用户是否点赞", example = "false")
    private Boolean isLike;

    @Schema(description = "是否是当前用户评价")
    private Boolean isMyReview;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;
}