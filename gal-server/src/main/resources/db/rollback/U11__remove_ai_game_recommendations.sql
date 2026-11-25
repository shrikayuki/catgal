-- Disable game tools in both services. Export recommendation data before rollback.
DELIMITER //
CREATE PROCEDURE catgal_require_unused_ai_v11()
BEGIN
 IF EXISTS(SELECT 1 FROM ai_message WHERE recommendations IS NOT NULL LIMIT 1) THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Recommendation data exists. Export before rollback';
 END IF;
 ALTER TABLE ai_message DROP COLUMN recommendations;
END//
DELIMITER ;
CALL catgal_require_unused_ai_v11();
DROP PROCEDURE catgal_require_unused_ai_v11;
