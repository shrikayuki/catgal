-- 阅读说明
-- 取消点赞后保留显式 0 标记，以便数据库同步确认最后一条确实已清空。
-- 重复取消与缓存未加载通过不同结果码返回，调用方据此重试或回源。

-- Keep an explicit zero marker when SREM removes the final member.
-- KEYS 与 add.lua 一致，ARGV 为用户 ID、目标 ID。返回 1=已取消、0=原本未点赞、-2=快照不完整。
-- 最后一条移除后保留标记 0，后续同步才能确认这是用户清空后的结果。
local types = {'set', 'string', 'set', 'set', 'set'}
for i, expected in ipairs(types) do
    local actual = redis.call('TYPE', KEYS[i]).ok
    if actual ~= 'none' and actual ~= expected then return redis.error_reply('Like key type mismatch') end
end
local expected = redis.call('GET', KEYS[2])
if not expected or not string.match(expected, '^%d+$') or
   tonumber(expected) ~= redis.call('SCARD', KEYS[1]) then return -2 end
local removed = redis.call('srem', KEYS[1], ARGV[1])
if removed == 1 then
    redis.call('SET', KEYS[2], tostring(redis.call('SCARD', KEYS[1])))
    if redis.call('scard', KEYS[1]) == 0 then
        redis.call('srem', KEYS[3], ARGV[2])
    end
    redis.call('sadd', KEYS[4], ARGV[2])
    redis.call('sadd', KEYS[5], ARGV[2])
end
return removed
