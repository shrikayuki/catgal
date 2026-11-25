-- Empty-use rollback only, with AI_PERSONALIZATION_ENABLED=false in both services.
DELIMITER //
CREATE PROCEDURE catgal_require_unused_ai_v10()
BEGIN
 IF EXISTS(SELECT 1 FROM ai_user_credential LIMIT 1)
 OR EXISTS(SELECT 1 FROM ai_bot WHERE kind='custom' OR revision<>1 LIMIT 1)
 OR EXISTS(SELECT 1 FROM ai_message WHERE prompt_version_id IS NOT NULL LIMIT 1) THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='V10 data is in use. Export and plan recovery before rollback';
 END IF;
 ALTER TABLE ai_message DROP FOREIGN KEY fk_ai_message_bot_v10,
  DROP FOREIGN KEY fk_ai_message_prompt_v10, DROP FOREIGN KEY fk_ai_message_credential_v10,
  DROP CHECK chk_ai_message_prompt_v10,
  DROP COLUMN bot_id,DROP COLUMN prompt_version_id,DROP COLUMN credential_id,
  DROP COLUMN credential_revision,DROP COLUMN provider_code;
 ALTER TABLE user_session DROP FOREIGN KEY fk_ai_session_bot_v10,
  DROP INDEX idx_ai_session_bot,DROP COLUMN bot_id;
 DROP TABLE ai_bot_prompt_version;
 DROP TABLE ai_bot;
 DROP TABLE ai_user_credential;
END//
DELIMITER ;
CALL catgal_require_unused_ai_v10();
DROP PROCEDURE catgal_require_unused_ai_v10;
