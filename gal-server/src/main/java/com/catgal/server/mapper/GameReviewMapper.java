/*
 * 阅读说明
 * 游戏评价的数据访问接口。
 * BaseMapper 提供单表 CRUD，额外 SQL 位于本接口注解或同名 mapper XML。
 * 行锁查询需加入业务事务，锁的获取顺序由上层 Service 统一约束。
 */
package com.catgal.server.mapper;

import com.catgal.server.domain.po.GameReview;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * <p>
 * 游戏评价表 Mapper 接口
 * </p>
 *
 * @author rance
 * @since 2026-05-01
 */
public interface GameReviewMapper extends BaseMapper<GameReview> {
    @Select("SELECT * FROM game_review WHERE id = #{id} FOR UPDATE")
    GameReview selectForUpdate(@Param("id") Long id);
}
