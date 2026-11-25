-- Read-only. All five problem counts must be zero before V17.
SELECT 'missing_user_table' AS check_name, 1-COUNT(*) AS problem_count
FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='user' AND engine='InnoDB';
SELECT 'incompatible_user_id' AS check_name, 1-COUNT(*) AS problem_count
FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='user'
AND column_name='id' AND data_type='bigint' AND column_type NOT LIKE '%unsigned%';
SELECT 'missing_legacy_avatar_column' AS check_name, 1-COUNT(*) AS problem_count
FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='user'
AND column_name='avatar_url' AND data_type='varchar' AND is_nullable='YES';
SELECT 'existing_v17_objects' AS check_name,
 (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='storage_file')
 +(SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='user' AND column_name='avatar_file_id') AS problem_count;
SELECT 'invalid_user_identity' AS check_name, COUNT(*) AS problem_count FROM `user` WHERE id<=0;
