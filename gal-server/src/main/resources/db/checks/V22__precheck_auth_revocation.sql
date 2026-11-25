-- 在目标库选定 catgal 后执行。两项均为 0 时可以继续 V22。
SELECT 'user_auth_version_exists' AS check_name, COUNT(*) AS issue_count
FROM information_schema.columns
WHERE table_schema = DATABASE() AND table_name = 'user' AND column_name = 'auth_version'
UNION ALL
SELECT 'revoked_auth_token_table_exists', COUNT(*)
FROM information_schema.tables
WHERE table_schema = DATABASE() AND table_name = 'auth_revoked_token';
