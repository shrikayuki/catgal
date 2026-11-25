-- Disable RESOURCE_SEARCH_ENABLED before rollback. Resource revision history is discarded.
-- Rebuild a fresh resource index after reinstalling this migration.
DELIMITER $$
DROP TRIGGER IF EXISTS trg_search_tag_name_insert$$
DROP TRIGGER IF EXISTS trg_search_tag_name_update$$
DROP TRIGGER IF EXISTS trg_search_tag_name_delete$$
DROP TRIGGER IF EXISTS trg_search_company_name_insert$$
DROP TRIGGER IF EXISTS trg_search_company_name_update$$
DROP TRIGGER IF EXISTS trg_search_company_name_delete$$
DROP TRIGGER IF EXISTS trg_search_resource_insert$$
DROP TRIGGER IF EXISTS trg_search_resource_update$$
DROP TRIGGER IF EXISTS trg_search_resource_delete$$

DROP TRIGGER IF EXISTS trg_search_game_update$$
CREATE TRIGGER trg_search_game_update AFTER UPDATE ON game
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

DELIMITER ;
DROP TABLE IF EXISTS resource_search_outbox;
