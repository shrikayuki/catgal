-- 阅读说明
-- 物理内容删除提交后的 Redis 清理，移除目标缓存和待处理索引。
-- processing 批次保留给持有处理锁的执行者按协议确认。

-- Used only after a physical target deletion commits. Readers fall back to the database.
-- KEYS 与 add.lua 一致，ARGV[1] 为已删除目标 ID。关系已经随数据库事务删除。
-- processing 留给持有任务锁的消费者确认，清理函数只回收缓存、索引与 pending。
local types = {'set', 'string', 'set', 'set', 'set'}
for i, expected in ipairs(types) do
    local actual = redis.call('TYPE', KEYS[i]).ok
    if actual ~= 'none' and actual ~= expected then return redis.error_reply('Like clear key type mismatch') end
end
redis.call('DEL', KEYS[1], KEYS[2])
redis.call('SREM', KEYS[3], ARGV[1])
redis.call('SREM', KEYS[4], ARGV[1])
redis.call('SREM', KEYS[5], ARGV[1])
return 1
