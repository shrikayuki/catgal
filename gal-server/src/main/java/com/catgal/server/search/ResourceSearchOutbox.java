/*
 * 阅读说明
 * 资源搜索同步任务适配，复用通用 Outbox 查询与版本确认。
 */
package com.catgal.server.search;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ResourceSearchOutbox extends SearchOutbox {
    public ResourceSearchOutbox(JdbcTemplate jdbc) {
        super(jdbc, "resource_search_outbox", "resource_id");
    }
}
