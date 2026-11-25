-- 用户手工执行。执行前备份库结构及 user_cute_points、cute_points_record、prize_pool、draw_record。
-- DDL 会隐式提交。初始 LEGACY 保持旧流程，部署新版后通过管理接口排空、核对、激活。
ALTER TABLE user_cute_points ADD COLUMN revision BIGINT NOT NULL DEFAULT 0 COMMENT '事务积分版本，仅新流程递增';
ALTER TABLE prize_pool ADD COLUMN revision BIGINT NOT NULL DEFAULT 0 COMMENT '奖品编辑和库存版本';

CREATE TABLE reward_runtime (
 id TINYINT NOT NULL PRIMARY KEY,
 mode VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'LEGACY',
 baseline_cursor BIGINT NOT NULL DEFAULT 0,
 baseline_complete TINYINT NOT NULL DEFAULT 0,
 baseline_date DATE NULL,
 updated_by BIGINT NULL,
 update_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 activated_at DATETIME(6) NULL,
 CONSTRAINT ck_reward_mode CHECK(mode IN ('LEGACY','DRAINING','ACTIVE')),
 CONSTRAINT ck_reward_singleton CHECK(id=1)
) ENGINE=InnoDB COMMENT='积分权威数据切换屏障，ACTIVE 后禁止自动回退旧写入';
INSERT INTO reward_runtime(id) VALUES(1);

CREATE TABLE reward_request (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 user_id BIGINT NOT NULL,
 action VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 request_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 biz_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 business_date DATE NOT NULL,
 status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 result_json JSON NULL,
 rejection_reason VARCHAR(100) NULL,
 legacy_imported TINYINT NOT NULL DEFAULT 0,
 create_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE KEY uk_reward_request(user_id,action,request_key),
 UNIQUE KEY uk_reward_biz(biz_id),
 KEY idx_reward_user_date(user_id,action,business_date,id),
 CONSTRAINT fk_reward_user FOREIGN KEY(user_id) REFERENCES user(id) ON DELETE RESTRICT,
 CONSTRAINT ck_reward_action CHECK(action IN ('SIGN','DRAW')),
 CONSTRAINT ck_reward_status CHECK(status IN ('COMPLETED','REJECTED'))
) ENGINE=InnoDB COMMENT='签到按日期、抽奖按客户端 UUID 幂等，保存首次确定结果';

CREATE TABLE reward_outbox (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 request_id BIGINT NOT NULL,
 state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'PENDING',
 attempts INT NOT NULL DEFAULT 0,
 next_attempt_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 lease_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
 lease_until DATETIME(6) NULL,
 last_error VARCHAR(100) NULL,
 projected_at DATETIME(6) NULL,
 create_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 update_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE KEY uk_reward_outbox_request(request_id),
 KEY idx_reward_outbox_due(state,next_attempt_at,id),
 CONSTRAINT fk_reward_outbox_request FOREIGN KEY(request_id) REFERENCES reward_request(id) ON DELETE RESTRICT,
 CONSTRAINT ck_reward_outbox_state CHECK(state IN ('PENDING','IN_FLIGHT','SENT','COMPLETE','FAILED'))
) ENGINE=InnoDB COMMENT='与业务事务一同提交，确认投递后等待消费者投影，超时可再次投递';
