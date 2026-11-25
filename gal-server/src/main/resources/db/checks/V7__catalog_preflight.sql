-- Every invalid_rows value must be zero before V7. Review historical data manually.
SELECT 'blank_game_name' AS issue, COUNT(*) AS invalid_rows FROM game WHERE CHAR_LENGTH(TRIM(name)) = 0
UNION ALL SELECT 'blank_company_name', COUNT(*) FROM company WHERE CHAR_LENGTH(TRIM(name)) = 0
UNION ALL SELECT 'blank_tag_name', COUNT(*) FROM tag WHERE CHAR_LENGTH(TRIM(name)) = 0
UNION ALL SELECT 'game_company_missing_game', COUNT(*) FROM game_company gc LEFT JOIN game g ON g.id = gc.game_id WHERE g.id IS NULL
UNION ALL SELECT 'game_company_missing_company', COUNT(*) FROM game_company gc LEFT JOIN company c ON c.id = gc.company_id WHERE c.id IS NULL
UNION ALL SELECT 'game_tag_missing_game', COUNT(*) FROM game_tag gt LEFT JOIN game g ON g.id = gt.game_id WHERE g.id IS NULL
UNION ALL SELECT 'game_tag_missing_tag', COUNT(*) FROM game_tag gt LEFT JOIN tag t ON t.id = gt.tag_id WHERE t.id IS NULL;
