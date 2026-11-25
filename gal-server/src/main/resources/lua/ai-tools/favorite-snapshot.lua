-- 阅读说明
-- AI 兴趣工具读取收藏样本，返回状态标识和有界关系数据供 Java 核验。
-- 调用入口为 AiInterestReader，是否完整与是否降级由返回标识判断。

-- Atomically distinguish an empty, pending removal from an uninitialised cache.
-- All returned pairs are bounded. Callers still verify the folder owner and pair owner.
local current = redis.call('EXISTS', KEYS[1])
if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 or
   redis.call('SISMEMBER', KEYS[3], ARGV[1]) == 1 then
    current = 1
end
local limit = math.min(201, math.max(1, tonumber(ARGV[2])))
local cursor = '0'
local result = {current, 0}
local count = 0
for iteration = 1, 20 do
    local page = redis.call('HSCAN', KEYS[1], cursor, 'COUNT', limit)
    cursor = page[1]
    local values = page[2]
    for index = 1, #values, 2 do
        if count >= limit then
            result[2] = 1
            return result
        end
        result[#result + 1] = values[index]
        result[#result + 1] = values[index + 1]
        count = count + 1
    end
    if cursor == '0' then return result end
end
result[2] = 1
return result
