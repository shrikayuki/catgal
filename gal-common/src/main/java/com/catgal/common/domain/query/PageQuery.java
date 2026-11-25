/*
 * 阅读说明
 * 基于页码和页大小构造 MyBatis-Plus Page，提供默认排序入口。
 * 通用排序只接受公共列名，具体业务可以传入服务端固定排序或定义自己的白名单。
 */
package com.catgal.common.domain.query;

import com.baomidou.mybatisplus.core.metadata.OrderItem;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import com.catgal.common.constants.Constant;
import com.catgal.common.utils.StringUtils;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Locale;

@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "分页查询参数")
public class PageQuery extends AbstractPageQuery {

    public static final Integer DEFAULT_PAGE_NUM = 1;
    private static final int MAX_PAGE_SIZE = 100;

    @Schema(description = "页码", example = "1")
    @Min(value = 1, message = "页码不能小于1")
    private Integer pageNo = DEFAULT_PAGE_NUM;

    // 页码从 1 开始，SQL offset 从 0 开始，此方法完成两种表示的换算。
    public int from(){
        return (pageNo - 1) * pageSize;
    }

    // 显式 OrderItem 优先，其他分支使用请求或默认排序，调用边界需要收敛允许的字段。
    public <T> Page<T> toMpPage(OrderItem ... orderItems) {
        Page<T> page = boundedPage();
        // 是否手动指定排序方式
        if (orderItems != null && orderItems.length > 0) {
            for (OrderItem orderItem : orderItems) {
                page.addOrder(orderItem);
            }
            return page;
        }
        String column = allowedSortColumn();
        if (column != null) applyStableSort(page, column, !Boolean.FALSE.equals(isAsc));
        return page;
    }

    // 显式 OrderItem 优先，其他分支使用请求或默认排序，调用边界需要收敛允许的字段。
    public <T> Page<T> toMpPage(String defaultSortBy, boolean isAsc) {
        String column = allowedSortColumn();
        Page<T> page = boundedPage();
        applyStableSort(page, column == null ? defaultSortBy : column,
                column == null ? isAsc : !Boolean.FALSE.equals(this.isAsc));
        return page;
    }
    public <T> Page<T> toMpPageDefaultSortByCreateTimeDesc() {
        return toMpPage(Constant.DATA_FIELD_NAME_CREATE_TIME, false);
    }

    private <T> Page<T> boundedPage() {
        int number = pageNo == null ? DEFAULT_PAGE_NUM : Math.max(pageNo, 1);
        int size = pageSize == null ? DEFAULT_PAGE_SIZE : Math.max(pageSize, 1);
        return new Page<>(number, Math.min(size, MAX_PAGE_SIZE));
    }

    private String allowedSortColumn() {
        if (StringUtils.isBlank(sortBy)) return null;
        return switch (sortBy.strip().toLowerCase(Locale.ROOT)) {
            case "id" -> "id";
            case "create_time" -> "create_time";
            default -> null;
        };
    }

    private void applyStableSort(Page<?> page, String column, boolean ascending) {
        page.addOrder(ascending ? OrderItem.asc(column) : OrderItem.desc(column));
        // 多行创建时间相同时按主键定序，分页不会因相同时间戳随机换位。
        if (!"id".equals(column)) {
            page.addOrder(ascending ? OrderItem.asc("id") : OrderItem.desc("id"));
        }
    }
}
