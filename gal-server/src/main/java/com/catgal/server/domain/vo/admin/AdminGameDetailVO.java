/*
 * 阅读说明
 * AdminGameDetailVO的响应视图。
 * 业务服务将数据库记录及关联信息装配为此类型，再由 Controller 放入统一响应。
 */
package com.catgal.server.domain.vo.admin;

import com.catgal.server.domain.po.Game;
import com.catgal.server.domain.vo.CatalogEntryVO;
import java.util.List;

public record AdminGameDetailVO(Game game, List<CatalogEntryVO> tags, List<CatalogEntryVO> companies) { }
