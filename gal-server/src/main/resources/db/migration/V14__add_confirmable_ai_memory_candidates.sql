-- Execute manually after all six V14 checks return zero. MySQL 8 DDL commits implicitly.
-- Keep AI_MEMORY_CANDIDATES_ENABLED=false during migration. Back up user_session first.
-- Suggestions are untrusted derived data. Only explicit confirmation writes ai_memory.
CREATE TABLE ai_memory_suggestion_settings (
 bot_id BIGINT NOT NULL,
 user_id BIGINT NOT NULL,
 enabled TINYINT NOT NULL DEFAULT 0,
 enabled_after_message_id BIGINT NOT NULL DEFAULT 0,
 revision BIGINT NOT NULL DEFAULT 1,
 create_time DATETIME(6) NOT NULL,
 update_time DATETIME(6) NOT NULL,
 PRIMARY KEY(bot_id),
 KEY idx_ai_suggestion_owner(bot_id,user_id),
 CONSTRAINT fk_ai_suggestion_bot_v14 FOREIGN KEY(bot_id,user_id)
  REFERENCES ai_bot(id,user_id) ON DELETE RESTRICT,
 CONSTRAINT chk_ai_suggestion_enabled_v14 CHECK(enabled IN (0,1)),
 CONSTRAINT chk_ai_suggestion_revision_v14 CHECK(revision>=1 AND enabled_after_message_id>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE ai_memory_extraction (
 source_message_id BIGINT NOT NULL,
 user_id BIGINT NOT NULL,
 bot_id BIGINT NOT NULL,
 session_id VARCHAR(256) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 source_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 settings_revision BIGINT NOT NULL,
 generation_message_id BIGINT NOT NULL,
 generation_token CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 lease_until DATETIME(6) NOT NULL,
 state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'running',
 error_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
 create_time DATETIME(6) NOT NULL,
 update_time DATETIME(6) NOT NULL,
 PRIMARY KEY(source_message_id),
 KEY idx_ai_extraction_owner(user_id,bot_id,update_time,source_message_id),
 KEY idx_ai_extraction_bot_owner(bot_id,user_id),
 KEY idx_ai_extraction_session(session_id,state),
 KEY idx_ai_extraction_generation(generation_message_id),
 CONSTRAINT fk_ai_extraction_source_v14 FOREIGN KEY(source_message_id)
  REFERENCES ai_message(id) ON DELETE RESTRICT,
 CONSTRAINT fk_ai_extraction_generation_v14 FOREIGN KEY(generation_message_id)
  REFERENCES ai_message(id) ON DELETE RESTRICT,
 CONSTRAINT fk_ai_extraction_bot_v14 FOREIGN KEY(bot_id,user_id)
  REFERENCES ai_bot(id,user_id) ON DELETE RESTRICT,
 CONSTRAINT fk_ai_extraction_session_v14 FOREIGN KEY(session_id)
  REFERENCES user_session(session_id) ON DELETE RESTRICT,
 CONSTRAINT chk_ai_extraction_revision_v14 CHECK(settings_revision>=1),
 CONSTRAINT chk_ai_extraction_state_v14 CHECK(state IN
  ('running','completed','empty','failed','interrupted','invalidated'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE ai_memory_candidate (
 id BIGINT NOT NULL AUTO_INCREMENT,
 user_id BIGINT NOT NULL,
 bot_id BIGINT NOT NULL,
 source_message_id BIGINT NOT NULL,
 source_session_id VARCHAR(256) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 source_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 content_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 content TEXT NOT NULL,
 memory_type VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 revision BIGINT NOT NULL DEFAULT 1,
 state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'pending',
 confirmed_memory_id BIGINT NULL,
 create_time DATETIME(6) NOT NULL,
 update_time DATETIME(6) NOT NULL,
 PRIMARY KEY(id),
 UNIQUE KEY uk_ai_candidate_source(user_id,bot_id,source_message_id,content_hash),
 KEY idx_ai_candidate_owner(user_id,bot_id,state,id),
 KEY idx_ai_candidate_bot_owner(bot_id,user_id),
 KEY idx_ai_candidate_session(source_session_id,state),
 KEY idx_ai_candidate_job(source_message_id),
 KEY idx_ai_candidate_memory(confirmed_memory_id),
 CONSTRAINT fk_ai_candidate_bot_v14 FOREIGN KEY(bot_id,user_id)
  REFERENCES ai_bot(id,user_id) ON DELETE RESTRICT,
 CONSTRAINT fk_ai_candidate_job_v14 FOREIGN KEY(source_message_id)
  REFERENCES ai_memory_extraction(source_message_id) ON DELETE RESTRICT,
 CONSTRAINT fk_ai_candidate_session_v14 FOREIGN KEY(source_session_id)
  REFERENCES user_session(session_id) ON DELETE RESTRICT,
 CONSTRAINT fk_ai_candidate_memory_v14 FOREIGN KEY(confirmed_memory_id)
  REFERENCES ai_memory(id) ON DELETE RESTRICT,
 CONSTRAINT chk_ai_candidate_revision_v14 CHECK(revision>=1),
 CONSTRAINT chk_ai_candidate_type_v14 CHECK(memory_type IN ('preference','fact')),
 CONSTRAINT chk_ai_candidate_state_v14 CHECK(
  (state='pending' AND CHAR_LENGTH(TRIM(content)) BETWEEN 1 AND 1000 AND confirmed_memory_id IS NULL)
  OR (state='confirmed' AND content='' AND confirmed_memory_id IS NOT NULL)
  OR (state IN ('rejected','invalidated') AND content='' AND confirmed_memory_id IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Enforce erasure even with the Python feature globally disabled.
DELIMITER //
CREATE TRIGGER trg_ai_candidates_session_deleted_v14
AFTER UPDATE ON user_session FOR EACH ROW
BEGIN
 IF NEW.status=0 THEN
  UPDATE ai_memory_candidate
   SET content='',state='invalidated',revision=revision+1,update_time=UTC_TIMESTAMP(6)
   WHERE source_session_id=NEW.session_id AND state='pending';
  UPDATE ai_memory_extraction
   SET state='invalidated',error_code='SOURCE_NOT_AVAILABLE',update_time=UTC_TIMESTAMP(6)
   WHERE session_id=NEW.session_id AND state='running';
 END IF;
END//
DELIMITER ;
