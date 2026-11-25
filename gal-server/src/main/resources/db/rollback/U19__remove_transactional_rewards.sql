-- 先停止新版应用。仅允许新流程、基线导入都尚未使用时回滚。
-- ACTIVE 已产生数据时应保留结构并修复前进，旧镜像无法识别切换屏障。
DELIMITER //
CREATE PROCEDURE catgal_require_unused_rewards_v19()
BEGIN
 IF EXISTS(SELECT 1 FROM reward_runtime WHERE mode<>'LEGACY' OR baseline_cursor<>0 OR activated_at IS NOT NULL)
 OR EXISTS(SELECT 1 FROM reward_request LIMIT 1) OR EXISTS(SELECT 1 FROM reward_outbox LIMIT 1)
 OR EXISTS(SELECT 1 FROM user_cute_points WHERE revision<>0 LIMIT 1)
 OR EXISTS(SELECT 1 FROM prize_pool WHERE revision<>0 LIMIT 1) THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='V19 rewards used. Reconcile and retain schema before rollback';
 END IF;
 DROP TABLE reward_outbox;
 DROP TABLE reward_request;
 DROP TABLE reward_runtime;
 ALTER TABLE prize_pool DROP COLUMN revision;
 ALTER TABLE user_cute_points DROP COLUMN revision;
END//
DELIMITER ;
CALL catgal_require_unused_rewards_v19();
DROP PROCEDURE catgal_require_unused_rewards_v19;
