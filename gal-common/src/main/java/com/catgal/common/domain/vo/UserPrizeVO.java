package com.catgal.common.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "用户抽奖信息")
public class UserPrizeVO {
    @Schema(description = "奖品id")
    private Long id;

    @Schema(description = "用户id")
    private Long userId;

    @Schema(description = "消耗积分数")
    private Integer spentCutePoints;

}
