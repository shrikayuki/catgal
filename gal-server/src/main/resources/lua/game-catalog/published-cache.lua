-- 阅读说明
-- 根据游戏 revision 更新公开候选集合，较旧回调不会覆盖已经应用的新状态。
-- 十进制版本使用长度和字典序比较，避开 Lua 浮点数对大整数的精度限制。

-- KEYS[1] published game IDs, KEYS[2] per-game catalog revisions.
-- ARGV[1] game ID, ARGV[2] revision as a decimal string, ARGV[3] status.
local current = redis.call('HGET', KEYS[2], ARGV[1])
local incoming = ARGV[2]
-- Compare decimal strings without losing BIGINT precision in Lua numbers.
if current and (#current > #incoming or (#current == #incoming and current > incoming)) then
    return 0
end
redis.call('HSET', KEYS[2], ARGV[1], incoming)
if ARGV[3] == '1' then
    redis.call('SADD', KEYS[1], ARGV[1])
else
    redis.call('SREM', KEYS[1], ARGV[1])
end
return 1
