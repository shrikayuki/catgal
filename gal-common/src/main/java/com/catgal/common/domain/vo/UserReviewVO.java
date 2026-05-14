package com.catgal.common.domain.vo;

import com.catgal.common.enums.PlayStatusEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Schema(description = "用户评价")
public class UserReviewVO {

    @Schema(description = "评价ID", example = "1")
    private Long id;

    @Schema(description = "游戏ID", example = "100")
    private Long gameId;

    @Schema(description = "游戏名称", example = "千恋万花")
    private String gameName;

    @Schema(description = "推荐程度", example = "4")
    private Integer recommendLevel;

    @Schema(description = "游玩状态", example = "2")
    private Integer playStatus;

    @Schema(description = "剧透程度", example = "1")
    private Integer spoilerLevel;

    @Schema(description = "评分", example = "8")
    private Integer score;

    @Schema(description = "简评", example = "不错的游戏")
    private String briefReview;

    @Schema(description = "点赞数", example = "0")
    private Integer likeCount;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

}