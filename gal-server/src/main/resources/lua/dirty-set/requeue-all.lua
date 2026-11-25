-- 阅读说明
-- 新执行者持有锁后把上次遗留的 processing 重新加入 pending。
-- 锁所有者校验防止已失效实例改动其他实例正在处理的队列。

if redis.call('get', KEYS[3]) ~= ARGV[1] then return -1 end
for index = 1, 2 do
    local actual = redis.call('type', KEYS[index]).ok
    if actual ~= 'none' and actual ~= 'set' then
        return redis.error_reply('Dirty task key type mismatch')
    end
end
local members = redis.call('smembers', KEYS[2])
for _, member in ipairs(members) do
    redis.call('sadd', KEYS[1], member)
end
redis.call('del', KEYS[2])
return #members
