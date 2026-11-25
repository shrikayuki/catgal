/*
 * 阅读说明
 * 资源审核列表的 MySQL 检索实现，承接 ES 未启用或失败时的请求。
 */
package com.catgal.server.search;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.catgal.common.utils.StringUtils;
import com.catgal.server.domain.po.Resource;
import com.catgal.server.domain.query.admin.AdminPageQuery;
import com.catgal.server.mapper.ResourceMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MysqlResourceSearch implements ResourceSearchPort {
    private final ResourceMapper mapper;

    @Override
    public SearchPage search(AdminPageQuery query) {
        var page = mapper.selectPage(query.<Resource>toSafePage(), new LambdaQueryWrapper<Resource>()
                .select(Resource::getId)
                .eq(query.getStatus() != null, Resource::getStatus, query.getStatus())
                .eq(query.getGameId() != null, Resource::getGameId, query.getGameId())
                .eq(query.getUserId() != null, Resource::getUserId, query.getUserId())
                .like(StringUtils.isNotBlank(query.getKeyword()), Resource::getName, query.getKeyword()));
        return new SearchPage(page.getTotal(), page.getRecords().stream().map(Resource::getId).toList());
    }
}
