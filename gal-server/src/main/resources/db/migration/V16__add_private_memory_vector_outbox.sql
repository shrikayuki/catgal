-- Execute manually after all V16 prechecks return zero. Back up ai_memory and ai_bot first.
-- MySQL 8 DDL commits implicitly. Keep AI_MEMORY_VECTOR_ENABLED=false until both services deploy.
-- No plaintext is added to these tables. Existing confirmed memories remain in ai_memory.
CREATE TABLE ai_memory_vector_settings (
 bot_id BIGINT NOT NULL,
 user_id BIGINT NOT NULL,
 enabled TINYINT NOT NULL DEFAULT 0,
 revision BIGINT NOT NULL DEFAULT 1,
 create_time DATETIME(6) NOT NULL,
 update_time DATETIME(6) NOT NULL,
 PRIMARY KEY(bot_id),
 KEY idx_memory_vector_settings_owner(bot_id,user_id),
 CONSTRAINT fk_memory_vector_settings_bot_v16 FOREIGN KEY(bot_id,user_id)
  REFERENCES ai_bot(id,user_id) ON DELETE RESTRICT,
 CONSTRAINT chk_memory_vector_settings_v16 CHECK(enabled IN (0,1) AND revision>=1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Intentionally retain version tombstones after source deletion. No FK to the source row.
CREATE TABLE ai_memory_vector_outbox (
 memory_id BIGINT NOT NULL,
 user_id BIGINT NOT NULL,
 bot_id BIGINT NOT NULL,
 version BIGINT NOT NULL DEFAULT 1,
 pending TINYINT NOT NULL DEFAULT 1,
 attempts INT NOT NULL DEFAULT 0,
 next_attempt_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 last_error VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
 dirty_since DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 update_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(memory_id),
 KEY idx_memory_vector_pending(pending,next_attempt_at,memory_id),
 KEY idx_memory_vector_owner(user_id,bot_id,memory_id),
 CONSTRAINT chk_memory_vector_outbox_v16 CHECK(version>=1 AND pending IN (0,1) AND attempts>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

DELIMITER //
CREATE TRIGGER trg_memory_vector_insert_v16 AFTER INSERT ON ai_memory FOR EACH ROW
BEGIN
 INSERT INTO ai_memory_vector_outbox(memory_id,user_id,bot_id,next_attempt_at,dirty_since,update_time) VALUES(NEW.id,NEW.user_id,NEW.bot_id,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
 ON DUPLICATE KEY UPDATE user_id=NEW.user_id,bot_id=NEW.bot_id,version=version+1,
  dirty_since=IF(pending=0,UTC_TIMESTAMP(6),dirty_since),pending=1,attempts=0,
  next_attempt_at=UTC_TIMESTAMP(6),last_error=NULL,update_time=UTC_TIMESTAMP(6);
END//
CREATE TRIGGER trg_memory_vector_update_v16 AFTER UPDATE ON ai_memory FOR EACH ROW
BEGIN
 IF NOT (OLD.id<=>NEW.id AND OLD.user_id<=>NEW.user_id AND OLD.bot_id<=>NEW.bot_id
   AND OLD.content<=>NEW.content AND OLD.memory_type<=>NEW.memory_type
   AND OLD.status<=>NEW.status AND OLD.revision<=>NEW.revision) THEN
  INSERT INTO ai_memory_vector_outbox(memory_id,user_id,bot_id,next_attempt_at,dirty_since,update_time) VALUES(NEW.id,NEW.user_id,NEW.bot_id,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
  ON DUPLICATE KEY UPDATE user_id=NEW.user_id,bot_id=NEW.bot_id,version=version+1,
   dirty_since=IF(pending=0,UTC_TIMESTAMP(6),dirty_since),pending=1,attempts=0,
   next_attempt_at=UTC_TIMESTAMP(6),last_error=NULL,update_time=UTC_TIMESTAMP(6);
  IF OLD.id<>NEW.id THEN
   INSERT INTO ai_memory_vector_outbox(memory_id,user_id,bot_id,next_attempt_at,dirty_since,update_time) VALUES(OLD.id,OLD.user_id,OLD.bot_id,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
   ON DUPLICATE KEY UPDATE version=version+1,
    dirty_since=IF(pending=0,UTC_TIMESTAMP(6),dirty_since),pending=1,attempts=0,
    next_attempt_at=UTC_TIMESTAMP(6),last_error=NULL,update_time=UTC_TIMESTAMP(6);
  END IF;
 END IF;
END//
CREATE TRIGGER trg_memory_vector_delete_v16 AFTER DELETE ON ai_memory FOR EACH ROW
BEGIN
 INSERT INTO ai_memory_vector_outbox(memory_id,user_id,bot_id,next_attempt_at,dirty_since,update_time) VALUES(OLD.id,OLD.user_id,OLD.bot_id,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
 ON DUPLICATE KEY UPDATE version=version+1,
  dirty_since=IF(pending=0,UTC_TIMESTAMP(6),dirty_since),pending=1,attempts=0,
  next_attempt_at=UTC_TIMESTAMP(6),last_error=NULL,update_time=UTC_TIMESTAMP(6);
END//
CREATE TRIGGER trg_memory_vector_bot_update_v16 AFTER UPDATE ON ai_bot FOR EACH ROW
BEGIN
 IF NOT (OLD.memory_enabled<=>NEW.memory_enabled AND OLD.status<=>NEW.status
         AND OLD.user_id<=>NEW.user_id) THEN
  INSERT INTO ai_memory_vector_outbox(memory_id,user_id,bot_id,next_attempt_at,dirty_since,update_time)
   SELECT id,user_id,bot_id,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6) FROM ai_memory WHERE bot_id=NEW.id ORDER BY id
  ON DUPLICATE KEY UPDATE version=version+1,
   dirty_since=IF(pending=0,UTC_TIMESTAMP(6),dirty_since),pending=1,attempts=0,
   next_attempt_at=UTC_TIMESTAMP(6),last_error=NULL,update_time=UTC_TIMESTAMP(6);
 END IF;
END//
CREATE TRIGGER trg_memory_vector_grant_insert_v16 AFTER INSERT ON ai_memory_vector_settings FOR EACH ROW
BEGIN
 INSERT INTO ai_memory_vector_outbox(memory_id,user_id,bot_id,next_attempt_at,dirty_since,update_time)
  SELECT id,user_id,bot_id,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6) FROM ai_memory WHERE bot_id=NEW.bot_id ORDER BY id
 ON DUPLICATE KEY UPDATE version=version+1,
  dirty_since=IF(pending=0,UTC_TIMESTAMP(6),dirty_since),pending=1,attempts=0,
  next_attempt_at=UTC_TIMESTAMP(6),last_error=NULL,update_time=UTC_TIMESTAMP(6);
END//
CREATE TRIGGER trg_memory_vector_grant_update_v16 AFTER UPDATE ON ai_memory_vector_settings FOR EACH ROW
BEGIN
 IF NOT (OLD.enabled<=>NEW.enabled AND OLD.revision<=>NEW.revision AND OLD.user_id<=>NEW.user_id) THEN
  INSERT INTO ai_memory_vector_outbox(memory_id,user_id,bot_id,next_attempt_at,dirty_since,update_time)
   SELECT id,user_id,bot_id,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6) FROM ai_memory WHERE bot_id=NEW.bot_id ORDER BY id
  ON DUPLICATE KEY UPDATE version=version+1,
   dirty_since=IF(pending=0,UTC_TIMESTAMP(6),dirty_since),pending=1,attempts=0,
   next_attempt_at=UTC_TIMESTAMP(6),last_error=NULL,update_time=UTC_TIMESTAMP(6);
 END IF;
END//
CREATE TRIGGER trg_memory_vector_grant_delete_v16 AFTER DELETE ON ai_memory_vector_settings FOR EACH ROW
BEGIN
 INSERT INTO ai_memory_vector_outbox(memory_id,user_id,bot_id,next_attempt_at,dirty_since,update_time)
  SELECT id,user_id,bot_id,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6) FROM ai_memory WHERE bot_id=OLD.bot_id ORDER BY id
 ON DUPLICATE KEY UPDATE version=version+1,
  dirty_since=IF(pending=0,UTC_TIMESTAMP(6),dirty_since),pending=1,attempts=0,
  next_attempt_at=UTC_TIMESTAMP(6),last_error=NULL,update_time=UTC_TIMESTAMP(6);
END//
DELIMITER ;

-- Establish a monotonic row even for existing inactive sources. Consent defaults to no row/OFF.
INSERT IGNORE INTO ai_memory_vector_outbox(memory_id,user_id,bot_id,next_attempt_at,dirty_since,update_time)
 SELECT id,user_id,bot_id,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6) FROM ai_memory ORDER BY id;
