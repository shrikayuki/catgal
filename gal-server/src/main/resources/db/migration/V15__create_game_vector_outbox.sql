-- V15 public-game semantic index source. Run after its read-only precheck.
-- Keep AI_SEMANTIC_SEARCH_ENABLED=false during migration. MySQL 8 DDL commits implicitly.
-- Independent pending/version state. Existing search_outbox is neither consumed nor modified.
-- No game foreign key: retain versioned tombstones after physical deletion.
CREATE TABLE ai_game_vector_outbox (
    game_id BIGINT NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    pending TINYINT NOT NULL DEFAULT 1,
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6) NOT NULL,
    last_error_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    dirty_since DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY(game_id),
    KEY idx_ai_game_vector_ready(pending,next_attempt_at,game_id),
    CONSTRAINT chk_ai_game_vector_version_v15 CHECK(version>0),
    CONSTRAINT chk_ai_game_vector_pending_v15 CHECK(pending IN (0,1)),
    CONSTRAINT chk_ai_game_vector_attempts_v15 CHECK(attempts>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Hot counters, rating and cover-only changes do not request new embeddings.
DELIMITER $$

CREATE TRIGGER trg_ai_vec_game_ai AFTER INSERT ON `game`
FOR EACH ROW
BEGIN
    INSERT INTO ai_game_vector_outbox(game_id, next_attempt_at, dirty_since, updated_at)
        VALUES(NEW.id,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
        ON DUPLICATE KEY UPDATE version=version+1,
            dirty_since=IF(pending=0,UTC_TIMESTAMP(6),dirty_since),pending=1,
            attempts=0,next_attempt_at=UTC_TIMESTAMP(6),last_error_code=NULL,updated_at=UTC_TIMESTAMP(6);
END$$

CREATE TRIGGER trg_ai_vec_game_au AFTER UPDATE ON `game`
FOR EACH ROW
BEGIN
    IF NOT (OLD.id <=> NEW.id AND OLD.name <=> NEW.name
        AND OLD.introduction <=> NEW.introduction AND OLD.status <=> NEW.status
        AND OLD.release_date <=> NEW.release_date AND OLD.types <=> NEW.types
        AND OLD.languages <=> NEW.languages AND OLD.platforms <=> NEW.platforms) THEN
    INSERT INTO ai_game_vector_outbox(game_id, next_attempt_at, dirty_since, updated_at)
        VALUES(NEW.id,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
        ON DUPLICATE KEY UPDATE version=version+1,
            dirty_since=IF(pending=0,UTC_TIMESTAMP(6),dirty_since),pending=1,
            attempts=0,next_attempt_at=UTC_TIMESTAMP(6),last_error_code=NULL,updated_at=UTC_TIMESTAMP(6);
        IF OLD.id <> NEW.id THEN
    INSERT INTO ai_game_vector_outbox(game_id, next_attempt_at, dirty_since, updated_at)
        VALUES(OLD.id,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
        ON DUPLICATE KEY UPDATE version=version+1,
            dirty_since=IF(pending=0,UTC_TIMESTAMP(6),dirty_since),pending=1,
            attempts=0,next_attempt_at=UTC_TIMESTAMP(6),last_error_code=NULL,updated_at=UTC_TIMESTAMP(6);
        END IF;
    END IF;
END$$

CREATE TRIGGER trg_ai_vec_game_ad AFTER DELETE ON `game`
FOR EACH ROW
BEGIN
    INSERT INTO ai_game_vector_outbox(game_id, next_attempt_at, dirty_since, updated_at)
        VALUES(OLD.id,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
        ON DUPLICATE KEY UPDATE version=version+1,
            dirty_since=IF(pending=0,UTC_TIMESTAMP(6),dirty_since),pending=1,
            attempts=0,next_attempt_at=UTC_TIMESTAMP(6),last_error_code=NULL,updated_at=UTC_TIMESTAMP(6);
END$$

CREATE TRIGGER trg_ai_vec_gt_ai AFTER INSERT ON `game_tag`
FOR EACH ROW
BEGIN
    INSERT INTO ai_game_vector_outbox(game_id, next_attempt_at, dirty_since, updated_at)
        VALUES(NEW.game_id,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
        ON DUPLICATE KEY UPDATE version=version+1,
            dirty_since=IF(pending=0,UTC_TIMESTAMP(6),dirty_since),pending=1,
            attempts=0,next_attempt_at=UTC_TIMESTAMP(6),last_error_code=NULL,updated_at=UTC_TIMESTAMP(6);
END$$

CREATE TRIGGER trg_ai_vec_gt_au AFTER UPDATE ON `game_tag`
FOR EACH ROW
BEGIN
    INSERT INTO ai_game_vector_outbox(game_id, next_attempt_at, dirty_since, updated_at)
        SELECT id,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)
        FROM (SELECT OLD.game_id AS id UNION SELECT NEW.game_id AS id) changed ORDER BY id
        ON DUPLICATE KEY UPDATE version=version+1,
            dirty_since=IF(pending=0,UTC_TIMESTAMP(6),dirty_since),pending=1,
            attempts=0,next_attempt_at=UTC_TIMESTAMP(6),last_error_code=NULL,updated_at=UTC_TIMESTAMP(6);
END$$

CREATE TRIGGER trg_ai_vec_gt_ad AFTER DELETE ON `game_tag`
FOR EACH ROW
BEGIN
    INSERT INTO ai_game_vector_outbox(game_id, next_attempt_at, dirty_since, updated_at)
        VALUES(OLD.game_id,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
        ON DUPLICATE KEY UPDATE version=version+1,
            dirty_since=IF(pending=0,UTC_TIMESTAMP(6),dirty_since),pending=1,
            attempts=0,next_attempt_at=UTC_TIMESTAMP(6),last_error_code=NULL,updated_at=UTC_TIMESTAMP(6);
END$$

CREATE TRIGGER trg_ai_vec_gc_ai AFTER INSERT ON `game_company`
FOR EACH ROW
BEGIN
    INSERT INTO ai_game_vector_outbox(game_id, next_attempt_at, dirty_since, updated_at)
        VALUES(NEW.game_id,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
        ON DUPLICATE KEY UPDATE version=version+1,
            dirty_since=IF(pending=0,UTC_TIMESTAMP(6),dirty_since),pending=1,
            attempts=0,next_attempt_at=UTC_TIMESTAMP(6),last_error_code=NULL,updated_at=UTC_TIMESTAMP(6);
END$$

CREATE TRIGGER trg_ai_vec_gc_au AFTER UPDATE ON `game_company`
FOR EACH ROW
BEGIN
    INSERT INTO ai_game_vector_outbox(game_id, next_attempt_at, dirty_since, updated_at)
        SELECT id,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)
        FROM (SELECT OLD.game_id AS id UNION SELECT NEW.game_id AS id) changed ORDER BY id
        ON DUPLICATE KEY UPDATE version=version+1,
            dirty_since=IF(pending=0,UTC_TIMESTAMP(6),dirty_since),pending=1,
            attempts=0,next_attempt_at=UTC_TIMESTAMP(6),last_error_code=NULL,updated_at=UTC_TIMESTAMP(6);
END$$

CREATE TRIGGER trg_ai_vec_gc_ad AFTER DELETE ON `game_company`
FOR EACH ROW
BEGIN
    INSERT INTO ai_game_vector_outbox(game_id, next_attempt_at, dirty_since, updated_at)
        VALUES(OLD.game_id,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
        ON DUPLICATE KEY UPDATE version=version+1,
            dirty_since=IF(pending=0,UTC_TIMESTAMP(6),dirty_since),pending=1,
            attempts=0,next_attempt_at=UTC_TIMESTAMP(6),last_error_code=NULL,updated_at=UTC_TIMESTAMP(6);
END$$

CREATE TRIGGER trg_ai_vec_tag_au AFTER UPDATE ON `tag`
FOR EACH ROW
BEGIN
    IF NOT (OLD.id <=> NEW.id AND OLD.name <=> NEW.name) THEN
    INSERT INTO ai_game_vector_outbox(game_id, next_attempt_at, dirty_since, updated_at)
        SELECT DISTINCT game_id,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)
        FROM game_tag WHERE tag_id IN (OLD.id,NEW.id) ORDER BY game_id
        ON DUPLICATE KEY UPDATE version=version+1,
            dirty_since=IF(pending=0,UTC_TIMESTAMP(6),dirty_since),pending=1,
            attempts=0,next_attempt_at=UTC_TIMESTAMP(6),last_error_code=NULL,updated_at=UTC_TIMESTAMP(6);
    END IF;
END$$

CREATE TRIGGER trg_ai_vec_tag_ad AFTER DELETE ON `tag`
FOR EACH ROW
BEGIN
    INSERT INTO ai_game_vector_outbox(game_id, next_attempt_at, dirty_since, updated_at)
        SELECT DISTINCT game_id,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)
        FROM game_tag WHERE tag_id=OLD.id ORDER BY game_id
        ON DUPLICATE KEY UPDATE version=version+1,
            dirty_since=IF(pending=0,UTC_TIMESTAMP(6),dirty_since),pending=1,
            attempts=0,next_attempt_at=UTC_TIMESTAMP(6),last_error_code=NULL,updated_at=UTC_TIMESTAMP(6);
END$$

CREATE TRIGGER trg_ai_vec_co_au AFTER UPDATE ON `company`
FOR EACH ROW
BEGIN
    IF NOT (OLD.id <=> NEW.id AND OLD.name <=> NEW.name) THEN
    INSERT INTO ai_game_vector_outbox(game_id, next_attempt_at, dirty_since, updated_at)
        SELECT DISTINCT game_id,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)
        FROM game_company WHERE company_id IN (OLD.id,NEW.id) ORDER BY game_id
        ON DUPLICATE KEY UPDATE version=version+1,
            dirty_since=IF(pending=0,UTC_TIMESTAMP(6),dirty_since),pending=1,
            attempts=0,next_attempt_at=UTC_TIMESTAMP(6),last_error_code=NULL,updated_at=UTC_TIMESTAMP(6);
    END IF;
END$$

CREATE TRIGGER trg_ai_vec_co_ad AFTER DELETE ON `company`
FOR EACH ROW
BEGIN
    INSERT INTO ai_game_vector_outbox(game_id, next_attempt_at, dirty_since, updated_at)
        SELECT DISTINCT game_id,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)
        FROM game_company WHERE company_id=OLD.id ORDER BY game_id
        ON DUPLICATE KEY UPDATE version=version+1,
            dirty_since=IF(pending=0,UTC_TIMESTAMP(6),dirty_since),pending=1,
            attempts=0,next_attempt_at=UTC_TIMESTAMP(6),last_error_code=NULL,updated_at=UTC_TIMESTAMP(6);
END$$

DELIMITER ;

-- Triggers capture concurrent changes before this seed. Do not reset existing versions.
INSERT IGNORE INTO ai_game_vector_outbox(game_id,next_attempt_at,dirty_since,updated_at)
    SELECT id,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6) FROM game;

