-- 阅读说明
-- 原子读取条数和可选用户成员状态，展示查询用数据库回源处理未加载状态。
-- KEYS 为成员集合与条数标记，ARGV[1] 为用户 ID 或空串。

-- Atomic count and optional membership. Invalid state is resolved from MySQL by readers.
-- KEYS 为成员 Set、条数标记，ARGV[1] 可传用户 ID 或空串。结果为 {计数, 是否属于该集合}。
-- 计数 -1 表示未加载或不完整，展示查询可回源，写库消费者需等待完整状态。
local setType = redis.call('TYPE', KEYS[1]).ok
local markerType = redis.call('TYPE', KEYS[2]).ok
if (setType ~= 'none' and setType ~= 'set') or
   (markerType ~= 'none' and markerType ~= 'string') then return {-1, 0} end
local marker = redis.call('GET', KEYS[2])
if not marker or not string.match(marker, '^%d+$') or
   tonumber(marker) ~= redis.call('SCARD', KEYS[1]) then return {-1, 0} end
local member = 0
if ARGV[1] ~= '' then member = redis.call('SISMEMBER', KEYS[1], ARGV[1]) end
return {tonumber(marker), member}
