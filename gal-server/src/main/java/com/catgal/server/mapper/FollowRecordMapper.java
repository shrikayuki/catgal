/*
 * 阅读说明
 * 用户关注的数据访问接口。
 * BaseMapper 提供单表 CRUD，额外 SQL 位于本接口注解或同名 mapper XML。
 */
package com.catgal.server.mapper;

import com.catgal.server.domain.po.FollowRecord;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * <p>
 * 关注记录表 Mapper 接口
 * </p>
 *
 * @author rance
 * @since 2026-06-05
 */
public interface FollowRecordMapper extends BaseMapper<FollowRecord> {

}
