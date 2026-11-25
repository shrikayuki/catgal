/*
 * 阅读说明
 * 搜索派生索引的写入契约，定义创建、批量更新、刷新和激活操作。
 */
package com.catgal.server.search;

import java.util.List;
import java.util.Map;

public interface SearchIndexWriter {
    String createIndex();
    void write(String index, List<Long> ids, Map<Long, Map<String, Object>> documents);
    void refresh(String index);
    void activate(String index);
}
