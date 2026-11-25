-- 阅读说明
-- 维护锁检查与奖品库存扣减在一个 Redis 脚本完成，有限库存成功后标脏。
-- KEYS 为当月奖池 Hash、维护锁和库存脏集合，ARGV 为奖品 ID 与扣减数量。
-- 返回 -1 正在维护、0 不可扣减、1 成功，totalStock=0 按无限库存处理。

-- Lua 运行错误会保留已执行的写入。先校验完整输入，避免 HSET 成功后 SADD 类型错误丢失标脏。
if #KEYS ~= 3 or #ARGV ~= 2 then
    return redis.error_reply('Invalid prize stock script arguments')
end
if KEYS[1] == KEYS[2] or KEYS[1] == KEYS[3] or KEYS[2] == KEYS[3] then
    return redis.error_reply('Prize stock keys must be distinct')
end
local types = {'hash', 'string', 'set'}
for index, expected in ipairs(types) do
    local actual = redis.call('TYPE', KEYS[index]).ok
    if actual ~= 'none' and actual ~= expected then
        return redis.error_reply('Prize stock key type mismatch')
    end
end
local quantity = tonumber(ARGV[2])
if not string.match(ARGV[1], '^[1-9]%d*$') or
   not string.match(ARGV[2], '^[1-9]%d*$') or not quantity or quantity > 2147483647 then
    return redis.error_reply('Invalid prize stock quantity or identity')
end
if redis.call('EXISTS', KEYS[2]) == 1 then
    return -1
end
local prize = redis.call('HGET', KEYS[1], ARGV[1])
if not prize then
    return 0
end

-- 缓存 JSON 也属于需要验证的输入，库存范围对应 PrizePool 的 Java Integer 与 MySQL INT。
local decoded, obj = pcall(cjson.decode, prize)
if not decoded or type(obj) ~= 'table' or
   type(obj.totalStock) ~= 'number' or type(obj.remainStock) ~= 'number' then
    return redis.error_reply('Invalid prize stock document')
end
for _, value in ipairs({obj.totalStock, obj.remainStock}) do
    if value < 0 or value > 2147483647 or value % 1 ~= 0 then
        return redis.error_reply('Invalid prize stock counter')
    end
end
if obj.totalStock == 0 then
    return 1
end
if obj.remainStock > obj.totalStock then
    return redis.error_reply('Prize remaining stock exceeds total stock')
end
if obj.remainStock < quantity then
    return 0
end
obj.remainStock = obj.remainStock - quantity
-- 编码也在写前完成，合法剩余库存与脏任务一并写入 Redis。
local encoded, content = pcall(cjson.encode, obj)
if not encoded then
    return redis.error_reply('Invalid prize stock encoding')
end
redis.call('HSET', KEYS[1], ARGV[1], content)
redis.call('SADD', KEYS[3], ARGV[1])
return 1
