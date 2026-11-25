/*
 * 阅读说明
 * 游戏详情内资源、评论和评价列表的公共分页条件，gameId 确定关联范围。
 */
package com.catgal.common.domain.query;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "游戏相关评论评价查询参数")
public class GameQuery extends PageQuery{
    private Long gameId;
}
