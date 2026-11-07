package com.catgal.server.domain.query.admin;

import com.baomidou.mybatisplus.core.metadata.OrderItem;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.catgal.common.domain.query.PageQuery;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "后台管理通用分页筛选")
public class AdminPageQuery extends PageQuery {

    @Schema(description = "名称、用户名、手机号或正文关键字")
    private String keyword;

    @Schema(description = "用户ID")
    private Long userId;

    @Schema(description = "游戏ID")
    private Long gameId;

    @Schema(description = "状态")
    private Integer status;

    @Schema(description = "用户角色：0普通用户、1创作者、2管理员")
    private Integer role;

    @Schema(description = "奖池月份，格式 yyyy-MM")
    private String month;

    /**
     * 后台接口不接受任意数据库列名排序，避免把客户端输入直接带入 ORDER BY。
     */
    public <T> Page<T> toSafePage() {
        int requestedSize = getPageSize() == null ? DEFAULT_PAGE_SIZE : getPageSize();
        int requestedPage = getPageNo() == null ? DEFAULT_PAGE_NUM : getPageNo();
        int safeSize = Math.min(Math.max(requestedSize, 1), 100);
        Page<T> page = new Page<>(Math.max(requestedPage, 1), safeSize);
        page.addOrder(OrderItem.desc("id"));
        return page;
    }
}
