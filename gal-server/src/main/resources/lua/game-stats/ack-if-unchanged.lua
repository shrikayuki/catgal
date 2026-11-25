-- 阅读说明
-- 只有所有计数仍等于已落库快照时才移除脏集合 ID。
-- KEYS[1] 为任务集合，其余为计数键，ARGV[1] 为 ID，其余与计数快照一一对应。

for i = 2, #KEYS do
    if redis.call('get', KEYS[i]) ~= ARGV[i] then
        return 0
    end
end
return redis.call('srem', KEYS[1], ARGV[1])
