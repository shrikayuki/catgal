/*
 * 阅读说明
 * 资料页大小、关键词、名称、ID 和 revision 的业务边界校验。
 */
package com.catgal.server.catalog;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.catgal.common.exception.BusinessException;
import com.catgal.server.domain.query.CatalogPageQuery;
import java.util.Collection;

public final class CatalogValidation {
    private CatalogValidation() { }

    public static <T> Page<T> page(CatalogPageQuery query) {
        int size = Math.min(Math.max(query.getPageSize() == null ? 20 : query.getPageSize(), 1), 100);
        int number = Math.max(query.getPageNo() == null ? 1 : query.getPageNo(), 1);
        return new Page<>(number, size);
    }

    public static String keyword(CatalogPageQuery query) {
        String keyword = query.getKeyword() == null ? "" : query.getKeyword().strip();
        if (keyword.length() > 100) throw BusinessException.badRequest("查询关键字不能超过100字符");
        return keyword;
    }

    public static String normalizeName(CatalogKind kind, String name) {
        String normalized = name == null ? "" : name.strip();
        if (normalized.isEmpty() || normalized.length() > kind.maxNameLength) {
            throw BusinessException.badRequest(kind.label + "名称须为1到" + kind.maxNameLength + "字符");
        }
        return normalized;
    }

    public static void requireRevision(Long revision) {
        if (revision == null || revision < 0) throw BusinessException.badRequest("revision必须为非负整数");
    }

    public static void requireId(CatalogKind kind, long id) {
        if (id <= 0 || id > kind.maxId) throw BusinessException.badRequest(kind.label + "ID超出有效范围");
    }

    public static void requireLockIds(CatalogKind kind, Collection<Long> ids) {
        if (ids == null || ids.size() > 50) throw BusinessException.badRequest("每类关联最多50个ID");
        for (Long id : ids) {
            if (id == null) throw BusinessException.badRequest("关联ID不能为空");
            requireId(kind, id);
        }
    }
}
