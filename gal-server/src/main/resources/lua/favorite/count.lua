-- 阅读说明
-- 批量展示用的收藏夹计数读取，完整性标记与 Hash 大小必须一致。
-- 返回值协议由 FavoriteFolderServiceImpl 解码，缺失状态交由调用方回源。

-- 只读计数。-1 代表缓存不可验证，0 代表完整性标记确认清空。
local hashType = redis.call('TYPE', KEYS[1]).ok
if (hashType ~= 'none' and hashType ~= 'hash') or redis.call('TYPE', KEYS[2]).ok ~= 'string' then return -1 end
local expected = redis.call('GET', KEYS[2])
if not string.match(expected, '^%d+$') then return -1 end
local size = redis.call('HLEN', KEYS[1])
if tonumber(expected) ~= size or size > 2147483647 then return -1 end
return size
