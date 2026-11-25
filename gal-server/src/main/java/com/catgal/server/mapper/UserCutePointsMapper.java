/*
 * 阅读说明
 * 用户积分余额的数据访问接口。
 * BaseMapper 提供单表 CRUD，额外 SQL 位于本接口注解或同名 mapper XML。
 */
package com.catgal.server.mapper;

import com.catgal.server.domain.po.UserCutePoints;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * <p>
 * 用户萌萌点汇总表 Mapper 接口
 * </p>
 *
 * @author rance
 * @since 2026-05-23
 */
public interface UserCutePointsMapper extends BaseMapper<UserCutePoints> {

    @org.apache.ibatis.annotations.Select("SELECT * FROM user_cute_points WHERE user_id=#{userId} FOR UPDATE")
    UserCutePoints lockBalance(Long userId);

}
