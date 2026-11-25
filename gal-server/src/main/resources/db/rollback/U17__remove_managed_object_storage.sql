-- Stop the new application and storage cleanup workers first.
-- Used files require a separate export/reconciliation plan before rollback.
DELIMITER //
CREATE PROCEDURE catgal_require_unused_storage_v17()
BEGIN
 IF EXISTS(SELECT 1 FROM storage_file LIMIT 1)
    OR EXISTS(SELECT 1 FROM `user` WHERE avatar_file_id IS NOT NULL LIMIT 1) THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='V17 data exists. Export and reconcile objects before rollback';
 END IF;
 ALTER TABLE `user` DROP FOREIGN KEY fk_user_avatar_owner_v17,
  DROP INDEX idx_user_avatar_owner_v17, DROP COLUMN avatar_file_id;
 DROP TABLE storage_file;
END//
DELIMITER ;
CALL catgal_require_unused_storage_v17();
DROP PROCEDURE catgal_require_unused_storage_v17;
