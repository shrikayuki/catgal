/*
 * 阅读说明
 * 受支持资料来源的枚举，负责 ID 规范化及固定官方条目地址构造。
 */
package com.catgal.server.catalog.external;

import com.catgal.common.exception.BusinessException;

public enum ExternalSource {
    BANGUMI("bangumi", "https://bgm.tv/subject/"),
    VNDB("vndb", "https://vndb.org/");

    private final String key;
    private final String pagePrefix;

    ExternalSource(String key, String pagePrefix) {
        this.key = key;
        this.pagePrefix = pagePrefix;
    }

    public String key() { return key; }

    public String normalizeId(String id) {
        String value = id == null ? "" : id.strip();
        String pattern = this == BANGUMI ? "[1-9][0-9]{0,9}" : "v[1-9][0-9]{0,9}";
        if (!value.matches(pattern)) throw BusinessException.badRequest("外部条目ID格式错误");
        return value;
    }

    public String pageUrl(String id) { return pagePrefix + normalizeId(id); }

    public static ExternalSource parse(String key) {
        for (ExternalSource source : values()) {
            if (source.key.equals(key)) return source;
        }
        throw BusinessException.badRequest("资料来源只支持bangumi或vndb");
    }
}
