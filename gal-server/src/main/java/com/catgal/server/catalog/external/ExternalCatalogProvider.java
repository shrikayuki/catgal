/*
 * 阅读说明
 * 外部资料来源的查询接口，Bangumi 与 VNDB 分别实现协议差异。
 */
package com.catgal.server.catalog.external;

public interface ExternalCatalogProvider {
    ExternalSource source();
    ExternalSearchResult search(String keyword, int pageNo);
    ExternalGamePreview detail(String externalId);
}
