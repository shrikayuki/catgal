-- Disable AI_SEMANTIC_SEARCH_ENABLED and stop the vector worker before rollback.
-- This removes only derived delivery state. Public game/catalog data is retained.
-- Reinstalling/resetting this revision table REQUIRES a fresh ES physical index.
DROP TRIGGER IF EXISTS trg_ai_vec_game_ai;
DROP TRIGGER IF EXISTS trg_ai_vec_game_au;
DROP TRIGGER IF EXISTS trg_ai_vec_game_ad;
DROP TRIGGER IF EXISTS trg_ai_vec_gt_ai;
DROP TRIGGER IF EXISTS trg_ai_vec_gt_au;
DROP TRIGGER IF EXISTS trg_ai_vec_gt_ad;
DROP TRIGGER IF EXISTS trg_ai_vec_gc_ai;
DROP TRIGGER IF EXISTS trg_ai_vec_gc_au;
DROP TRIGGER IF EXISTS trg_ai_vec_gc_ad;
DROP TRIGGER IF EXISTS trg_ai_vec_tag_au;
DROP TRIGGER IF EXISTS trg_ai_vec_tag_ad;
DROP TRIGGER IF EXISTS trg_ai_vec_co_au;
DROP TRIGGER IF EXISTS trg_ai_vec_co_ad;
DROP TABLE ai_game_vector_outbox;

