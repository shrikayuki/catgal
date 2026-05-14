package com.catgal.common.domain.query;

import com.baomidou.mybatisplus.core.metadata.OrderItem;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.catgal.common.domain.query.PageQuery;
import com.catgal.common.enums.LanguageEnum;
import com.catgal.common.enums.PlatformEnum;
import com.catgal.common.enums.ResourceTypeEnum;
import com.catgal.common.utils.StringUtils;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;
import java.util.Set;

@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "游戏分页查询参数")
public class GamePageQuery extends PageQuery {

    // 游戏允许的排序字段白名单
    private static final Set<String> ALLOWED_SORT_FIELDS = Set.of(
            "create_time",    // ✅ 改成下划线命名
            "rating",
            "view_count",     // ✅ 改成下划线命名
            "download_count", // ✅ 改成下划线命名
            "favorite_count", // ✅ 改成下划线命名
            "release_date"
    );

    // ========== 模糊匹配 ==========
    @Schema(description = "游戏名称（模糊匹配）", example = "Summer")
    private String name;

    // ========== 时间筛选 ==========
    @Schema(description = "发售年份", example = "2024")
    private Integer releaseYear;

    // ========== 标签筛选（单选） ==========
    @Schema(description = "游戏标签ID", example = "1")
    private Integer tagId;
    // ========== 会社筛选（单选） ==========
    @Schema(description = "会社ID", example = "1")
    private Long companyId;
    // ========== 资源相关筛选（单选） ==========
    @Schema(description = "资源类型", example = "pc", allowableValues = {"pc", "生肉", "汉化", "手机", "补丁", "模拟器", "其他"})
    private String resourceType;

    @Schema(description = "语言", example = "中文", allowableValues = {"中文", "日语", "英语", "其他"})
    private String language;

    @Schema(description = "平台", example = "win", allowableValues = {"win", "mac", "linux", "android", "ios", "其他"})
    private String platform;

    // ========== 排序 ==========
    // 可排序字段：createTime, rating, viewCount, downloadCount, favoriteCount, releaseDate

    /**
     * 重写父类方法，只允许白名单内的字段排序
     */
    @Override
    public <T> Page<T> toMpPage(OrderItem... orderItems) {
        Page<T> page = new Page<>(getPageNo(), getPageSize());

        // 手动指定排序
        if (orderItems != null && orderItems.length > 0) {
            for (OrderItem orderItem : orderItems) {
                if (ALLOWED_SORT_FIELDS.contains(orderItem.getColumn())) {
                    page.addOrder(orderItem);
                }
            }
            // 手动排序没有有效字段时，使用默认排序
            if (page.orders() == null || page.orders().isEmpty()) {
                page.addOrder(OrderItem.desc("createTime"));
            }
            return page;
        }

        // 前端传来的排序字段
        if (StringUtils.isNotBlank(getSortBy()) && ALLOWED_SORT_FIELDS.contains(getSortBy())) {
            OrderItem orderItem = new OrderItem();
            orderItem.setColumn(getSortBy());
            orderItem.setAsc(getIsAsc());
            page.addOrder(orderItem);
        } else {
            // 没有有效排序字段，使用默认排序（按创建时间倒序）
            page.addOrder(OrderItem.desc("create_time"));
        }

        return page;
    }
}