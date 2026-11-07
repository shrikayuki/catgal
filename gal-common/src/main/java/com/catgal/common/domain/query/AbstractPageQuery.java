package com.catgal.common.domain.query;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import lombok.Data;

/**
 * 标准分页和滚动分页共同使用的查询参数。
 */
@Data
@Schema(description = "分页查询公共参数")
public abstract class AbstractPageQuery {

    public static final Integer DEFAULT_PAGE_SIZE = 20;

    @Schema(description = "每页大小", example = "20")
    @Min(value = 1, message = "每页查询数量不能小于1")
    protected Integer pageSize = DEFAULT_PAGE_SIZE;

    @Schema(description = "是否升序", example = "true")
    protected Boolean isAsc = true;

    @Schema(description = "排序字段", example = "id")
    protected String sortBy;
}
