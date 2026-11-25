-- Disable the feature and stop AI traffic. Only completely unused V14 may be rolled back.
-- Export/review settings, extraction history and candidate history before a destructive plan.
DELIMITER //
CREATE PROCEDURE catgal_require_unused_ai_v14()
BEGIN
 IF EXISTS(SELECT 1 FROM ai_memory_suggestion_settings LIMIT 1)
    OR EXISTS(SELECT 1 FROM ai_memory_extraction LIMIT 1)
    OR EXISTS(SELECT 1 FROM ai_memory_candidate LIMIT 1) THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='V14 data exists. Export and review rollback first';
 END IF;
 DROP TRIGGER trg_ai_candidates_session_deleted_v14;
 DROP TABLE ai_memory_candidate;
 DROP TABLE ai_memory_extraction;
 DROP TABLE ai_memory_suggestion_settings;
END//
DELIMITER ;
CALL catgal_require_unused_ai_v14();
DROP PROCEDURE catgal_require_unused_ai_v14;
