-- All four problem counts must be zero before V15. Back up the schema first.
SELECT COUNT(*) AS conflicting_tables FROM information_schema.tables
 WHERE table_schema=DATABASE() AND table_name='ai_game_vector_outbox';
SELECT COUNT(*) AS conflicting_triggers FROM information_schema.triggers
 WHERE trigger_schema=DATABASE() AND trigger_name LIKE 'trg_ai_vec_%';
SELECT COUNT(*) AS invalid_game_ids FROM game WHERE id<=0;
SELECT (
 (SELECT COUNT(*) FROM game_tag r LEFT JOIN game g ON g.id=r.game_id LEFT JOIN tag t ON t.id=r.tag_id WHERE g.id IS NULL OR t.id IS NULL)
 +(SELECT COUNT(*) FROM game_company r LEFT JOIN game g ON g.id=r.game_id LEFT JOIN company c ON c.id=r.company_id WHERE g.id IS NULL OR c.id IS NULL)
) AS orphan_catalog_relations;

