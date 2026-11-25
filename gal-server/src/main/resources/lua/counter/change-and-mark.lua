-- 阅读说明
-- 计数器缺失时用数据库快照初始化，再增减计数、限制非负并记录待同步 ID。
-- KEYS 为计数键和脏集合，ARGV 为初始值、增量和业务 ID，返回新计数。

if redis.call('exists', KEYS[1]) == 0 then
    redis.call('set', KEYS[1], ARGV[1])
end
local value = redis.call('incrby', KEYS[1], ARGV[2])
if value < 0 then
    value = 0
    redis.call('set', KEYS[1], '0')
end
redis.call('sadd', KEYS[2], ARGV[3])
return value
