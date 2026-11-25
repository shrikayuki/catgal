-- All issue counts must be zero. Run in catgal before V9.
SELECT 'invalid_session_identity' AS issue, COUNT(*) AS issue_count
FROM user_session WHERE session_id IS NULL OR session_id = ''
 OR NOT REGEXP_LIKE(session_id, '^[A-Za-z0-9_-]{1,256}$', 'c')
UNION ALL
SELECT 'duplicate_session_id', COUNT(*) FROM (
 SELECT BINARY session_id FROM user_session GROUP BY BINARY session_id HAVING COUNT(*) > 1
) duplicates
UNION ALL
SELECT 'invalid_session_state', COUNT(*) FROM user_session
WHERE status IS NULL OR status NOT IN (0,1) OR message_count IS NULL OR message_count < 0
UNION ALL
SELECT 'orphan_or_mismatched_owner', COUNT(*) FROM user_session s
LEFT JOIN user u ON u.id=s.user_id LEFT JOIN ai_girl g ON g.id=s.girl_id
WHERE u.id IS NULL OR g.id IS NULL OR BINARY s.girl_code <> BINARY g.girl_code;

SHOW CREATE TABLE user_session;
SHOW CREATE TABLE ai_girl;
