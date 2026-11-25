/*
 * 阅读说明
 * 游戏与标签关联的数据访问接口。
 * BaseMapper 提供单表 CRUD，额外 SQL 位于本接口注解或同名 mapper XML。
 */
package com.catgal.server.mapper;

import com.catgal.server.domain.po.GameTag;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * <p>
 * 游戏-标签关联表 Mapper 接口
 * </p>
 *
 * @author rance
 * @since 2026-05-30
 */
public interface GameTagMapper extends BaseMapper<GameTag> {

}
