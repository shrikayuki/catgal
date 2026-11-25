/*
 * 阅读说明
 * 游戏资源的数据访问接口。
 * BaseMapper 提供单表 CRUD，额外 SQL 位于本接口注解或同名 mapper XML。
 */
package com.catgal.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.catgal.server.domain.po.Resource;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * <p>
 * 游戏资源表 Mapper 接口
 * </p>
 *
 * @author rance
 * @since 2026-05-01
 */
public interface ResourceMapper extends BaseMapper<Resource> {

    /** 事务内先锁操作者、所属游戏，再锁资源，保持跨资源操作的锁序一致。 */
    @Select("SELECT * FROM resource WHERE id=#{id} FOR UPDATE")
    Resource selectForUpdate(@Param("id") Long id);

    int batchUpdateResourceStats(@Param("list") List<Resource> resourcesToUpdate);
}
