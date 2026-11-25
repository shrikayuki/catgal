-- 仅在停止新版应用且功能未生效时执行。已使用应保留数据并评估向前修复。
DELIMITER $$
CREATE PROCEDURE rollback_auth_token_revocation_v22()
BEGIN
    IF EXISTS (SELECT 1 FROM `user` WHERE auth_version <> 0)
        OR EXISTS (SELECT 1 FROM auth_revoked_token WHERE expires_at > UTC_TIMESTAMP(6)) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Auth revocation already in use, do not downgrade';
    END IF;
    DROP TABLE auth_revoked_token;
    ALTER TABLE `user` DROP COLUMN auth_version;
END$$
DELIMITER ;
CALL rollback_auth_token_revocation_v22();
DROP PROCEDURE rollback_auth_token_revocation_v22;
