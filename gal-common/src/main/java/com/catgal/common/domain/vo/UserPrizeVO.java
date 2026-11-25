/*
 * 阅读说明
 * 用户抽奖信息的响应视图。
 * 业务服务将数据库记录及关联信息装配为此类型，再由 Controller 放入统一响应。
 */
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

    /** 客户端逻辑请求号。刷新和网络重试必须继续使用该值。 */
    private String requestKey;
    private String bizId;
    private String prizeName;
    private String prizeImage;
    private Integer prizeType;
    private Integer pointsBalance;
    private Boolean replayed;

}
