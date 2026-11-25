-- 手工迁移，要求 V19 已执行。MySQL DDL 隐式提交，请在维护窗口执行并先备份。
-- 保留状态 0失效/1有效/2待审。旧记录 revision=0，审核元数据保持 NULL，不伪造历史。
ALTER TABLE resource
 ADD COLUMN revision BIGINT NOT NULL DEFAULT 0 COMMENT '内容/审核版本，计数变化不递增',
 ADD COLUMN reviewed_by BIGINT NULL COMMENT '最近审核人，历史采用快照保留',
 ADD COLUMN reviewed_at DATETIME(6) NULL,
 ADD COLUMN review_reason VARCHAR(500) NULL;

-- 不建立到 resource/user 的物理外键，使原有资源物理删除后仍可追溯操作。
-- 仅保存名称、身份与字段名，不保存外链、提取码、解压密码和修改前后的私密值。
CREATE TABLE resource_review_history (
 id BIGINT NOT NULL AUTO_INCREMENT,
 resource_id BIGINT NOT NULL,
 game_id BIGINT NOT NULL,
 owner_id BIGINT NOT NULL,
 actor_id BIGINT NOT NULL,
 actor_name VARCHAR(100) NOT NULL,
 action VARCHAR(24) NOT NULL,
 old_status TINYINT NULL,
 new_status TINYINT NULL,
 before_revision BIGINT NOT NULL,
 after_revision BIGINT NOT NULL,
 resource_name VARCHAR(200) NOT NULL,
 reason VARCHAR(500) NULL,
 changed_fields JSON NOT NULL,
 create_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(id),
 KEY idx_resource_history(resource_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 每游戏一条合并任务。version 用于条件确认，失败任务留在库中并退避重试。
CREATE TABLE resource_count_outbox (
 game_id BIGINT NOT NULL,
 version BIGINT NOT NULL DEFAULT 1,
 pending TINYINT NOT NULL DEFAULT 1,
 attempts INT NOT NULL DEFAULT 0,
 next_attempt_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 last_error VARCHAR(128) NULL,
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(game_id),
 KEY idx_resource_count_ready(pending,next_attempt_at,game_id),
 CONSTRAINT fk_resource_count_game FOREIGN KEY(game_id) REFERENCES game(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

DELIMITER $$
CREATE TRIGGER trg_resource_business_revision BEFORE UPDATE ON resource FOR EACH ROW
BEGIN
 IF NOT (OLD.game_id <=> NEW.game_id AND OLD.user_id <=> NEW.user_id
  AND OLD.name <=> NEW.name AND OLD.download_url <=> NEW.download_url
  AND OLD.extract_code <=> NEW.extract_code AND OLD.password <=> NEW.password
  AND OLD.remark <=> NEW.remark AND OLD.file_size <=> NEW.file_size
  AND OLD.types <=> NEW.types AND OLD.languages <=> NEW.languages
  AND OLD.platforms <=> NEW.platforms AND OLD.status <=> NEW.status
  AND OLD.is_official <=> NEW.is_official AND OLD.reviewed_by <=> NEW.reviewed_by
  AND OLD.reviewed_at <=> NEW.reviewed_at AND OLD.review_reason <=> NEW.review_reason) THEN
  SET NEW.revision=OLD.revision+1;
 ELSE
  SET NEW.revision=OLD.revision;
 END IF;
END$$
CREATE TRIGGER trg_resource_count_insert AFTER INSERT ON resource FOR EACH ROW
BEGIN
 IF NEW.status=1 THEN
  INSERT INTO resource_count_outbox(game_id) VALUES(NEW.game_id)
  ON DUPLICATE KEY UPDATE version=version+1,pending=1,attempts=0,
   next_attempt_at=NOW(6),last_error=NULL,updated_at=NOW(6);
 END IF;
END$$
CREATE TRIGGER trg_resource_count_update AFTER UPDATE ON resource FOR EACH ROW
BEGIN
 IF OLD.status<>NEW.status OR OLD.game_id<>NEW.game_id THEN
  IF OLD.status=1 THEN
   INSERT INTO resource_count_outbox(game_id) VALUES(OLD.game_id)
   ON DUPLICATE KEY UPDATE version=version+1,pending=1,attempts=0,
    next_attempt_at=NOW(6),last_error=NULL,updated_at=NOW(6);
  END IF;
  IF NEW.status=1 THEN
   INSERT INTO resource_count_outbox(game_id) VALUES(NEW.game_id)
   ON DUPLICATE KEY UPDATE version=version+1,pending=1,attempts=0,
    next_attempt_at=NOW(6),last_error=NULL,updated_at=NOW(6);
  END IF;
 END IF;
END$$
CREATE TRIGGER trg_resource_count_delete AFTER DELETE ON resource FOR EACH ROW
BEGIN
 IF OLD.status=1 AND EXISTS(SELECT 1 FROM game WHERE id=OLD.game_id) THEN
  INSERT INTO resource_count_outbox(game_id) VALUES(OLD.game_id)
  ON DUPLICATE KEY UPDATE version=version+1,pending=1,attempts=0,
   next_attempt_at=NOW(6),last_error=NULL,updated_at=NOW(6);
 END IF;
END$$
DELIMITER ;

-- 包括零资源游戏，首次启动时校准历史漂移。原有 ES 同步触发器继续运行。
INSERT INTO resource_count_outbox(game_id) SELECT id FROM game ORDER BY id;
