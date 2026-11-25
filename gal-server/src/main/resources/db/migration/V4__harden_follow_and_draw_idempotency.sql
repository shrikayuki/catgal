-- Phase A: follow concurrency and draw-record idempotency.
-- This migration intentionally does not delete duplicate rows. The unique-key statement
-- fails fast when historical duplicates exist; run docs/DATABASE_HARDENING_PLAN.md first.

ALTER TABLE `follow_record`
    ADD UNIQUE KEY `uk_follow_pair` (`fan_id`, `followed_id`);

ALTER TABLE `follow_record`
    MODIFY COLUMN `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '关注记录ID';

ALTER TABLE `draw_record`
    ADD COLUMN `biz_id` VARCHAR(64) NULL COMMENT '抽奖业务幂等号' AFTER `id`,
    ADD INDEX `idx_draw_prize_id` (`prize_id`),
    ADD INDEX `idx_draw_create_time` (`create_time`);

UPDATE `draw_record`
SET `biz_id` = CONCAT('legacy-draw-', `id`)
WHERE `biz_id` IS NULL;

ALTER TABLE `draw_record`
    MODIFY COLUMN `biz_id` VARCHAR(64) NOT NULL COMMENT '抽奖业务幂等号',
    ADD UNIQUE KEY `uk_draw_biz_id` (`biz_id`);
