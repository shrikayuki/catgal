/*
 * 阅读说明
 * 资源搜索端口，屏蔽 MySQL 与 ES 的实现差异。
 */
package com.catgal.server.search;

import com.catgal.server.domain.query.admin.AdminPageQuery;
import java.util.List;

public interface ResourceSearchPort {
    SearchPage search(AdminPageQuery query);

    record SearchPage(long total, List<Long> ids) {
        public SearchPage { ids = List.copyOf(ids); }
    }
}
