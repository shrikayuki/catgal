-- Execute manually after V9 precheck reports zero issues. MySQL 8 DDL commits implicitly.
-- Keep AI_PERSISTENCE_ENABLED=false during migration and deploy both services before enabling.
-- Existing rows and IDs are retained. Back up user_session and ai_girl before execution.
ALTER TABLE user_session
 DROP INDEX uk_girl_code,
 MODIFY girl_code VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
 MODIFY session_id VARCHAR(256) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 ADD COLUMN title VARCHAR(100) NOT NULL DEFAULT '新对话',
 ADD COLUMN memory_version BIGINT NOT NULL DEFAULT 0,
 ADD COLUMN active_message_id BIGINT NULL,
 ADD COLUMN active_lease_until DATETIME(6) NULL,
 ADD COLUMN update_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 ADD UNIQUE KEY uk_ai_session_id (session_id),
 ADD KEY idx_ai_session_owner_page (user_id, status, id),
 ADD KEY idx_ai_session_girl_id (girl_id),
 ADD CONSTRAINT fk_ai_session_user_v9 FOREIGN KEY (user_id) REFERENCES user(id) ON DELETE RESTRICT,
 ADD CONSTRAINT fk_ai_session_girl_v9 FOREIGN KEY (girl_id) REFERENCES ai_girl(id) ON DELETE RESTRICT;

UPDATE user_session SET update_time=COALESCE(last_chat_time,create_time);

CREATE TABLE ai_message (
 id BIGINT NOT NULL AUTO_INCREMENT,
 session_id VARCHAR(256) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 user_id BIGINT NOT NULL,
 role VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 content MEDIUMTEXT NOT NULL,
 status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 reply_to_id BIGINT NULL COMMENT 'assistant points to the original user message',
 regenerated_from_id BIGINT NULL,
 client_message_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
 request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
 request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
 generation_token CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
 model VARCHAR(100) NULL,
 prompt_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
 error_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
 create_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 update_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 finished_time DATETIME(6) NULL,
 PRIMARY KEY(id),
 UNIQUE KEY uk_ai_message_client (user_id, client_message_id),
 UNIQUE KEY uk_ai_message_request (request_id),
 KEY idx_ai_message_history (session_id,id),
 KEY idx_ai_message_turn (session_id,reply_to_id,status,id),
 CONSTRAINT fk_ai_message_session_v9 FOREIGN KEY(session_id) REFERENCES user_session(session_id) ON DELETE RESTRICT,
 CONSTRAINT fk_ai_message_reply_v9 FOREIGN KEY(reply_to_id) REFERENCES ai_message(id) ON DELETE RESTRICT,
 CONSTRAINT fk_ai_message_regenerate_v9 FOREIGN KEY(regenerated_from_id) REFERENCES ai_message(id) ON DELETE RESTRICT,
 CONSTRAINT chk_ai_message_role_v9 CHECK(role IN ('user','assistant')),
 CONSTRAINT chk_ai_message_status_v9 CHECK(status IN ('running','completed','failed','interrupted')),
 CONSTRAINT chk_ai_message_identity_v9 CHECK(
  (role='user' AND status='completed' AND reply_to_id IS NULL AND client_message_id IS NULL)
  OR (role='assistant' AND reply_to_id IS NOT NULL AND client_message_id IS NOT NULL
   AND request_id IS NOT NULL AND request_hash IS NOT NULL AND generation_token IS NOT NULL)
 )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='AI messages and durable generation idempotency';

-- Bootstrap the website assistant only if its code is absent. Existing configuration is retained.
INSERT INTO ai_girl(girl_code,name,system_prompt,status,like_count)
SELECT 'catgal-assistant','Catgal社区助手',
 '你是 Catgal Galgame 社区的聊天助手。使用自然、友善的中文交流，帮助用户讨论作品与整理想法。对不确定的信息明确说明，不编造本站的资源地址、用户信息或权限状态。',1,0
WHERE NOT EXISTS(SELECT 1 FROM ai_girl WHERE girl_code='catgal-assistant');
