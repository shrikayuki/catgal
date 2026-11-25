-- Both problem counts must be zero. Back up ai_message before migration.
SELECT COUNT(*) AS column_already_exists FROM information_schema.columns
 WHERE table_schema=DATABASE() AND table_name='ai_message' AND column_name='recommendations';
SELECT 1-COUNT(*) AS missing_message_table FROM information_schema.tables
 WHERE table_schema=DATABASE() AND table_name='ai_message';
