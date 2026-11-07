package com.catgal.server.domain.query.admin;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AdminPageQueryTest {

    @Test
    void capsPageSizeAndUsesFixedIdDescendingOrder() {
        AdminPageQuery query = new AdminPageQuery();
        query.setPageNo(3);
        query.setPageSize(9999);
        query.setSortBy("password");
        query.setIsAsc(true);

        Page<Object> page = query.toSafePage();

        assertEquals(3, page.getCurrent());
        assertEquals(100, page.getSize());
        assertEquals("id", page.orders().get(0).getColumn());
        assertFalse(page.orders().get(0).isAsc());
    }
}
