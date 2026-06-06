package com.catgal.common.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "ai美少女列表信息")
public class AIGirlVO {
    @Schema(description = "主键id")
    Long id;
    @Schema(description = "美少女唯一编码,类似英文名,前端不显示,作用和id一样,前端用这个查")
    String girlCode;
    @Schema(description = "美少女姓名,列表要展示的")
    String name;
    @Schema(description = "美少女头像")
    String avatarUrl;
}
