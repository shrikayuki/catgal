-- 只读预检查。全部 problem_count=0 后执行 V20，先备份 resource、game 及库结构。
SELECT 'v20_tables_exist' problem, COUNT(*) problem_count FROM information_schema.tables
 WHERE table_schema=DATABASE() AND table_name IN ('resource_review_history','resource_count_outbox')
UNION ALL
SELECT 'v20_columns_exist', COUNT(*) FROM information_schema.columns
 WHERE table_schema=DATABASE() AND table_name='resource'
 AND column_name IN ('revision','reviewed_by','reviewed_at','review_reason')
UNION ALL
SELECT 'invalid_resource_status',COUNT(*) FROM resource WHERE status NOT IN (0,1,2) OR status IS NULL
UNION ALL
SELECT 'orphan_resource_game',COUNT(*) FROM resource r LEFT JOIN game g ON g.id=r.game_id WHERE g.id IS NULL
UNION ALL
SELECT 'orphan_resource_owner',COUNT(*) FROM resource r LEFT JOIN user u ON u.id=r.user_id WHERE u.id IS NULL
UNION ALL
SELECT 'v20_trigger_names_exist',COUNT(*) FROM information_schema.triggers
 WHERE trigger_schema=DATABASE() AND trigger_name IN ('trg_resource_business_revision',
 'trg_resource_count_insert','trg_resource_count_update','trg_resource_count_delete')
UNION ALL
SELECT 'unexpected_resource_search_triggers',ABS(3-COUNT(*)) FROM information_schema.triggers
 WHERE trigger_schema=DATABASE() AND event_object_table='resource'
 AND trigger_name IN ('trg_search_resource_insert','trg_search_resource_update','trg_search_resource_delete');
