-- Empty-data rollback only. Run while both services have AI_PERSISTENCE_ENABLED=false.
-- Restore from a verified backup if AI sessions/messages already exist.
DELIMITER //
CREATE PROCEDURE catgal_require_empty_ai_v9()
BEGIN
 IF EXISTS(SELECT 1 FROM ai_message LIMIT 1) OR EXISTS(SELECT 1 FROM user_session LIMIT 1) THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='AI data exists. Export and plan recovery before rollback';
 END IF;
 DROP TABLE ai_message;
 ALTER TABLE user_session
 DROP FOREIGN KEY fk_ai_session_user_v9,
 DROP FOREIGN KEY fk_ai_session_girl_v9,
 DROP INDEX uk_ai_session_id,
 DROP INDEX idx_ai_session_owner_page,
 DROP INDEX idx_ai_session_girl_id,
 DROP COLUMN title,
 DROP COLUMN memory_version,
 DROP COLUMN active_message_id,
 DROP COLUMN active_lease_until,
 DROP COLUMN update_time,
 MODIFY girl_code VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
 MODIFY session_id VARCHAR(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
 ADD UNIQUE KEY uk_girl_code(girl_code);
END//
DELIMITER ;
CALL catgal_require_empty_ai_v9();
DROP PROCEDURE catgal_require_empty_ai_v9;
-- The bootstrap character is retained as ordinary existing configuration.
