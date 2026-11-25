-- 阅读说明
-- 先确认当前生成锁的 token，再将用户与助手消息成对追加到短期历史。
-- KEYS 为生成锁和历史列表，ARGV 为 token、最大条数、TTL、用户消息和助手消息。
-- 裁剪和续期与租约检查同属一次 Redis 原子操作。

if redis.call('GET', KEYS[1]) ~= ARGV[1] then
    return 0
end
redis.call('RPUSH', KEYS[2], ARGV[4], ARGV[5])
redis.call('LTRIM', KEYS[2], -tonumber(ARGV[2]), -1)
redis.call('EXPIRE', KEYS[2], ARGV[3])
return 1
