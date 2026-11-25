/*
 * 阅读说明
 * CatalogPageQuery 封装查询筛选条件，默认值与校验注解共同确定请求边界。
 * 分页继承关系和转换方法决定使用标准页码或业务游标，具体 SQL 由调用服务构造。
 */
package com.catgal.server.domain.query;

import com.catgal.common.domain.query.PageQuery;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "会社、标签分页查询，按ID稳定排序，每页最多100条")
public class CatalogPageQuery extends PageQuery {
    @Size(max = 100)
    private String keyword;
}
