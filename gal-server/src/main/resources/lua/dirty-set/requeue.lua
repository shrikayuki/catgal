-- 阅读说明
-- 处理失败时把指定批次从 processing 重新排入 pending，供下一次重试。
-- 只有当前任务锁所有者可进行恢复，参数中的 token 与 Java 批次一致。

if redis.call('get', KEYS[3]) ~= ARGV[1] then return -1 end
for index = 1, 2 do
    local actual = redis.call('type', KEYS[index]).ok
    if actual ~= 'none' and actual ~= 'set' then
        return redis.error_reply('Dirty task key type mismatch')
    end
end
for index = 2, #ARGV do
    local member = ARGV[index]
    redis.call('sadd', KEYS[1], member)
    redis.call('srem', KEYS[2], member)
end
return #ARGV - 1
