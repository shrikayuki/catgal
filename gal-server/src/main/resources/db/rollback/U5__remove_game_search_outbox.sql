-- Disable GAME_SEARCH_ENABLED before rollback. Pending synchronization events will be discarded.
-- Rebuild a fresh ES index before enabling search after any subsequent reinstallation.
DROP TRIGGER IF EXISTS trg_search_game_insert;
DROP TRIGGER IF EXISTS trg_search_game_update;
DROP TRIGGER IF EXISTS trg_search_game_delete;
DROP TRIGGER IF EXISTS trg_search_game_tag_insert;
DROP TRIGGER IF EXISTS trg_search_game_tag_update;
DROP TRIGGER IF EXISTS trg_search_game_tag_delete;
DROP TRIGGER IF EXISTS trg_search_game_company_insert;
DROP TRIGGER IF EXISTS trg_search_game_company_update;
DROP TRIGGER IF EXISTS trg_search_game_company_delete;
DROP TABLE IF EXISTS search_outbox;
