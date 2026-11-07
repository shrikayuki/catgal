package com.catgal.server.domain.vo.admin;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@Schema(description = "游戏统计计数快照")
public class CounterValuesVO {
    private Integer favoriteCount;
    private Integer viewCount;
    private Integer commentCount;
    private Integer reviewCount;
    private Integer downloadCount;
    private Integer resourceCount;
}
