-- Read-only. All six problem_count values must be zero before V14.
-- Keep AI_MEMORY_CANDIDATES_ENABLED=false until migration and both services are deployed.
SELECT 'missing_prior_ai_objects' AS check_name,
 (5-(SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()
   AND table_name IN ('user_session','ai_bot','ai_message','ai_memory','ai_session_summary')))
 +(1-(SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()
   AND table_name='ai_bot' AND column_name='memory_enabled')) AS problem_count;

SELECT 'existing_v14_objects' AS check_name,
 (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()
   AND table_name IN ('ai_memory_suggestion_settings','ai_memory_extraction','ai_memory_candidate'))
 +(SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema=DATABASE()
   AND trigger_name='trg_ai_candidates_session_deleted_v14') AS problem_count;

SELECT 'invalid_session_owner_or_bot' AS check_name, COUNT(*) AS problem_count
 FROM user_session s LEFT JOIN user u ON u.id=s.user_id LEFT JOIN ai_bot b ON b.id=s.bot_id
 WHERE u.id IS NULL OR (s.bot_id IS NOT NULL
   AND (b.id IS NULL OR b.user_id<>s.user_id OR b.template_id<>s.girl_id));

SELECT 'orphan_or_mismatched_message_source' AS check_name, COUNT(*) AS problem_count
 FROM ai_message m LEFT JOIN user_session s ON s.session_id=m.session_id
 LEFT JOIN ai_message p ON p.id=m.reply_to_id
 WHERE s.id IS NULL OR m.user_id<>s.user_id
  OR (m.bot_id IS NOT NULL AND NOT (m.bot_id<=>s.bot_id))
  OR (m.role='assistant' AND (p.id IS NULL OR p.role<>'user'
    OR p.user_id<>m.user_id OR BINARY p.session_id<>BINARY m.session_id));

SELECT 'missing_referenced_unique_keys' AS check_name,
 IF(EXISTS(SELECT 1 FROM information_schema.statistics
   WHERE table_schema=DATABASE() AND table_name='user_session' AND non_unique=0
   GROUP BY index_name HAVING GROUP_CONCAT(column_name ORDER BY seq_in_index)='session_id'
    AND SUM(sub_part IS NOT NULL)=0),0,1)
 +IF(EXISTS(SELECT 1 FROM information_schema.statistics
   WHERE table_schema=DATABASE() AND table_name='ai_bot' AND non_unique=0
   GROUP BY index_name HAVING GROUP_CONCAT(column_name ORDER BY seq_in_index)='id,user_id'
    AND SUM(sub_part IS NOT NULL)=0),0,1)
 +(2-(SELECT COUNT(DISTINCT table_name) FROM information_schema.statistics
   WHERE table_schema=DATABASE() AND table_name IN ('ai_message','ai_memory')
    AND index_name='PRIMARY' AND column_name='id' AND non_unique=0)) AS problem_count;

SELECT 'incompatible_identifier_columns' AS check_name,
 (2-(SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()
   AND table_name IN ('user_session','ai_message') AND column_name='session_id'
   AND data_type='varchar' AND character_maximum_length=256
   AND character_set_name='ascii' AND collation_name='ascii_bin'))
 +(4-(SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()
   AND data_type='bigint' AND column_type NOT LIKE '%unsigned%'
   AND ((table_name='ai_bot' AND column_name IN ('id','user_id'))
     OR (table_name IN ('ai_message','ai_memory') AND column_name='id')))) AS problem_count;
