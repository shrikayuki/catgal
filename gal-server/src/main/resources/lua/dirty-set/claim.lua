-- 阅读说明
-- 持有任务锁的执行者原子领取 pending 成员并转移到 processing。
-- 同一脚本避免领取后尚未记录处理中状态时丢失任务。

if redis.call('get', KEYS[3]) ~= ARGV[1] then
    return redis.error_reply('Dirty task lock ownership lost')
end
for index = 1, 2 do
    local actual = redis.call('type', KEYS[index]).ok
    if actual ~= 'none' and actual ~= 'set' then
        return redis.error_reply('Dirty task key type mismatch')
    end
end
local batchSize = tonumber(ARGV[2])
if not batchSize or batchSize < 1 or batchSize ~= math.floor(batchSize) then
    return redis.error_reply('Invalid dirty task batch size')
end
local result = {}
for i = 1, batchSize do
    local member = redis.call('spop', KEYS[1])
    if not member then
        break
    end
    redis.call('sadd', KEYS[2], member)
    table.insert(result, member)
end
return result
