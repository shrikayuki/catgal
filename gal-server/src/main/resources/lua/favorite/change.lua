-- 阅读说明
-- 单次收藏或取消在同一脚本内修改 Hash、完整性标记、游戏计数和两类脏集合。
-- 写入前检查类型、条数与归属，重复操作返回 -1，未确认完整状态返回 -2。

-- KEYS: folder hash, expected hash size, game count, folder dirty set, game dirty set.
-- ARGV: game ID, owner ID, folder ID, delta.
local types = {'hash', 'string', 'string', 'set', 'set'}
for index, expected in ipairs(types) do
    local actual = redis.call('TYPE', KEYS[index]).ok
    if actual ~= 'none' and actual ~= expected then
        return redis.error_reply('Favorite cache key type mismatch')
    end
end

local size = redis.call('HLEN', KEYS[1])
local expected = redis.call('GET', KEYS[2])
if expected then
    if not string.match(expected, '^%d+$') or tonumber(expected) ~= size then
        return -2
    end
else
    return -2
end

local delta = tonumber(ARGV[4])
if delta ~= 1 and delta ~= -1 then
    return redis.error_reply('Invalid favorite operation')
end
local member = redis.call('HGET', KEYS[1], ARGV[1])
if member and member ~= ARGV[2] then
    return redis.error_reply('Favorite cache owner mismatch')
end
if (delta == 1 and member) or (delta == -1 and not member) then
    return -1
end

-- 游戏计数缺失由持有游戏锁的 Java 路径重算，禁止用可能滞后的 game.favorite_count 初始化。
local count = redis.call('GET', KEYS[3])
if not count then return -2 end
if not string.match(count, '^%d+$') or tonumber(count) > 2147483647 then
    return redis.error_reply('Invalid favorite counter')
end
local updated = math.max(0, tonumber(count) + delta)
if updated > 2147483647 then
    return redis.error_reply('Favorite counter overflow')
end

if delta == 1 then
    redis.call('HSET', KEYS[1], ARGV[1], ARGV[2])
else
    redis.call('HDEL', KEYS[1], ARGV[1])
end
redis.call('SET', KEYS[2], tostring(size + delta))
redis.call('SET', KEYS[3], tostring(updated), 'KEEPTTL')
redis.call('SADD', KEYS[4], ARGV[3])
redis.call('SADD', KEYS[5], ARGV[1])
return updated
