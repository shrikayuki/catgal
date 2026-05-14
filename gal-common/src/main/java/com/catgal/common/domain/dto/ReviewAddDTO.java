package com.catgal.common.domain.dto;

import com.catgal.common.enums.PlayStatusEnum;
import com.catgal.common.enums.RecommendLevelEnum;
import com.catgal.common.enums.SpoilerLevelEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(description = "发布评价请求")
public class ReviewAddDTO {

    @NotNull(message = "游戏ID不能为空")
    @Schema(description = "游戏ID", required = true, example = "1")
    private Long gameId;

    @NotNull(message = "推荐程度不能为空")
    @Schema(description = "推荐程度：1-强烈不推荐，2-不推荐，3-中立，4-推荐，5-强烈推荐", example = "4")
    private RecommendLevelEnum recommendLevel;

    @NotNull(message = "评分不能为空")
    @Min(value = 1, message = "评分不能小于1")
    @Max(value = 10, message = "评分不能大于10")
    @Schema(description = "评分：1-10分", example = "8")
    private Integer score;

    @NotNull(message = "游玩状态不能为空")
    @Schema(description = "游玩状态：1-未开始，2-正在通关，3-单线，4-主线，5-全线，6-弃坑", example = "5")
    private PlayStatusEnum playStatus;

    @Size(max = 520, message = "简评最多520个字符")
    @Schema(description = "简评，最多520字", example = "非常感人的故事")
    private String briefReview;

    @NotNull(message = "剧透等级不能为空")
    @Schema(description = "剧透等级：1-无剧透，2-轻微剧透，3-严重剧透", example = "1")
    private SpoilerLevelEnum spoilerLevel;
}
