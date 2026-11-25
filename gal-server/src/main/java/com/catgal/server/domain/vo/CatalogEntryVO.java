/*
 * 阅读说明
 * CatalogEntryVO的响应视图。
 * 业务服务将数据库记录及关联信息装配为此类型，再由 Controller 放入统一响应。
 */
package com.catgal.server.domain.vo;

import java.time.LocalDateTime;

public record CatalogEntryVO(Long id, String name, Long revision,
                             LocalDateTime createTime, LocalDateTime updateTime) { }
