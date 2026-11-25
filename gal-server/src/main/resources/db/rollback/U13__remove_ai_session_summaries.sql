-- Disable AI_SESSION_SUMMARY_ENABLED and stop AI traffic before rollback.
-- Only an unused V13 table can be rolled back. Even cleared rows must be reviewed and exported.
DELIMITER //
CREATE PROCEDURE catgal_require_unused_ai_v13()
BEGIN
 IF EXISTS(SELECT 1 FROM ai_session_summary LIMIT 1) THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='V13 summary rows exist. Export and review rollback first';
 END IF;
 DROP TRIGGER trg_ai_summary_session_deleted_v13;
 DROP TABLE ai_session_summary;
END//
DELIMITER ;
CALL catgal_require_unused_ai_v13();
DROP PROCEDURE catgal_require_unused_ai_v13;
