-- 只读预检查，每项问题计数必须为 0。队列、Redis 与 MySQL 的切换核对由新版管理接口完成。
SELECT 'v19_tables_already_exist' AS problem, COUNT(*) AS problem_count FROM information_schema.tables
 WHERE table_schema=DATABASE() AND table_name IN ('reward_runtime','reward_request','reward_outbox')
UNION ALL
SELECT 'v19_columns_already_exist',COUNT(*) FROM information_schema.columns
 WHERE table_schema=DATABASE() AND table_name IN ('user_cute_points','prize_pool') AND column_name='revision'
UNION ALL
SELECT 'invalid_points',COUNT(*) FROM user_cute_points
 WHERE cute_points<0 OR total_earned<0 OR total_spent<0
UNION ALL
SELECT 'orphan_points',COUNT(*) FROM user_cute_points p LEFT JOIN user u ON u.id=p.user_id WHERE u.id IS NULL
UNION ALL
SELECT 'invalid_prize_stock',COUNT(*) FROM prize_pool
 WHERE weight<0 OR total_stock<0 OR remain_stock<0 OR (total_stock=0 AND remain_stock<>0)
 OR (total_stock>0 AND remain_stock>total_stock) OR month NOT REGEXP '^[0-9]{4}(0[1-9]|1[0-2])$'
UNION ALL
SELECT 'invalid_points_ledger_bounds',COUNT(*) FROM cute_points_record WHERE before_points<0 OR after_points<0;
