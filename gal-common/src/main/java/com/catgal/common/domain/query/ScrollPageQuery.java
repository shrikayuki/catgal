package com.catgal.common.domain.query;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 滚动分页查询参数。
 *
 * <p>首次请求不传 cursor；后续请求传入上一次响应返回的游标。
 * 游标使用字符串，便于以后承载 ID、时间或复合排序键。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "滚动分页查询参数")
public class ScrollPageQuery extends AbstractPageQuery {

    @Schema(description = "滚动游标；首次请求不传", example = "eyJpZCI6MTAwfQ==")
    private String cursor;
}
