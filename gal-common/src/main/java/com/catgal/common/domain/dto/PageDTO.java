/*
 * 阅读说明
 * 标准分页结果，保留总条数、总页数与当前页列表。
 * of 重载支持实体直接输出、类型复制或自定义转换，页统计来自原 Page。
 */
package com.catgal.common.domain.dto;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.catgal.common.utils.BeanUtils;
import com.catgal.common.utils.CollUtils;
import com.catgal.common.utils.Convert;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "分页结果")
public class PageDTO<T> {

    @Schema(description = "总条数", example = "100")
    protected Long total;

    @Schema(description = "总页码数", example = "10")
    protected Long pages;

    @Schema(description = "当前页数据")
    protected List<T> list;

    // 空记录页仍可保留原查询的总量与页数，便于前端显示真实分页状态。
    public static <T> PageDTO<T> empty(Long total, Long pages) {
        return new PageDTO<>(total, pages, CollUtils.emptyList());
    }

    // 空记录页仍可保留原查询的总量与页数，便于前端显示真实分页状态。
    public static <T> PageDTO<T> empty(Page<?> page) {
        return new PageDTO<>(page.getTotal(), page.getPages(), CollUtils.emptyList());
    }

    // 保留源 Page 的 total 和 pages，再按所选重载映射当前页记录，避免转换丢失分页元信息。
    public static <T> PageDTO<T> of(Page<T> page) {
        if (page == null) {
            return new PageDTO<>();
        }
        if (CollUtils.isEmpty(page.getRecords())) {
            return empty(page);
        }
        return new PageDTO<>(page.getTotal(), page.getPages(), page.getRecords());
    }

    // 保留源 Page 的 total 和 pages，再按所选重载映射当前页记录，避免转换丢失分页元信息。
    public static <T, R> PageDTO<T> of(Page<R> page, Function<R, T> mapper) {
        if (page == null) {
            return new PageDTO<>();
        }
        if (CollUtils.isEmpty(page.getRecords())) {
            return empty(page);
        }
        return new PageDTO<>(page.getTotal(), page.getPages(),
                page.getRecords().stream().map(mapper).collect(Collectors.toList()));
    }

    // 保留源 Page 的 total 和 pages，再按所选重载映射当前页记录，避免转换丢失分页元信息。
    public static <T> PageDTO<T> of(Page<?> page, List<T> list) {
        return new PageDTO<>(page.getTotal(), page.getPages(), list);
    }

    // 保留源 Page 的 total 和 pages，再按所选重载映射当前页记录，避免转换丢失分页元信息。
    public static <T, R> PageDTO<T> of(Page<R> page, Class<T> clazz) {
        if (page == null) {
            return new PageDTO<>();
        }
        if (CollUtils.isEmpty(page.getRecords())) {
            return empty(page);
        }
        return new PageDTO<>(page.getTotal(), page.getPages(),
                BeanUtils.copyList(page.getRecords(), clazz));
    }

    // 保留源 Page 的 total 和 pages，再按所选重载映射当前页记录，避免转换丢失分页元信息。
    public static <T, R> PageDTO<T> of(Page<R> page, Class<T> clazz, Convert<R, T> convert) {
        if (page == null) {
            return new PageDTO<>();
        }
        if (CollUtils.isEmpty(page.getRecords())) {
            return empty(page);
        }
        return new PageDTO<>(page.getTotal(), page.getPages(),
                BeanUtils.copyList(page.getRecords(), clazz, convert));
    }

    @Schema(hidden = true)
    @JsonIgnore
    public boolean isEmpty() {
        return list == null || list.isEmpty();
    }
}