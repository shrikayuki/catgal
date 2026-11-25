/*
 * 阅读说明
 * 萌萌点流水的数据访问接口。
 * BaseMapper 提供单表 CRUD，额外 SQL 位于本接口注解或同名 mapper XML。
 */
package com.catgal.server.mapper;

import com.catgal.server.domain.po.CutePointsRecord;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * <p>
 * 萌萌点流水表 Mapper 接口
 * </p>
 *
 * @author rance
 * @since 2026-05-23
 */
public interface CutePointsRecordMapper extends BaseMapper<CutePointsRecord> {

}
