-- Administrator operation audit log.
-- Kept separate from V2 because MySQL DDL uses implicit commits. A failure here must not
-- leave Flyway reporting a single partially executed migration that also changed `user`.

CREATE TABLE `admin_operation_log` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '日志主键',
    `admin_user_id` BIGINT NOT NULL COMMENT '操作管理员用户ID',
    `module` VARCHAR(32) NOT NULL COMMENT '业务模块',
    `operation` VARCHAR(64) NOT NULL COMMENT '操作类型',
    `target_type` VARCHAR(32) NOT NULL COMMENT '目标类型',
    `target_id` VARCHAR(64) NULL COMMENT '目标业务ID',
    `before_data` JSON NULL COMMENT '操作前快照，不得存储密码、Token等敏感字段',
    `after_data` JSON NULL COMMENT '操作后快照，不得存储密码、Token等敏感字段',
    `reason` VARCHAR(255) NULL COMMENT '管理员填写的原因',
    `request_id` VARCHAR(64) NULL COMMENT '请求追踪ID',
    `ip_address` VARCHAR(45) NULL COMMENT '操作来源IP，兼容IPv6',
    `result` TINYINT NOT NULL DEFAULT 1 COMMENT '执行结果：0-失败，1-成功',
    `error_message` VARCHAR(500) NULL COMMENT '失败摘要，不记录堆栈和敏感信息',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    INDEX `idx_admin_log_admin_time` (`admin_user_id`, `create_time`),
    INDEX `idx_admin_log_target` (`target_type`, `target_id`, `create_time`),
    INDEX `idx_admin_log_module_time` (`module`, `create_time`),
    INDEX `idx_admin_log_request_id` (`request_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='管理员操作审计日志';
