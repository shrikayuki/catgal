/*
 * 阅读说明
 * 收藏夹的数据访问接口。
 * BaseMapper 提供单表 CRUD，额外 SQL 位于本接口注解或同名 mapper XML。
 * 行锁查询需加入业务事务，锁的获取顺序由上层 Service 统一约束。
 */
package com.catgal.server.mapper;

import com.catgal.server.domain.po.FavoriteFolder;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Param;

/**
 * <p>
 * 收藏夹表 Mapper 接口
 * </p>
 *
 * @author rance
 * @since 2026-05-01
 */
public interface FavoriteFolderMapper extends BaseMapper<FavoriteFolder> {

    /** 所有收藏写入先锁收藏夹，再按 ID 递增锁游戏，避免删除后写入和交叉死锁。 */
    @Select("SELECT * FROM favorite_folder WHERE id = #{id} FOR UPDATE")
    FavoriteFolder selectForUpdate(@Param("id") Long id);

}
