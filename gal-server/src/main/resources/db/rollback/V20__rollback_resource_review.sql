-- 仅适用于功能尚未使用，先停新版应用。历史有记录/资源有业务版本时拒绝自动回退。
DELIMITER $$
CREATE PROCEDURE rollback_resource_review_v20()
BEGIN
 IF EXISTS(SELECT 1 FROM resource_review_history LIMIT 1)
  OR EXISTS(SELECT 1 FROM resource WHERE revision<>0 OR reviewed_by IS NOT NULL
   OR reviewed_at IS NOT NULL OR review_reason IS NOT NULL LIMIT 1) THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='V20 in use: preserve history and evaluate forward repair';
 END IF;
 DROP TRIGGER IF EXISTS trg_resource_business_revision;
 DROP TRIGGER IF EXISTS trg_resource_count_insert;
 DROP TRIGGER IF EXISTS trg_resource_count_update;
 DROP TRIGGER IF EXISTS trg_resource_count_delete;
 DROP TABLE resource_count_outbox;
 DROP TABLE resource_review_history;
 ALTER TABLE resource DROP COLUMN revision,DROP COLUMN reviewed_by,DROP COLUMN reviewed_at,DROP COLUMN review_reason;
END$$
DELIMITER ;
CALL rollback_resource_review_v20();
DROP PROCEDURE rollback_resource_review_v20;
