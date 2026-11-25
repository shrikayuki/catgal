-- 修正 V20 的业务文本比较。先备份 resource 和触发器定义，再在新版应用上线前执行。
-- VARCHAR 所用的 *_ci 排序规则会忽略大小写，URL路径和访问口令必须按实际字节比较。
-- 只替换该触发器，不修改业务记录、既有 revision 和审核历史。DDL 隐式提交。
DELIMITER $$
DROP TRIGGER IF EXISTS trg_resource_business_revision$$
CREATE TRIGGER trg_resource_business_revision BEFORE UPDATE ON resource FOR EACH ROW
BEGIN
 IF NOT (OLD.game_id <=> NEW.game_id AND OLD.user_id <=> NEW.user_id
  AND CAST(OLD.name AS BINARY) <=> CAST(NEW.name AS BINARY)
  AND CAST(OLD.download_url AS BINARY) <=> CAST(NEW.download_url AS BINARY)
  AND CAST(OLD.extract_code AS BINARY) <=> CAST(NEW.extract_code AS BINARY)
  AND CAST(OLD.password AS BINARY) <=> CAST(NEW.password AS BINARY)
  AND CAST(OLD.remark AS BINARY) <=> CAST(NEW.remark AS BINARY)
  AND CAST(OLD.file_size AS BINARY) <=> CAST(NEW.file_size AS BINARY)
  AND CAST(OLD.types AS BINARY) <=> CAST(NEW.types AS BINARY)
  AND CAST(OLD.languages AS BINARY) <=> CAST(NEW.languages AS BINARY)
  AND CAST(OLD.platforms AS BINARY) <=> CAST(NEW.platforms AS BINARY)
  AND OLD.status <=> NEW.status AND OLD.is_official <=> NEW.is_official
  AND OLD.reviewed_by <=> NEW.reviewed_by AND OLD.reviewed_at <=> NEW.reviewed_at
  AND CAST(OLD.review_reason AS BINARY) <=> CAST(NEW.review_reason AS BINARY)) THEN
  SET NEW.revision=OLD.revision+1;
 ELSE
  SET NEW.revision=OLD.revision;
 END IF;
END$$
DELIMITER ;
