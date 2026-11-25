-- Requires V5. Execute while deploying search v2, before rebuilding both indexes.
-- MySQL DDL commits implicitly. Requires CREATE TABLE and TRIGGER privileges.
-- Existing game/resource data is retained. Outboxes keep monotonic per-entity revisions.
CREATE TABLE IF NOT EXISTS resource_search_outbox (
    resource_id BIGINT NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    pending TINYINT NOT NULL DEFAULT 1,
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    last_error VARCHAR(128) DEFAULT NULL,
    dirty_since DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (resource_id),
    KEY idx_resource_search_ready (pending, next_attempt_at, resource_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

DELIMITER $$

DROP TRIGGER IF EXISTS trg_search_game_update$$
CREATE TRIGGER trg_search_game_update AFTER UPDATE ON game
FOR EACH ROW
BEGIN
    IF NOT (OLD.id <=> NEW.id
        AND OLD.name <=> NEW.name
        AND OLD.introduction <=> NEW.introduction
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

DROP TRIGGER IF EXISTS trg_search_tag_name_insert$$
CREATE TRIGGER trg_search_tag_name_insert AFTER INSERT ON `tag`
FOR EACH ROW
BEGIN
    INSERT INTO search_outbox (game_id)
        SELECT DISTINCT game_id FROM game_tag WHERE tag_id IN (NEW.id) ORDER BY game_id
        ON DUPLICATE KEY UPDATE version = version + 1,
            dirty_since = IF(pending = 0, NOW(6), dirty_since), pending = 1,
            attempts = 0, next_attempt_at = NOW(6), last_error = NULL, updated_at = NOW(6);
END$$

DROP TRIGGER IF EXISTS trg_search_tag_name_update$$
CREATE TRIGGER trg_search_tag_name_update AFTER UPDATE ON `tag`
FOR EACH ROW
BEGIN
    INSERT INTO search_outbox (game_id)
        SELECT DISTINCT game_id FROM game_tag WHERE tag_id IN (OLD.id, NEW.id) ORDER BY game_id
        ON DUPLICATE KEY UPDATE version = version + 1,
            dirty_since = IF(pending = 0, NOW(6), dirty_since), pending = 1,
            attempts = 0, next_attempt_at = NOW(6), last_error = NULL, updated_at = NOW(6);
END$$

DROP TRIGGER IF EXISTS trg_search_tag_name_delete$$
CREATE TRIGGER trg_search_tag_name_delete AFTER DELETE ON `tag`
FOR EACH ROW
BEGIN
    INSERT INTO search_outbox (game_id)
        SELECT DISTINCT game_id FROM game_tag WHERE tag_id IN (OLD.id) ORDER BY game_id
        ON DUPLICATE KEY UPDATE version = version + 1,
            dirty_since = IF(pending = 0, NOW(6), dirty_since), pending = 1,
            attempts = 0, next_attempt_at = NOW(6), last_error = NULL, updated_at = NOW(6);
END$$

DROP TRIGGER IF EXISTS trg_search_company_name_insert$$
CREATE TRIGGER trg_search_company_name_insert AFTER INSERT ON `company`
FOR EACH ROW
BEGIN
    INSERT INTO search_outbox (game_id)
        SELECT DISTINCT game_id FROM game_company WHERE company_id IN (NEW.id) ORDER BY game_id
        ON DUPLICATE KEY UPDATE version = version + 1,
            dirty_since = IF(pending = 0, NOW(6), dirty_since), pending = 1,
            attempts = 0, next_attempt_at = NOW(6), last_error = NULL, updated_at = NOW(6);
END$$

DROP TRIGGER IF EXISTS trg_search_company_name_update$$
CREATE TRIGGER trg_search_company_name_update AFTER UPDATE ON `company`
FOR EACH ROW
BEGIN
    INSERT INTO search_outbox (game_id)
        SELECT DISTINCT game_id FROM game_company WHERE company_id IN (OLD.id, NEW.id) ORDER BY game_id
        ON DUPLICATE KEY UPDATE version = version + 1,
            dirty_since = IF(pending = 0, NOW(6), dirty_since), pending = 1,
            attempts = 0, next_attempt_at = NOW(6), last_error = NULL, updated_at = NOW(6);
END$$

DROP TRIGGER IF EXISTS trg_search_company_name_delete$$
CREATE TRIGGER trg_search_company_name_delete AFTER DELETE ON `company`
FOR EACH ROW
BEGIN
    INSERT INTO search_outbox (game_id)
        SELECT DISTINCT game_id FROM game_company WHERE company_id IN (OLD.id) ORDER BY game_id
        ON DUPLICATE KEY UPDATE version = version + 1,
            dirty_since = IF(pending = 0, NOW(6), dirty_since), pending = 1,
            attempts = 0, next_attempt_at = NOW(6), last_error = NULL, updated_at = NOW(6);
END$$

DROP TRIGGER IF EXISTS trg_search_resource_insert$$
CREATE TRIGGER trg_search_resource_insert AFTER INSERT ON resource
FOR EACH ROW
BEGIN
    INSERT INTO resource_search_outbox (resource_id) VALUES (NEW.id)
        ON DUPLICATE KEY UPDATE version = version + 1,
            dirty_since = IF(pending = 0, NOW(6), dirty_since), pending = 1,
            attempts = 0, next_attempt_at = NOW(6), last_error = NULL, updated_at = NOW(6);
END$$

DROP TRIGGER IF EXISTS trg_search_resource_update$$
CREATE TRIGGER trg_search_resource_update AFTER UPDATE ON resource
FOR EACH ROW
BEGIN
    IF NOT (OLD.id <=> NEW.id
        AND OLD.name <=> NEW.name
        AND OLD.status <=> NEW.status
        AND OLD.game_id <=> NEW.game_id
        AND OLD.user_id <=> NEW.user_id) THEN
        INSERT INTO resource_search_outbox (resource_id) VALUES (NEW.id)
        ON DUPLICATE KEY UPDATE version = version + 1,
            dirty_since = IF(pending = 0, NOW(6), dirty_since), pending = 1,
            attempts = 0, next_attempt_at = NOW(6), last_error = NULL, updated_at = NOW(6);
        IF OLD.id <> NEW.id THEN
            INSERT INTO resource_search_outbox (resource_id) VALUES (OLD.id)
        ON DUPLICATE KEY UPDATE version = version + 1,
            dirty_since = IF(pending = 0, NOW(6), dirty_since), pending = 1,
            attempts = 0, next_attempt_at = NOW(6), last_error = NULL, updated_at = NOW(6);
        END IF;
    END IF;
END$$

DROP TRIGGER IF EXISTS trg_search_resource_delete$$
CREATE TRIGGER trg_search_resource_delete AFTER DELETE ON resource
FOR EACH ROW
BEGIN
    INSERT INTO resource_search_outbox (resource_id) VALUES (OLD.id)
        ON DUPLICATE KEY UPDATE version = version + 1,
            dirty_since = IF(pending = 0, NOW(6), dirty_since), pending = 1,
            attempts = 0, next_attempt_at = NOW(6), last_error = NULL, updated_at = NOW(6);
END$$

DELIMITER ;

INSERT IGNORE INTO resource_search_outbox (resource_id) SELECT id FROM resource;

-- Requeue all games so the additional searchable fields are populated.
INSERT INTO search_outbox (game_id) SELECT id FROM game
    ON DUPLICATE KEY UPDATE version = version + 1,
            dirty_since = IF(pending = 0, NOW(6), dirty_since), pending = 1,
            attempts = 0, next_attempt_at = NOW(6), last_error = NULL, updated_at = NOW(6);
