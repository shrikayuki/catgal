-- 阅读说明
-- 完成收藏夹删除或清空的缓存阶段，关系清理和游戏计数调整保持 Redis 内原子。
-- 删除日志与快照版本用于防止重复扣减，调用方按数据库事务结果安排执行。

-- 完整预检后一次性清空关系并扣计数。调用方持有收藏夹及所有相关游戏行锁。
-- KEYS 为 Hash、标记、pending、游戏统计 pending、各游戏计数。
-- ARGV 为原标记、folderId、模式(delete/clear)，随后为各 gameId、游戏存在标记的二元组。
if redis.call('GET', KEYS[2]) ~= ARGV[1] then return 0 end
local count = (#ARGV - 3) / 2
if count ~= redis.call('HLEN', KEYS[1]) then return 0 end
for index = 3, 4 do
    local kind = redis.call('TYPE', KEYS[index]).ok
    if kind ~= 'none' and kind ~= 'set' then return redis.error_reply('Invalid favorite dirty set') end
end
local values = {}
for index = 1, count do
    local game = ARGV[2 * index + 2]
    if not redis.call('HGET', KEYS[1], game) then return 0 end
    if ARGV[2 * index + 3] == '1' then
        local kind = redis.call('TYPE', KEYS[index + 4]).ok
        if kind ~= 'string' then return 0 end
        local value = redis.call('GET', KEYS[index + 4])
        if not string.match(value, '^%d+$') or tonumber(value) > 2147483647 then return 0 end
        values[index] = math.max(0, tonumber(value) - 1)
    end
end
for index = 1, count do
    if values[index] then
        redis.call('SET', KEYS[index + 4], tostring(values[index]), 'KEEPTTL')
        redis.call('SADD', KEYS[4], ARGV[2 * index + 2])
    else
        redis.call('DEL', KEYS[index + 4])
    end
end
redis.call('DEL', KEYS[1])
if ARGV[3] == 'clear' then
    redis.call('SET', KEYS[2], '0')
    redis.call('SADD', KEYS[3], ARGV[2])
else
    redis.call('DEL', KEYS[2])
    redis.call('SREM', KEYS[3], ARGV[2])
end
return 1
