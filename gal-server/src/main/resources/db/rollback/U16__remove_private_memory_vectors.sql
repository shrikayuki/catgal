-- Stop private workers and disable the global feature. Export/review all private-vector records.
-- Only a fully unused V16 can be rolled back. Seeded source rows also prevent this guard passing.
DELIMITER //
CREATE PROCEDURE catgal_require_unused_memory_vectors_v16()
BEGIN
 IF EXISTS(SELECT 1 FROM ai_memory_vector_settings LIMIT 1)
    OR EXISTS(SELECT 1 FROM ai_memory_vector_outbox LIMIT 1) THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='V16 data exists. Export and review rollback first';
 END IF;
 DROP TRIGGER trg_memory_vector_insert_v16;
 DROP TRIGGER trg_memory_vector_update_v16;
 DROP TRIGGER trg_memory_vector_delete_v16;
 DROP TRIGGER trg_memory_vector_bot_update_v16;
 DROP TRIGGER trg_memory_vector_grant_insert_v16;
 DROP TRIGGER trg_memory_vector_grant_update_v16;
 DROP TRIGGER trg_memory_vector_grant_delete_v16;
 DROP TABLE ai_memory_vector_settings;
 DROP TABLE ai_memory_vector_outbox;
END//
DELIMITER ;
CALL catgal_require_unused_memory_vectors_v16();
DROP PROCEDURE catgal_require_unused_memory_vectors_v16;
