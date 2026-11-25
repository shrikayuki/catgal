/*
 * 阅读说明
 * 本地游戏的外部来源关联展示，保存来源标识、条目 ID 与预览字段。
 */
package com.catgal.server.catalog.external;

import java.time.LocalDateTime;

public record ExternalLinkVO(String provider, String externalId, String title, String url,
                             LocalDateTime createTime, LocalDateTime updateTime) { }
