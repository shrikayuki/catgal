package com.catgal.server.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;

/** 以固定长度的 SHA-256 摘要查找撤销状态，数据库只保留到原有效期。 */
public interface RevokedAuthTokenMapper {
    @Select("SELECT EXISTS (SELECT 1 FROM auth_revoked_token WHERE token_digest=UNHEX(#{digest})"
            + " AND expires_at>UTC_TIMESTAMP(6))")
    boolean isRevoked(@Param("digest") String digest);

    @Insert("INSERT INTO auth_revoked_token (token_digest, expires_at) VALUES (UNHEX(#{digest}), #{expiresAt})"
            + " ON DUPLICATE KEY UPDATE expires_at=auth_revoked_token.expires_at")
    void revoke(@Param("digest") String digest, @Param("expiresAt") LocalDateTime expiresAt);

    @Delete("DELETE FROM auth_revoked_token WHERE expires_at<=UTC_TIMESTAMP(6) ORDER BY expires_at LIMIT 1000")
    int deleteExpiredBatch();
}
