-- Manual rollback for V4. Run only after the application has been rolled back.

ALTER TABLE `draw_record`
    DROP INDEX `uk_draw_biz_id`,
    DROP INDEX `idx_draw_prize_id`,
    DROP INDEX `idx_draw_create_time`,
    DROP COLUMN `biz_id`;

ALTER TABLE `follow_record`
    DROP INDEX `uk_follow_pair`,
    MODIFY COLUMN `id` BIGINT NOT NULL;
