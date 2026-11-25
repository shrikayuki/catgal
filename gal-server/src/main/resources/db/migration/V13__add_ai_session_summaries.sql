-- Execute manually after all six V13 prechecks return zero.
-- MySQL 8 DDL commits implicitly. Keep AI_SESSION_SUMMARY_ENABLED=false until both services deploy.
-- The existing user_session.session_id is VARCHAR(256) ASCII/ASCII_BIN, verified by precheck.
-- Summaries are derived, untrusted data, separate from user-confirmed ai_memory facts.
CREATE TABLE ai_session_summary (
 session_id VARCHAR(256) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 user_id BIGINT NOT NULL,
 bot_id BIGINT NOT NULL,
 enabled TINYINT NOT NULL DEFAULT 0,
 summary TEXT NOT NULL DEFAULT (''),
 covered_user_message_id BIGINT NULL,
 covered_assistant_message_id BIGINT NULL,
 covered_turns INT NOT NULL DEFAULT 0,
 source_memory_version BIGINT NULL,
 revision BIGINT NOT NULL DEFAULT 1,
 status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'empty',
 last_error_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
 create_time DATETIME(6) NOT NULL,
 update_time DATETIME(6) NOT NULL,
 PRIMARY KEY(session_id),
 KEY idx_ai_summary_owner(user_id,bot_id),
 KEY idx_ai_summary_bot_owner(bot_id,user_id),
 KEY idx_ai_summary_user_message(covered_user_message_id),
 KEY idx_ai_summary_assistant_message(covered_assistant_message_id),
 CONSTRAINT fk_ai_summary_session_v13 FOREIGN KEY(session_id)
  REFERENCES user_session(session_id) ON DELETE RESTRICT,
 CONSTRAINT fk_ai_summary_bot_v13 FOREIGN KEY(bot_id,user_id)
  REFERENCES ai_bot(id,user_id) ON DELETE RESTRICT,
 CONSTRAINT fk_ai_summary_user_message_v13 FOREIGN KEY(covered_user_message_id)
  REFERENCES ai_message(id) ON DELETE RESTRICT,
 CONSTRAINT fk_ai_summary_assistant_message_v13 FOREIGN KEY(covered_assistant_message_id)
  REFERENCES ai_message(id) ON DELETE RESTRICT,
 CONSTRAINT chk_ai_summary_revision_v13 CHECK(revision>=1),
 CONSTRAINT chk_ai_summary_enabled_v13 CHECK(enabled IN (0,1)),
 CONSTRAINT chk_ai_summary_status_v13 CHECK(status IN ('empty','ready','failed')),
 CONSTRAINT chk_ai_summary_content_v13 CHECK(
  (summary='' AND covered_user_message_id IS NULL AND covered_assistant_message_id IS NULL
   AND source_memory_version IS NULL AND covered_turns=0)
  OR
  (CHAR_LENGTH(TRIM(summary)) BETWEEN 1 AND 3000 AND covered_user_message_id IS NOT NULL
   AND covered_assistant_message_id IS NOT NULL AND source_memory_version IS NOT NULL
   AND source_memory_version>=0 AND covered_turns>0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
 COMMENT='Opt-in per-session derived rolling summaries; untrusted reference data';

-- Clear summaries even when the Python feature flag is disabled.
-- Deletion and an in-flight summary write both lock user_session before this summary row.
DELIMITER //
CREATE TRIGGER trg_ai_summary_session_deleted_v13
AFTER UPDATE ON user_session FOR EACH ROW
BEGIN
 IF NEW.status=0 THEN
  UPDATE ai_session_summary
   SET enabled=0, summary='', covered_user_message_id=NULL, covered_assistant_message_id=NULL,
       covered_turns=0, source_memory_version=NULL, status='empty', last_error_code=NULL,
       revision=revision+1, update_time=UTC_TIMESTAMP(6)
   WHERE session_id=NEW.session_id;
 END IF;
END//
DELIMITER ;
