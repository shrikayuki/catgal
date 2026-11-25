-- Read-only. All six counts must be zero before V18.
SELECT 'missing_storage_table' AS check_name, 1-COUNT(*) AS problem_count
FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='storage_file' AND engine='InnoDB';
SELECT 'incompatible_game_id' AS check_name, 1-COUNT(*) AS problem_count
FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='game' AND column_name='id'
AND data_type='bigint' AND column_type NOT LIKE '%unsigned%';
SELECT 'missing_cover_url_column' AS check_name, 1-COUNT(*) AS problem_count
FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='game' AND column_name='cover_url' AND is_nullable='YES';
SELECT 'existing_v18_objects' AS check_name,
 (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()
  AND ((table_name='game' AND column_name='cover_file_id') OR (table_name='storage_file' AND column_name='business_id')))
 +(SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema=DATABASE() AND trigger_name='trg_game_cover_delete_v18') AS problem_count;
SELECT 'unexpected_file_purpose' AS check_name, COUNT(*) AS problem_count FROM storage_file WHERE purpose<>'AVATAR';
SELECT 'invalid_avatar_owner' AS check_name, COUNT(*) AS problem_count
FROM storage_file f LEFT JOIN `user` u ON u.id=f.owner_id WHERE u.id IS NULL OR f.owner_id<=0;
