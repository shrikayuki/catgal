-- 阅读说明
-- 从数据库关系加载点赞缓存，调用方持有目标行锁且核验没有未完成任务。
-- 回灌后的条数标记让后续同步可区分空集合和冷缓存。

-- The caller holds the MySQL target row lock, shared by mutations and record sync.
-- A damaged cache with pending work must remain available for reconciliation.
-- 调用方持有 MySQL 目标行锁，KEYS 依次为成员、标记、索引、明细 pending/processing、计数 pending/processing。
-- ARGV[1] 为目标 ID，其余参数为数据库中的全部用户 ID。返回 1=已加载、0=存在未完成任务。
-- 有积压时数据库可能尚未包含最新变更，必须保留现场等待恢复，直接回灌会覆盖这些变更。
local types = {'set', 'string', 'set', 'set', 'set', 'set', 'set'}
for i, expected in ipairs(types) do
    local actual = redis.call('TYPE', KEYS[i]).ok
    if actual ~= 'none' and actual ~= expected then return redis.error_reply('Like hydrate key type mismatch') end
end
for i = 4, 7 do
    if redis.call('SISMEMBER', KEYS[i], ARGV[1]) == 1 then return 0 end
end
if #ARGV - 1 > 2147483647 then return redis.error_reply('Like count overflow') end
for i = 2, #ARGV do
    if not string.match(ARGV[i], '^[1-9]%d*$') then return redis.error_reply('Invalid like owner') end
end
redis.call('DEL', KEYS[1])
for i = 2, #ARGV do redis.call('SADD', KEYS[1], ARGV[i]) end
local size = redis.call('SCARD', KEYS[1])
redis.call('SET', KEYS[2], tostring(size))
if size > 0 then redis.call('SADD', KEYS[3], ARGV[1]) else redis.call('SREM', KEYS[3], ARGV[1]) end
redis.call('SADD', KEYS[6], ARGV[1])
return 1
