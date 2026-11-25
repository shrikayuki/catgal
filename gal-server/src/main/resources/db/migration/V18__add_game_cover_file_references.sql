-- Run manually after V18 prechecks are zero. Back up game, storage_file and the schema.
-- DDL commits implicitly. Legacy URLs remain available for historical reads.
-- Nullable business_id permits the previous application to finish avatar uploads during deployment.
ALTER TABLE storage_file
 ADD COLUMN business_id BIGINT NULL AFTER purpose,
 ADD UNIQUE KEY uk_storage_business_file(id,business_id),
 ADD KEY idx_storage_business(purpose,business_id,id);
UPDATE storage_file SET business_id=owner_id WHERE purpose='AVATAR' AND business_id IS NULL;

ALTER TABLE game
 ADD COLUMN cover_file_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER cover_url,
 ADD KEY idx_game_cover_ref_v18(cover_file_id,id),
 ADD CONSTRAINT fk_game_cover_ref_v18 FOREIGN KEY(cover_file_id,id)
  REFERENCES storage_file(id,business_id) ON DELETE RESTRICT;

DELIMITER //
CREATE TRIGGER trg_game_cover_delete_v18 AFTER DELETE ON game FOR EACH ROW
BEGIN
 UPDATE storage_file SET
  next_attempt_at=IF(status='UPLOADING',GREATEST(next_attempt_at,UTC_TIMESTAMP(6)+INTERVAL 1 HOUR),UTC_TIMESTAMP(6)),
  status='CLEANUP_PENDING',update_time=UTC_TIMESTAMP(6)
 WHERE purpose='GAME_COVER' AND business_id=OLD.id AND status IN ('UPLOADING','READY');
END//
DELIMITER ;
