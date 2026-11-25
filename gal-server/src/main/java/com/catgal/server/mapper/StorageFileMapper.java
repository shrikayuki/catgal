/*
 * 阅读说明
 * 托管文件的数据访问接口。
 * BaseMapper 提供单表 CRUD，额外 SQL 位于本接口注解或同名 mapper XML。
 * 行锁查询需加入业务事务，锁的获取顺序由上层 Service 统一约束。
 */
package com.catgal.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.catgal.server.domain.po.StorageFile;
import java.util.List;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface StorageFileMapper extends BaseMapper<StorageFile> {
    @Select("SELECT UTC_TIMESTAMP(6)")
    LocalDateTime currentTime();

    @Select("SELECT * FROM storage_file WHERE id=#{id} FOR UPDATE")
    StorageFile selectForUpdate(String id);

    @Select("SELECT * FROM storage_file WHERE purpose IN ('AVATAR','GAME_COVER','AI_BOT_AVATAR','AI_BOT_PORTRAIT') AND "
            + "((status='CLEANUP_PENDING' AND next_attempt_at<=UTC_TIMESTAMP(6)) "
            + "OR (status='UPLOADING' AND next_attempt_at<=UTC_TIMESTAMP(6))) "
            + "ORDER BY next_attempt_at,id LIMIT #{limit}")
    List<StorageFile> selectCleanupBatch(@Param("limit") int limit);

    @Select("SELECT f.* FROM storage_file f JOIN `user` u ON u.avatar_file_id=f.id AND u.id=f.owner_id "
            + "WHERE f.id=#{id} AND f.purpose='AVATAR' AND f.status='READY'")
    StorageFile selectPublicAvatar(String id);

    @Select("SELECT f.* FROM storage_file f JOIN game g ON g.cover_file_id=f.id AND g.id=f.business_id "
            + "WHERE f.id=#{id} AND f.purpose='GAME_COVER' AND f.status='READY' AND (g.status=1 OR #{admin}=TRUE)")
    StorageFile selectVisibleGameCover(@Param("id") String id, @Param("admin") boolean admin);

    @Select("SELECT f.* FROM storage_file f JOIN ai_bot b ON b.id=f.business_id AND b.user_id=f.owner_id "
            + "WHERE f.id=#{id} AND f.owner_id=#{owner} AND f.purpose=#{purpose} AND f.status='READY' "
            + "AND b.kind='custom' AND ((f.purpose='AI_BOT_AVATAR' "
            + "AND b.avatar_url=CONCAT('/api/files/ai-bot-avatars/',f.id)) "
            + "OR (f.purpose='AI_BOT_PORTRAIT' AND b.portrait_url=CONCAT('/api/files/ai-bot-portraits/',f.id)))")
    StorageFile selectOwnedAiBotImage(@Param("id") String id, @Param("purpose") String purpose,
                                     @Param("owner") long owner);
}
