-- All five problem counts must be zero. Back up ai_girl, user_session and ai_message.
SELECT COUNT(*) AS conflicting_new_tables FROM information_schema.tables
 WHERE table_schema=DATABASE() AND table_name IN ('ai_user_credential','ai_bot','ai_bot_prompt_version');
SELECT COUNT(*) AS invalid_template FROM ai_girl
 WHERE system_prompt IS NULL OR TRIM(system_prompt)='' OR girl_code NOT REGEXP '^[A-Za-z0-9_-]{1,32}$';
SELECT COUNT(*) AS invalid_session_owner FROM user_session s
 LEFT JOIN user u ON u.id=s.user_id LEFT JOIN ai_girl g ON g.id=s.girl_id
 WHERE u.id IS NULL OR g.id IS NULL OR BINARY s.girl_code<>BINARY g.girl_code;
SELECT COUNT(*) AS mismatched_message_owner FROM ai_message m
 LEFT JOIN user_session s ON s.session_id=m.session_id
 WHERE s.id IS NULL OR m.user_id<>s.user_id;
SELECT COUNT(*) AS conflicting_new_columns FROM information_schema.columns
 WHERE table_schema=DATABASE() AND
 ((table_name='user_session' AND column_name='bot_id') OR
  (table_name='ai_message' AND column_name IN ('bot_id','prompt_version_id','credential_id','credential_revision','provider_code')));
