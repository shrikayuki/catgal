package com.catgal.common.domain.query;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PageQueryTest {

    @Test
    void standardPageQueryKeepsExistingDefaultsAndConversion() {
        PageQuery query = new PageQuery();

        assertEquals(1, query.getPageNo());
        assertEquals(20, query.getPageSize());
        assertTrue(query.getIsAsc());
        assertEquals(0, query.from());

        query.setPageNo(3);
        query.setPageSize(10);
        Page<Object> page = query.toMpPage();

        assertEquals(20, query.from());
        assertEquals(3, page.getCurrent());
        assertEquals(10, page.getSize());
    }

    @Test
    void scrollPageQuerySharesCommonOptionsWithoutPageNumber() {
        ScrollPageQuery query = new ScrollPageQuery();

        assertEquals(20, query.getPageSize());
        assertTrue(query.getIsAsc());
        assertNull(query.getSortBy());
        assertNull(query.getCursor());

        query.setCursor("next-cursor");
        assertEquals("next-cursor", query.getCursor());
    }

    @Test
    void genericPaginationBoundsSizeAndUsesStableFallbackForUnknownOrder() {
        PageQuery query = new PageQuery();
        query.setPageSize(300);
        query.setSortBy("unknown_field");
        var page = query.toMpPageDefaultSortByCreateTimeDesc();
        assertEquals(100, page.getSize());
        assertEquals("create_time", page.orders().get(0).getColumn());
        assertEquals("id", page.orders().get(1).getColumn());
        assertFalse(page.orders().get(0).isAsc());
        assertFalse(page.orders().get(1).isAsc());
        assertEquals("unknown_field", query.getSortBy());
    }

    @Test
    void knownIdOrderKeepsRequestedDirectionWithoutDuplicateColumn() {
        PageQuery query = new PageQuery();
        query.setSortBy("id");
        var page = query.toMpPageDefaultSortByCreateTimeDesc();
        assertEquals(1, page.orders().size());
        assertEquals("id", page.orders().get(0).getColumn());
        assertTrue(page.orders().get(0).isAsc());
    }
}
