-- Read-only. All six counts must be zero. Keep AI_MEMORY_VECTOR_ENABLED=false.
SELECT 'missing_c1_objects' AS check_name,
 (2-(SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()
   AND table_name IN ('ai_memory','ai_bot')))
 +(1-(SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()
   AND table_name='ai_bot' AND column_name='memory_enabled')) AS problem_count;
SELECT 'existing_v16_objects' AS check_name,
 (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()
   AND table_name IN ('ai_memory_vector_settings','ai_memory_vector_outbox'))
 +(SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema=DATABASE()
   AND trigger_name LIKE 'trg_memory_vector_%_v16') AS problem_count;
SELECT 'invalid_memory_owner' AS check_name, COUNT(*) AS problem_count
 FROM ai_memory m LEFT JOIN ai_bot b ON b.id=m.bot_id
 WHERE b.id IS NULL OR b.user_id<>m.user_id;
SELECT 'invalid_memory_state' AS check_name, COUNT(*) AS problem_count
 FROM ai_memory WHERE revision<1 OR memory_type NOT IN ('preference','fact')
 OR NOT ((status=1 AND CHAR_LENGTH(TRIM(content)) BETWEEN 1 AND 1000)
       OR (status=0 AND content='' AND source_message_id IS NULL));
SELECT 'missing_bot_owner_unique' AS check_name,
 IF(EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE()
   AND table_name='ai_bot' AND non_unique=0 GROUP BY index_name
   HAVING GROUP_CONCAT(column_name ORDER BY seq_in_index)='id,user_id'
    AND SUM(sub_part IS NOT NULL)=0),0,1) AS problem_count;
SELECT 'incompatible_identity_columns' AS check_name,
 5-COUNT(*) AS problem_count FROM information_schema.columns
 WHERE table_schema=DATABASE() AND data_type='bigint' AND column_type NOT LIKE '%unsigned%'
 AND ((table_name='ai_memory' AND column_name IN ('id','bot_id','user_id'))
   OR (table_name='ai_bot' AND column_name IN ('id','user_id')));
