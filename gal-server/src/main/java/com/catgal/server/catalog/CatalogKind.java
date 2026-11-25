/*
 * 阅读说明
 * 会社和标签资料维护的类型约定，统一校验文案及字段范围。
 */
package com.catgal.server.catalog;

/** SQL identifiers are a closed set, never taken from request parameters. */
public enum CatalogKind {
    COMPANY("company", "game_company", "company_id", "会社", 100, Long.MAX_VALUE),
    TAG("tag", "game_tag", "tag_id", "标签", 50, Integer.MAX_VALUE);

    final String table;
    final String relationTable;
    final String relationColumn;
    final String label;
    final int maxNameLength;
    final long maxId;

    CatalogKind(String table, String relationTable, String relationColumn, String label, int maxNameLength, long maxId) {
        this.table = table;
        this.relationTable = relationTable;
        this.relationColumn = relationColumn;
        this.label = label;
        this.maxNameLength = maxNameLength;
        this.maxId = maxId;
    }
}
