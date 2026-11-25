-- 阅读说明
-- 数据库删除前记录带所有者、条数及 token 的删除准备日志。
-- 此阶段保留关系 Hash，提交成功后的独立步骤负责完成清理。

-- 删除准备日志保留整个 Hash 和计数。数据库提交后才扣计数，回滚/进程退出均可恢复。
-- KEYS 为 Hash、标记、pending。ARGV 为 folderId、ownerId、token、预期条数。
local kind = redis.call('TYPE', KEYS[3]).ok
if kind ~= 'none' and kind ~= 'set' then return redis.error_reply('Invalid favorite dirty set') end
if redis.call('GET', KEYS[2]) ~= ARGV[4] or redis.call('HLEN', KEYS[1]) ~= tonumber(ARGV[4]) then return 0 end
for _, owner in ipairs(redis.call('HVALS', KEYS[1])) do if owner ~= ARGV[2] then return 0 end end
redis.call('SET', KEYS[2], 'D:' .. ARGV[2] .. ':' .. tostring(redis.call('HLEN', KEYS[1])) .. ':' .. ARGV[3])
redis.call('SADD', KEYS[3], ARGV[1])
return 1
