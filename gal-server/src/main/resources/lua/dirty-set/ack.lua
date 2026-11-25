-- 阅读说明
-- 业务成功后从 processing 移除本批次成员，执行前核验当前任务锁 token。
-- 新的 pending 变更保持可见，由下一批继续处理。

-- Only the current lock owner can acknowledge its processing members.
if redis.call('get', KEYS[2]) ~= ARGV[1] then return -1 end
local actual = redis.call('type', KEYS[1]).ok
if actual ~= 'none' and actual ~= 'set' then
    return redis.error_reply('Dirty task processing key type mismatch')
end
for index = 2, #ARGV do
    redis.call('srem', KEYS[1], ARGV[index])
end
return #ARGV - 1
