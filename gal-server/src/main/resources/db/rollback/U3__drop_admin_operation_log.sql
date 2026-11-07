-- Manual rollback for V3. Flyway does not execute this file automatically.
-- WARNING: this permanently deletes all administrator audit records.

DROP TABLE IF EXISTS `admin_operation_log`;
