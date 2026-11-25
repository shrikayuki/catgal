-- MySQL 提交后的只读投影。revision 使用十进制字符串比较，避免 Lua double 丢失 BIGINT 精度。
if #KEYS ~= 1 or #ARGV ~= 5 then return redis.error_reply('invalid projection arguments') end
local kind = redis.call('TYPE', KEYS[1]).ok
if kind ~= 'none' and kind ~= 'hash' then return redis.error_reply('invalid balance cache type') end
local function decimal(value)
    return value == '0' or (string.match(value, '^[1-9][0-9]*$') ~= nil)
end
local revision = ARGV[1]
if not decimal(revision) or #revision > 19 or (#revision == 19 and revision > '9223372036854775807') then
    return redis.error_reply('invalid balance revision')
end
for index = 2, 4 do
    local value = ARGV[index]
    if not decimal(value) or #value > 10 or (#value == 10 and value > '2147483647') then
        return redis.error_reply('invalid balance value')
    end
end
if ARGV[5] ~= '300' then return redis.error_reply('invalid projection ttl') end
local previous = redis.call('HGET', KEYS[1], 'revision')
if previous then
    if not decimal(previous) or #previous > 19 or (#previous == 19 and previous > '9223372036854775807') then
        return redis.error_reply('invalid stored revision')
    end
    if #previous > #revision or (#previous == #revision and previous > revision) then return 0 end
end
-- 全部参数和键类型校验后才修改，两个写命令均使用已验证参数。
redis.call('HSET', KEYS[1], 'revision', revision, 'balance', ARGV[2], 'earned', ARGV[3], 'spent', ARGV[4])
redis.call('EXPIRE', KEYS[1], 300)
return 1
