-- Read-only. Run in catgal after V11, with AI_LONG_TERM_MEMORY_ENABLED=false.
-- All five problem_count values must be zero. Back up ai_bot before migration.
SELECT 'missing_v10_tables' AS check_name,
  4-COUNT(*) AS problem_count FROM information_schema.tables
 WHERE table_schema=DATABASE()
   AND table_name IN ('ai_bot','ai_bot_prompt_version','user_session','ai_message');
SELECT 'missing_v11_recommendations' AS check_name,
  1-COUNT(*) AS problem_count FROM information_schema.columns
 WHERE table_schema=DATABASE() AND table_name='ai_message' AND column_name='recommendations';
SELECT 'existing_v12_objects' AS check_name,
  (SELECT COUNT(*) FROM information_schema.tables
    WHERE table_schema=DATABASE() AND table_name='ai_memory')
  +(SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema=DATABASE() AND table_name='ai_bot' AND column_name='memory_enabled') AS problem_count;
SELECT 'invalid_bot_owner' AS check_name, COUNT(*) AS problem_count
 FROM ai_bot b LEFT JOIN user u ON u.id=b.user_id WHERE u.id IS NULL;
SELECT 'missing_bot_owner_unique_key' AS check_name,
  IF(EXISTS(SELECT 1 FROM information_schema.statistics
     WHERE table_schema=DATABASE() AND table_name='ai_bot' AND non_unique=0
     GROUP BY index_name HAVING GROUP_CONCAT(column_name ORDER BY seq_in_index)='id,user_id'),0,1) AS problem_count;
