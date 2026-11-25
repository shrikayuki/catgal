-- Manual rollback for V2. Flyway does not execute this file automatically.
-- Run only after application code no longer reads the moderation columns.

ALTER TABLE `user`
    DROP INDEX `idx_user_status_banned_until`,
    DROP COLUMN `banned_until`,
    DROP COLUMN `ban_reason`,
    DROP COLUMN `status`;
