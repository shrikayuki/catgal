-- 阅读说明
-- 数据库删除回滚时核对原删除日志并恢复完整性标记。
-- 日志已变更时由后续持锁恢复路径处理，防止覆盖新的状态。

-- 只有仍存在的收藏夹持有行锁时调用。CAS 防止回滚回调误改后一次删除的日志。
if redis.call('GET', KEYS[1]) ~= ARGV[1] then return 0 end
local owner, size = string.match(ARGV[1], '^D:(%d+):(%d+):[%w%-]+$')
if not owner then return 0 end
redis.call('SET', KEYS[1], size)
return 1
