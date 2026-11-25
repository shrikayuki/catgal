/*
 * 阅读说明
 * 游戏作品的数据访问接口。
 * BaseMapper 提供单表 CRUD，额外 SQL 位于本接口注解或同名 mapper XML。
 * 行锁查询需加入业务事务，锁的获取顺序由上层 Service 统一约束。
 */
package com.catgal.server.mapper;

import com.catgal.server.domain.po.Game;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * <p>
 * 游戏主表 Mapper 接口
 * </p>
 *
 * @author rance
 * @since 2026-05-01
 */
public interface GameMapper extends BaseMapper<Game> {

    @Select("SELECT * FROM game WHERE id = #{id} FOR UPDATE")
    Game selectForUpdate(@Param("id") Long id);

    // 调用方先持有游戏行锁，再按 status=1 的评价聚合平均分；没有可见评价时 COALESCE 归零。
    /** Called inside the review transaction after acquiring the game's row lock. */
    @Update("UPDATE game SET rating = (SELECT COALESCE(ROUND(AVG(score), 1), 0.0) "
            + "FROM game_review WHERE game_id = #{id} AND status = 1) WHERE id = #{id}")
    int refreshRating(@Param("id") Long id);

    /** 评论写入方先持有游戏行锁，再在同一事务按公开可见规则重算。 */
    @Update("UPDATE game SET comment_count = (SELECT COUNT(*) FROM comment WHERE game_id = #{id} AND "
            + CommentMapper.VISIBLE_IN_THREAD + ") WHERE id = #{id}")
    int refreshCommentCount(@Param("id") Long id);

    int batchUpdateGameStats(@Param("list") List<Game> gamesToUpdate);
}
