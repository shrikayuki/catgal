/*
 * 阅读说明
 * 滚动分页结果，nextCursor 用于请求下一批，hasMore 表示仍有后续数据。
 * 游标含义由具体查询定义，积分流水的实现见 CutePointsRecordServiceImpl。
 */
package com.catgal.common.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "滚动分页结果")
public record ScrollPageDTO<T>(List<T> list, String nextCursor, boolean hasMore) { }
