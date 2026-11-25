-- Read-only V13 precheck. Keep AI_SESSION_SUMMARY_ENABLED=false during deployment.
-- All six problem_count values must be zero. Back up user_session before execution.
SELECT 'missing_v12_objects' AS check_name,
 (4-(SELECT COUNT(*) FROM information_schema.tables
      WHERE table_schema=DATABASE() AND table_name IN ('user_session','ai_bot','ai_message','ai_memory')))
 +(2-(SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()
       AND ((table_name='ai_bot' AND column_name='memory_enabled')
         OR (table_name='ai_message' AND column_name='recommendations')))) AS problem_count;

SELECT 'existing_v13_objects' AS check_name,
 (SELECT COUNT(*) FROM information_schema.tables
   WHERE table_schema=DATABASE() AND table_name='ai_session_summary')
 +(SELECT COUNT(*) FROM information_schema.triggers
   WHERE trigger_schema=DATABASE() AND trigger_name='trg_ai_summary_session_deleted_v13') AS problem_count;

SELECT 'invalid_session_owner_or_bot' AS check_name, COUNT(*) AS problem_count
 FROM user_session s LEFT JOIN user u ON u.id=s.user_id
 LEFT JOIN ai_bot b ON b.id=s.bot_id
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
      AND SUM(sub_part IS NOT NULL)=0),0,1) AS problem_count;

SELECT 'incompatible_identifier_columns' AS check_name,
 (2-(SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema=DATABASE() AND table_name IN ('user_session','ai_message')
      AND column_name='session_id' AND data_type='varchar' AND character_maximum_length=256
      AND character_set_name='ascii' AND collation_name='ascii_bin'))
 +(4-(SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema=DATABASE() AND data_type='bigint' AND column_type NOT LIKE '%unsigned%'
       AND ((table_name='ai_bot' AND column_name IN ('id','user_id'))
         OR (table_name='ai_message' AND column_name='id')
         OR (table_name='user_session' AND column_name='memory_version')))) AS problem_count;
