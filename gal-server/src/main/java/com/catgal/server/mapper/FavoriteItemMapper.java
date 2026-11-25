/*
 * 阅读说明
 * 收藏明细的数据访问接口。
 * BaseMapper 提供单表 CRUD，额外 SQL 位于本接口注解或同名 mapper XML。
 * 行锁查询需加入业务事务，锁的获取顺序由上层 Service 统一约束。
 */
package com.catgal.server.mapper;

import com.catgal.server.domain.po.FavoriteItem;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;

import java.util.Set;

/**
 * <p>
 * 收藏明细表 Mapper 接口
 * </p>
 *
 * @author rance
 * @since 2026-05-01
 */
public interface FavoriteItemMapper extends BaseMapper<FavoriteItem> {

    /** 持有游戏行锁后使用当前读，防止 RR 事务快照漏掉刚提交的收藏关系。 */
    @Select("SELECT * FROM favorite_item WHERE game_id = #{gameId} FOR UPDATE")
    List<FavoriteItem> selectByGameForUpdate(@Param("gameId") Long gameId);

    void deleteByFolderIds(@Param("folderIds") Set<Long> folderIds);
}
