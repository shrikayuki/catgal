-- Execute on catgal before enabling GAME_SEARCH_ENABLED.
-- Requires CREATE TABLE and TRIGGER privileges. MySQL DDL commits implicitly.
-- One durable revision row per game coalesces frequent counter updates.
-- Rows remain after acknowledgement to retain monotonic versions and deletion tombstones.
-- Rebuild into a fresh ES index after resetting or restoring outbox versions.
CREATE TABLE IF NOT EXISTS search_outbox (
    game_id BIGINT NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    pending TINYINT NOT NULL DEFAULT 1,
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    last_error VARCHAR(128) DEFAULT NULL,
    dirty_since DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (game_id),
    KEY idx_search_outbox_ready (pending, next_attempt_at, game_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

DELIMITER $$

DROP TRIGGER IF EXISTS trg_search_game_insert$$
CREATE TRIGGER trg_search_game_insert AFTER INSERT ON `game`
FOR EACH ROW
BEGIN
    INSERT INTO search_outbox (game_id) VALUES (NEW.id)
        ON DUPLICATE KEY UPDATE version = version + 1,
            dirty_since = IF(pending = 0, NOW(6), dirty_since), pending = 1,
            attempts = 0, next_attempt_at = NOW(6), last_error = NULL, updated_at = NOW(6);
END$$

DROP TRIGGER IF EXISTS trg_search_game_update$$
CREATE TRIGGER trg_search_game_update AFTER UPDATE ON `game`
FOR EACH ROW
BEGIN
    IF NOT (OLD.id <=> NEW.id
        AND OLD.name <=> NEW.name
        AND OLD.status <=> NEW.status
        AND OLD.release_date <=> NEW.release_date
        AND OLD.create_time <=> NEW.create_time
        AND OLD.rating <=> NEW.rating
        AND OLD.view_count <=> NEW.view_count
        AND OLD.download_count <=> NEW.download_count
        AND OLD.favorite_count <=> NEW.favorite_count
        AND OLD.types <=> NEW.types
        AND OLD.languages <=> NEW.languages
        AND OLD.platforms <=> NEW.platforms) THEN
        INSERT INTO search_outbox (game_id) VALUES (NEW.id)
        ON DUPLICATE KEY UPDATE version = version + 1,
            dirty_since = IF(pending = 0, NOW(6), dirty_since), pending = 1,
            attempts = 0, next_attempt_at = NOW(6), last_error = NULL, updated_at = NOW(6);
        IF OLD.id <> NEW.id THEN
            INSERT INTO search_outbox (game_id) VALUES (OLD.id)
        ON DUPLICATE KEY UPDATE version = version + 1,
            dirty_since = IF(pending = 0, NOW(6), dirty_since), pending = 1,
            attempts = 0, next_attempt_at = NOW(6), last_error = NULL, updated_at = NOW(6);
        END IF;
    END IF;
END$$

DROP TRIGGER IF EXISTS trg_search_game_delete$$
CREATE TRIGGER trg_search_game_delete AFTER DELETE ON `game`
FOR EACH ROW
BEGIN
    INSERT INTO search_outbox (game_id) VALUES (OLD.id)
        ON DUPLICATE KEY UPDATE version = version + 1,
            dirty_since = IF(pending = 0, NOW(6), dirty_since), pending = 1,
            attempts = 0, next_attempt_at = NOW(6), last_error = NULL, updated_at = NOW(6);
END$$

DROP TRIGGER IF EXISTS trg_search_game_tag_insert$$
CREATE TRIGGER trg_search_game_tag_insert AFTER INSERT ON `game_tag`
FOR EACH ROW
BEGIN
    INSERT INTO search_outbox (game_id) VALUES (NEW.game_id)
        ON DUPLICATE KEY UPDATE version = version + 1,
            dirty_since = IF(pending = 0, NOW(6), dirty_since), pending = 1,
            attempts = 0, next_attempt_at = NOW(6), last_error = NULL, updated_at = NOW(6);
END$$

DROP TRIGGER IF EXISTS trg_search_game_tag_update$$
CREATE TRIGGER trg_search_game_tag_update AFTER UPDATE ON `game_tag`
FOR EACH ROW
BEGIN
    INSERT INTO search_outbox (game_id) VALUES (OLD.game_id), (NEW.game_id)
        ON DUPLICATE KEY UPDATE version = version + 1,
            dirty_since = IF(pending = 0, NOW(6), dirty_since), pending = 1,
            attempts = 0, next_attempt_at = NOW(6), last_error = NULL, updated_at = NOW(6);
END$$

DROP TRIGGER IF EXISTS trg_search_game_tag_delete$$
CREATE TRIGGER trg_search_game_tag_delete AFTER DELETE ON `game_tag`
FOR EACH ROW
BEGIN
    INSERT INTO search_outbox (game_id) VALUES (OLD.game_id)
        ON DUPLICATE KEY UPDATE version = version + 1,
            dirty_since = IF(pending = 0, NOW(6), dirty_since), pending = 1,
            attempts = 0, next_attempt_at = NOW(6), last_error = NULL, updated_at = NOW(6);
END$$

DROP TRIGGER IF EXISTS trg_search_game_company_insert$$
CREATE TRIGGER trg_search_game_company_insert AFTER INSERT ON `game_company`
FOR EACH ROW
BEGIN
    INSERT INTO search_outbox (game_id) VALUES (NEW.game_id)
        ON DUPLICATE KEY UPDATE version = version + 1,
            dirty_since = IF(pending = 0, NOW(6), dirty_since), pending = 1,
            attempts = 0, next_attempt_at = NOW(6), last_error = NULL, updated_at = NOW(6);
END$$

DROP TRIGGER IF EXISTS trg_search_game_company_update$$
CREATE TRIGGER trg_search_game_company_update AFTER UPDATE ON `game_company`
FOR EACH ROW
BEGIN
    INSERT INTO search_outbox (game_id) VALUES (OLD.game_id), (NEW.game_id)
        ON DUPLICATE KEY UPDATE version = version + 1,
            dirty_since = IF(pending = 0, NOW(6), dirty_since), pending = 1,
            attempts = 0, next_attempt_at = NOW(6), last_error = NULL, updated_at = NOW(6);
END$$

DROP TRIGGER IF EXISTS trg_search_game_company_delete$$
CREATE TRIGGER trg_search_game_company_delete AFTER DELETE ON `game_company`
FOR EACH ROW
BEGIN
    INSERT INTO search_outbox (game_id) VALUES (OLD.game_id)
        ON DUPLICATE KEY UPDATE version = version + 1,
            dirty_since = IF(pending = 0, NOW(6), dirty_since), pending = 1,
            attempts = 0, next_attempt_at = NOW(6), last_error = NULL, updated_at = NOW(6);
END$$

DELIMITER ;

-- Seed existing records. Triggers already capture concurrent writes.
INSERT IGNORE INTO search_outbox (game_id) SELECT id FROM game;
