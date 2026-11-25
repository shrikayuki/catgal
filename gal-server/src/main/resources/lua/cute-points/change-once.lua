-- 阅读说明
-- 积分消息在 Redis 内完成业务号去重、余额校验、累计收入支出更新与标脏。
-- KEYS 为用户积分 Hash、脏集合和业务去重键。返回 -2 重复、-1 余额不足，其余为余额。
-- ARGV 顺序见 CutePointsListener，去重 TTL 与任务持久化属于不同恢复边界。

-- Redis Lua 保证执行期间没有其他命令插入，但运行错误会保留此前已经成功的写入。
-- 所有可预见的键类型、整数格式和溢出错误，都在首次写入前检查。
if #KEYS ~= 3 or #ARGV ~= 6 then
    return redis.error_reply('Invalid points script arguments')
end
if KEYS[1] == KEYS[2] or KEYS[1] == KEYS[3] or KEYS[2] == KEYS[3] then
    return redis.error_reply('Points keys must be distinct')
end
local types = {'hash', 'set', 'string'}
for index, expected in ipairs(types) do
    local actual = redis.call('TYPE', KEYS[index]).ok
    if actual ~= 'none' and actual ~= expected then
        return redis.error_reply('Points key type mismatch')
    end
end

-- 字段名由 Java 常量提供，三个累计值必须分别存储，避免配置别名导致重复修改余额。
if ARGV[1] == '' or ARGV[2] == '' or ARGV[5] == '' or
   ARGV[1] == ARGV[2] or ARGV[1] == ARGV[5] or ARGV[2] == ARGV[5] then
    return redis.error_reply('Invalid points fields')
end
local delta = tonumber(ARGV[4])
if not string.match(ARGV[4], '^%-?[1-9]%d*$') or not delta or
   delta < -2147483648 or delta > 2147483647 then
    return redis.error_reply('Invalid points delta')
end
local ttl = tonumber(ARGV[3])
if not string.match(ARGV[3], '^[1-9]%d*$') or not ttl or ttl == math.huge then
    return redis.error_reply('Invalid points deduplication TTL')
end
if not string.match(ARGV[6], '^[1-9]%d*$') then
    return redis.error_reply('Invalid points owner')
end
if redis.call('EXISTS', KEYS[3]) == 1 then
    return -2
end

-- Java 与 MySQL 当前均用有符号 INT 保存这三个非负统计值，超过范围会破坏读取或落库。
-- 严格校验规范十进制格式，避免 tonumber 接受的文本在后续 HINCRBY 时被 Redis 拒绝。
local values = {}
for index, field in ipairs({ARGV[1], ARGV[2], ARGV[5]}) do
    local raw = redis.call('HGET', KEYS[1], field) or '0'
    local value = tonumber(raw)
    if not (raw == '0' or string.match(raw, '^[1-9]%d*$')) or not value or value > 2147483647 then
        return redis.error_reply('Invalid points counter')
    end
    values[index] = value
end
local nextValue = values[1] + delta
if nextValue < 0 then
    return -1
end
local nextEarned = values[2] + math.max(delta, 0)
local nextSpent = values[3] + math.max(-delta, 0)
if nextValue > 2147483647 or nextEarned > 2147483647 or nextSpent > 2147483647 then
    return redis.error_reply('Points counter overflow')
end

-- SET 是首次写入，TTL 的 Redis 原生命令范围检查也会在其他字段修改前完成。
-- 成功后保持原协议，统一更新去重标记、余额、累计统计和待同步用户 ID。
redis.call('SET', KEYS[3], '1', 'EX', ARGV[3])
redis.call('HINCRBY', KEYS[1], ARGV[1], delta)
if delta > 0 then
    redis.call('HINCRBY', KEYS[1], ARGV[2], delta)
elseif delta < 0 then
    redis.call('HINCRBY', KEYS[1], ARGV[5], -delta)
end
redis.call('SADD', KEYS[2], ARGV[6])
return nextValue
