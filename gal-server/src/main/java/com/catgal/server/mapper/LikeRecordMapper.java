/*
 * 阅读说明
 * 点赞关系的数据访问接口。
 * BaseMapper 提供单表 CRUD，额外 SQL 位于本接口注解或同名 mapper XML。
 */
package com.catgal.server.mapper;

import com.catgal.server.domain.po.LikeRecord;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import java.time.LocalDateTime;

/**
 * <p>
 * 点赞记录表 Mapper 接口
 * </p>
 *
 * @author rance
 * @since 2026-05-03
 */
public interface LikeRecordMapper extends BaseMapper<LikeRecord> {
    // 扩展点赞类型时，同步修改类型常量、LikeTargetValidator、下列固定表名分支和监听器绑定。
    // Table names are fixed literals. Callers validate the supported business type first.
    // 固定表名阻止外部参数拼接 SQL，行锁由外层业务事务持有至提交。
    @Select("""
            <script>
            SELECT id,status,like_count AS likeCount FROM
            <choose>
              <when test="type == 'comment'">`comment`</when>
              <when test="type == 'review'">game_review</when>
              <otherwise>resource</otherwise>
            </choose>
            WHERE id=#{id} FOR UPDATE
            </script>
            """)
    Target lockTarget(@Param("type") String type, @Param("id") Long id);

    @Select("""
            <script>
            SELECT target_id AS targetId,COUNT(*) AS total FROM like_record
            WHERE type=#{type} AND target_id IN
            <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
            GROUP BY target_id
            </script>
            """)
    List<Count> countByTargets(@Param("type") String type, @Param("ids") List<Long> ids);

    /** 调用前已锁定对应目标，并验证当前完整计数。更新时间沿用现有自动填充的时间类型。 */
    @Update("""
            <script>
            UPDATE
            <choose>
              <when test="type == 'comment'">`comment`</when>
              <when test="type == 'review'">game_review</when>
              <otherwise>resource</otherwise>
            </choose>
            SET like_count=#{count},update_time=#{updatedAt} WHERE id=#{id}
            </script>
            """)
    int updateTargetLikeCount(@Param("type") String type, @Param("id") Long id,
                             @Param("count") int count, @Param("updatedAt") LocalDateTime updatedAt);

    record Target(Long id, Integer status, Integer likeCount) {}
    record Count(Long targetId, Long total) {}
}
