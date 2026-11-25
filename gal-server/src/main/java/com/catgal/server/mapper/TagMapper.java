/*
 * 阅读说明
 * 游戏标签的数据访问接口。
 * BaseMapper 提供单表 CRUD，额外 SQL 位于本接口注解或同名 mapper XML。
 * 行锁查询需加入业务事务，锁的获取顺序由上层 Service 统一约束。
 */
package com.catgal.server.mapper;

import com.catgal.server.domain.po.Tag;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * <p>
 * 游戏标签表 Mapper 接口
 * </p>
 *
 * @author rance
 * @since 2026-05-30
 */
public interface TagMapper extends BaseMapper<Tag> {
    Long selectRevisionForUpdate(@Param("id") long id);

    List<Long> selectIdsForShare(@Param("ids") Collection<Long> ids);
}
