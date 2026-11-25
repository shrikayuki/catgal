/*
 * 阅读说明
 * 游戏与会社关联的数据访问接口。
 * BaseMapper 提供单表 CRUD，额外 SQL 位于本接口注解或同名 mapper XML。
 */
package com.catgal.server.mapper;

import com.catgal.server.domain.po.GameCompany;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * <p>
 * 游戏-会社关联表 Mapper 接口
 * </p>
 *
 * @author rance
 * @since 2026-05-30
 */
public interface GameCompanyMapper extends BaseMapper<GameCompany> {

}
