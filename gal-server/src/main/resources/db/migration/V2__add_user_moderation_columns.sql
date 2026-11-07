-- Catgal existing database migration.
-- Prerequisite: the `user` table already exists and Flyway has been baselined at version 1.

ALTER TABLE `user`
    ADD COLUMN `status` TINYINT NOT NULL DEFAULT 1
        COMMENT '账号状态：0-封禁，1-正常',
    ADD COLUMN `ban_reason` VARCHAR(255) NULL
        COMMENT '最近一次封禁原因',
    ADD COLUMN `banned_until` DATETIME NULL
        COMMENT '封禁截止时间，NULL 表示永久封禁或未封禁',
    ADD INDEX `idx_user_status_banned_until` (`status`, `banned_until`);
