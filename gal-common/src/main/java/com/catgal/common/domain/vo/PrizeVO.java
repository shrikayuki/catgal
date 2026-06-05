package com.catgal.common.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
@Schema(description = "奖品信息")
public class PrizeVO {
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
