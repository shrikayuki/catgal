/*
 * 阅读说明
 * 游戏评论的数据访问接口。
 * BaseMapper 提供单表 CRUD，额外 SQL 位于本接口注解或同名 mapper XML。
 * 行锁查询需加入业务事务，锁的获取顺序由上层 Service 统一约束。
 */
package com.catgal.server.mapper;

import com.catgal.server.domain.po.Comment;
import com.catgal.server.domain.vo.CommentReplyPreview;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Param;

import java.util.Collection;
import java.util.List;

/**
 * <p>
 * 游戏评论表 Mapper 接口
 * </p>
 *
 * @author rance
 * @since 2026-05-01
 */
public interface CommentMapper extends BaseMapper<Comment> {
    // 回复只有在同一游戏的主评论仍可见时才能出现在公开讨论中。
    // 中间回复单独隐藏时，其他回复仍可通过平铺讨论访问，所以这里只校验主评论。
    String VISIBLE_IN_THREAD = """
            comment.status = 1 AND (comment.parent_id IS NULL OR EXISTS (
                SELECT 1 FROM comment root
                WHERE root.id = comment.parent_id AND root.game_id = comment.game_id
                    AND root.parent_id IS NULL AND root.status = 1
            ))
            """;

    String VISIBLE_ON_PUBLIC_PROFILE = VISIBLE_IN_THREAD + """
            AND EXISTS (SELECT 1 FROM game g WHERE g.id = comment.game_id AND g.status = 1)
            """;

    @Select("SELECT * FROM comment WHERE id = #{id} FOR UPDATE")
    Comment selectForUpdate(@Param("id") Long id);

    @Select("SELECT * FROM comment WHERE id = #{id} AND " + VISIBLE_IN_THREAD)
    Comment selectVisibleInThread(@Param("id") Long id);

    @Select("SELECT COUNT(*) FROM comment WHERE game_id = #{gameId} AND " + VISIBLE_IN_THREAD)
    int countVisibleInGame(@Param("gameId") Long gameId);

    @Select("""
            <script>
            SELECT comment.game_id AS gameId, COUNT(*) AS commentCount FROM comment
            WHERE comment.game_id IN
            <foreach collection="gameIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            AND
            """ + VISIBLE_IN_THREAD + " GROUP BY comment.game_id </script>")
    List<GameCommentCount> countVisibleInGames(@Param("gameIds") Collection<Long> gameIds);

    record GameCommentCount(Long gameId, Integer commentCount) {}

    List<CommentReplyPreview> selectReplyPreviews(@Param("gameId") Long gameId,
                                                  @Param("rootIds") List<Long> rootIds);

}
