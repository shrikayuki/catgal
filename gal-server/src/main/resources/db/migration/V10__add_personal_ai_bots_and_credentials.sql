-- Run manually after V10 precheck. Keep AI_PERSONALIZATION_ENABLED=false in both services.
-- MySQL 8 DDL commits implicitly. Back up all three existing AI tables before execution.
CREATE TABLE ai_user_credential (
 id BIGINT NOT NULL AUTO_INCREMENT,
 user_id BIGINT NOT NULL,
 provider_code VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 label VARCHAR(64) NOT NULL,
 ciphertext VARBINARY(2048) NULL,
 nonce VARBINARY(12) NULL,
 encryption_key_id VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
 masked_suffix CHAR(4) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 status TINYINT NOT NULL DEFAULT 0,
 revision BIGINT NOT NULL DEFAULT 1,
 create_time DATETIME(6) NOT NULL,
 update_time DATETIME(6) NOT NULL,
 PRIMARY KEY(id), UNIQUE KEY uk_ai_credential_owner(id,user_id),
 KEY idx_ai_credential_user(user_id,status,id),
 CONSTRAINT fk_ai_credential_user_v10 FOREIGN KEY(user_id) REFERENCES user(id) ON DELETE RESTRICT,
 CONSTRAINT chk_ai_credential_state_v10 CHECK(
  (status=0 AND ciphertext IS NULL AND nonce IS NULL AND encryption_key_id IS NULL) OR
  (status=1 AND ciphertext IS NOT NULL AND OCTET_LENGTH(ciphertext)>=16 AND nonce IS NOT NULL AND OCTET_LENGTH(nonce)=12 AND encryption_key_id IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE ai_bot (
 id BIGINT NOT NULL AUTO_INCREMENT,
 user_id BIGINT NOT NULL,
 template_id BIGINT NOT NULL,
 kind VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 platform_template_id BIGINT NULL,
 name VARCHAR(64) NOT NULL,
 avatar_url VARCHAR(1024) NOT NULL DEFAULT '',
 portrait_url VARCHAR(1024) NOT NULL DEFAULT '',
 credential_id BIGINT NULL,
 model VARCHAR(100) NOT NULL,
 prompt_version INT NOT NULL DEFAULT 1,
 revision BIGINT NOT NULL DEFAULT 1,
 status TINYINT NOT NULL DEFAULT 1,
 create_time DATETIME(6) NOT NULL,
 update_time DATETIME(6) NOT NULL,
 PRIMARY KEY(id),
 UNIQUE KEY uk_ai_bot_default(user_id,platform_template_id),
 UNIQUE KEY uk_ai_bot_owner(id,user_id),
 UNIQUE KEY uk_ai_bot_owner_template(id,user_id,template_id),
 KEY idx_ai_bot_user(user_id,status,id),
 CONSTRAINT fk_ai_bot_user_v10 FOREIGN KEY(user_id) REFERENCES user(id) ON DELETE RESTRICT,
 CONSTRAINT fk_ai_bot_template_v10 FOREIGN KEY(template_id) REFERENCES ai_girl(id) ON DELETE RESTRICT,
 CONSTRAINT fk_ai_bot_credential_v10 FOREIGN KEY(credential_id,user_id) REFERENCES ai_user_credential(id,user_id) ON DELETE RESTRICT,
 CONSTRAINT chk_ai_bot_kind_v10 CHECK(
  (kind='platform' AND platform_template_id IS NOT NULL AND platform_template_id=template_id) OR
  (kind='custom' AND platform_template_id IS NULL AND credential_id IS NOT NULL)),
 CONSTRAINT chk_ai_bot_status_v10 CHECK(status IN (0,1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE ai_bot_prompt_version (
 id BIGINT NOT NULL AUTO_INCREMENT,
 bot_id BIGINT NOT NULL,
 version INT NOT NULL,
 persona TEXT NOT NULL,
 system_prompt MEDIUMTEXT NOT NULL,
 create_time DATETIME(6) NOT NULL,
 PRIMARY KEY(id), UNIQUE KEY uk_ai_bot_prompt_version(bot_id,version),
 UNIQUE KEY uk_ai_bot_prompt_identity(id,bot_id),
 CONSTRAINT fk_ai_bot_prompt_bot_v10 FOREIGN KEY(bot_id) REFERENCES ai_bot(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- A private platform-bot instance per existing user/template pair. Keep legacy IDs and data.
-- model='' means the configured platform model is resolved at generation time.
INSERT INTO ai_bot(user_id,template_id,kind,platform_template_id,name,avatar_url,model,create_time,update_time)
 SELECT DISTINCT s.user_id,g.id,'platform',g.id,g.name,COALESCE(g.avatar_url,''),'',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)
 FROM user_session s JOIN ai_girl g ON g.id=s.girl_id;
INSERT INTO ai_bot_prompt_version(bot_id,version,persona,system_prompt,create_time)
 SELECT b.id,1,'',g.system_prompt,UTC_TIMESTAMP(6) FROM ai_bot b JOIN ai_girl g ON g.id=b.template_id;

ALTER TABLE user_session
 ADD COLUMN bot_id BIGINT NULL DEFAULT NULL,
 ADD KEY idx_ai_session_bot(user_id,bot_id,status,id),
 ADD CONSTRAINT fk_ai_session_bot_v10 FOREIGN KEY(bot_id,user_id,girl_id) REFERENCES ai_bot(id,user_id,template_id) ON DELETE RESTRICT;
UPDATE user_session s JOIN ai_bot b ON b.user_id=s.user_id AND b.platform_template_id=s.girl_id SET s.bot_id=b.id;

ALTER TABLE ai_message
 ADD COLUMN bot_id BIGINT NULL DEFAULT NULL,
 ADD COLUMN prompt_version_id BIGINT NULL DEFAULT NULL,
 ADD COLUMN credential_id BIGINT NULL DEFAULT NULL,
 ADD COLUMN credential_revision BIGINT NULL DEFAULT NULL,
 ADD COLUMN provider_code VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL DEFAULT NULL,
 ADD CONSTRAINT fk_ai_message_bot_v10 FOREIGN KEY(bot_id,user_id) REFERENCES ai_bot(id,user_id) ON DELETE RESTRICT,
 ADD CONSTRAINT fk_ai_message_prompt_v10 FOREIGN KEY(prompt_version_id,bot_id) REFERENCES ai_bot_prompt_version(id,bot_id) ON DELETE RESTRICT,
 ADD CONSTRAINT fk_ai_message_credential_v10 FOREIGN KEY(credential_id,user_id) REFERENCES ai_user_credential(id,user_id) ON DELETE RESTRICT,
 ADD CONSTRAINT chk_ai_message_prompt_v10 CHECK(prompt_version_id IS NULL OR bot_id IS NOT NULL);
UPDATE ai_message m JOIN user_session s ON s.session_id=m.session_id SET m.bot_id=s.bot_id;
-- Historical prompt snapshots cannot be inferred from today's template. Leave those references NULL.
