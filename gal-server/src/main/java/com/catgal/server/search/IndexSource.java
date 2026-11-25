/*
 * 阅读说明
 * 索引源数据接口，提供按主键扫描和批量装配文档的能力。
 */
package com.catgal.server.search;

import java.util.List;
import java.util.Map;

public interface IndexSource {
    List<Long> nextIds(long afterId, int size);
    Map<Long, Map<String, Object>> load(List<Long> ids);
}
