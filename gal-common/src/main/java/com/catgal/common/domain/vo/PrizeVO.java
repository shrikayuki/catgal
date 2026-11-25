/*
 * 阅读说明
 * 奖品信息的响应视图。
 * 业务服务将数据库记录及关联信息装配为此类型，再由 Controller 放入统一响应。
 */
package com.catgal.common.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
@Schema(description = "奖品信息")
public class PrizeVO {

    /** 是否具备抽取资格，展示接口无需公开内部权重。最终资格仍以事务内校验为准。 */
    private Boolean drawable;
    @Schema(description = "奖品id")
    private Long id;
    @Schema(description = "奖品名称")
    private String prizeName;
    @Schema(description = "奖品url")
    private String prizeImage;
    @Schema(description = "奖品权重")
    private Integer weight;
    @Schema(description = "剩余库存")
    private Integer remainStock;
    @Schema(description = "总库存")
    private Integer totalStock;
}
