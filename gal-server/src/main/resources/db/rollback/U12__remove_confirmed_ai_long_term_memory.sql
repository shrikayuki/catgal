-- Disable AI_LONG_TERM_MEMORY_ENABLED and stop AI traffic before rollback.
-- This guarded rollback permits unused V12 only, including no soft-deleted memory history.
-- Export and retain existing memories before planning any destructive rollback separately.
DELIMITER //
CREATE PROCEDURE catgal_require_unused_ai_v12()
BEGIN
 IF EXISTS(SELECT 1 FROM ai_memory LIMIT 1)
    OR EXISTS(SELECT 1 FROM ai_bot WHERE memory_enabled<>0 LIMIT 1) THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='V12 memory data/settings exist. Export and review rollback first';
 END IF;
 DROP TABLE ai_memory;
 ALTER TABLE ai_bot DROP CHECK chk_ai_bot_memory_v12, DROP COLUMN memory_enabled;
END//
DELIMITER ;
CALL catgal_require_unused_ai_v12();
DROP PROCEDURE catgal_require_unused_ai_v12;
