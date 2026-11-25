-- Stop cover uploads and cleanup workers first. Used cover data needs an export/reconciliation plan.
DELIMITER //
CREATE PROCEDURE catgal_require_unused_game_covers_v18()
BEGIN
 IF EXISTS(SELECT 1 FROM game WHERE cover_file_id IS NOT NULL LIMIT 1)
    OR EXISTS(SELECT 1 FROM storage_file WHERE purpose<>'AVATAR' LIMIT 1) THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='V18 cover data exists. Export and reconcile objects before rollback';
 END IF;
 DROP TRIGGER trg_game_cover_delete_v18;
 ALTER TABLE game DROP FOREIGN KEY fk_game_cover_ref_v18,
  DROP INDEX idx_game_cover_ref_v18,DROP COLUMN cover_file_id;
 ALTER TABLE storage_file DROP INDEX uk_storage_business_file,DROP INDEX idx_storage_business,DROP COLUMN business_id;
END//
DELIMITER ;
CALL catgal_require_unused_game_covers_v18();
DROP PROCEDURE catgal_require_unused_game_covers_v18;
