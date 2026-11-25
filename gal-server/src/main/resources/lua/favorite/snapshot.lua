-- 阅读说明
-- 一次读取收藏 Hash 与完整性标记，区分正常快照、删除日志和受损状态。
-- 返回标记及关系供 Java 校验，确认的空集合与缓存缺失有不同返回协议。

-- 原子读取完整状态。1 为普通快照，2 为待完成删除，0 为无法证明完整。
-- 返回 {状态, 标记原文, gameId, ownerId, ...}，只读脚本不会为旧 Hash 补造完整性证明。
local hashType = redis.call('TYPE', KEYS[1]).ok
local markerType = redis.call('TYPE', KEYS[2]).ok
if (hashType ~= 'none' and hashType ~= 'hash') or markerType ~= 'string' then return {0} end
local marker = redis.call('GET', KEYS[2])
local size = redis.call('HLEN', KEYS[1])
local state = 1
local expected = tonumber(marker)
if not string.match(marker, '^%d+$') then
    local owner, count, token = string.match(marker, '^D:(%d+):(%d+):([%w%-]+)$')
    if not owner or tonumber(owner) <= 0 or not token then return {0} end
    expected = tonumber(count)
    state = 2
end
if expected ~= size then return {0} end
local result = {state, marker}
for _, value in ipairs(redis.call('HGETALL', KEYS[1])) do result[#result + 1] = value end
return result
