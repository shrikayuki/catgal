-- 阅读说明
-- 核验集合大小与条数标记后导出全部用户 ID，供关系落库使用。
-- 返回 {1} 表示确认的空集合，{0} 表示无法证明完整。

-- {0} means incomplete. {1, owner IDs...} is an atomically verified snapshot.
-- KEYS 为成员 Set、条数标记。返回 {1} 是确认的空集合，{0} 需保留数据库原关系并重试。
local setType = redis.call('TYPE', KEYS[1]).ok
local markerType = redis.call('TYPE', KEYS[2]).ok
if (setType ~= 'none' and setType ~= 'set') or
   (markerType ~= 'none' and markerType ~= 'string') then return {0} end
local marker = redis.call('GET', KEYS[2])
if not marker or not string.match(marker, '^%d+$') or
   tonumber(marker) ~= redis.call('SCARD', KEYS[1]) then return {0} end
local result = {1}
for _, owner in ipairs(redis.call('SMEMBERS', KEYS[1])) do result[#result + 1] = owner end
return result
