/*
 * 阅读说明
 * 外部搜索的分页结果与条目预览集合。
 */
package com.catgal.server.catalog.external;

import java.util.List;

public record ExternalSearchResult(List<ExternalGamePreview> items, int pageNo, boolean hasMore) { }
