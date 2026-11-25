package com.catgal.server.mapper;

import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Param;
import java.util.List;

/** 资源统计是关系表的派生数据。队列由资源触发器与业务修改同事务维护。 */
public interface ResourceCountOutboxMapper {
    @Select("SELECT game_id FROM resource_count_outbox WHERE pending=1 AND next_attempt_at<=NOW(6) ORDER BY next_attempt_at,game_id LIMIT 100")
    List<Long> dueGames();

    /** 必须先持有游戏行锁，禁止先锁队列再锁游戏。 */
    @Select("SELECT version FROM resource_count_outbox WHERE game_id=#{id} AND pending=1 AND next_attempt_at<=NOW(6) FOR UPDATE")
    Long lockDueVersion(@Param("id") Long id);

    @Update("UPDATE game SET resource_count=#{count} WHERE id=#{id}")
    int updateCount(@Param("id") Long id, @Param("count") int count);

    @Update("UPDATE resource_count_outbox SET pending=0,attempts=0,last_error=NULL,updated_at=NOW(6) WHERE game_id=#{id} AND version=#{version}")
    int acknowledge(@Param("id") Long id, @Param("version") Long version);

    // 原始异常只存类型，避免 SQL 或 Redis 错误包含内部连接信息。
    @Update("UPDATE resource_count_outbox SET attempts=LEAST(attempts+1,30),last_error=#{error}, "
            + "next_attempt_at=TIMESTAMPADD(SECOND,LEAST(300,POW(2,LEAST(attempts,8))),NOW(6)),updated_at=NOW(6) "
            + "WHERE game_id=#{id} AND version=#{version} AND pending=1")
    int failed(@Param("id") Long id, @Param("version") Long version, @Param("error") String error);

    @Select("SELECT COUNT(*) FROM resource_count_outbox WHERE pending=1")
    long pendingCount();
}
