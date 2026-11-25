-- Run manually after the five V12 prechecks return zero. Keep the memory feature disabled.
-- MySQL 8 DDL commits implicitly. Back up ai_bot first. Existing bots default to memory OFF.
-- Only user-confirmed facts/preferences are stored. This migration adds no extraction or vector index.
ALTER TABLE ai_bot
 ADD COLUMN memory_enabled TINYINT NOT NULL DEFAULT 0,
 ADD CONSTRAINT chk_ai_bot_memory_v12 CHECK(memory_enabled IN (0,1));

CREATE TABLE ai_memory (
 id BIGINT NOT NULL AUTO_INCREMENT,
 user_id BIGINT NOT NULL,
 bot_id BIGINT NOT NULL,
 memory_type VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 content TEXT NOT NULL,
 source_message_id BIGINT NULL,
 revision BIGINT NOT NULL DEFAULT 1,
 status TINYINT NOT NULL DEFAULT 1,
 create_time DATETIME(6) NOT NULL,
 update_time DATETIME(6) NOT NULL,
 PRIMARY KEY(id),
 KEY idx_ai_memory_owner(user_id,bot_id,status,id),
 KEY idx_ai_memory_bot_owner(bot_id,user_id),
 KEY idx_ai_memory_source(source_message_id),
 CONSTRAINT fk_ai_memory_bot_v12 FOREIGN KEY(bot_id,user_id)
  REFERENCES ai_bot(id,user_id) ON DELETE RESTRICT,
 CONSTRAINT fk_ai_memory_source_v12 FOREIGN KEY(source_message_id)
  REFERENCES ai_message(id) ON DELETE RESTRICT,
 CONSTRAINT chk_ai_memory_type_v12 CHECK(memory_type IN ('preference','fact')),
 CONSTRAINT chk_ai_memory_revision_v12 CHECK(revision>=1),
 CONSTRAINT chk_ai_memory_state_v12 CHECK(
  (status=1 AND CHAR_LENGTH(TRIM(content)) BETWEEN 1 AND 1000) OR
  (status=0 AND content='' AND source_message_id IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
