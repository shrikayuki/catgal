if redis.call('exists', KEYS[2]) == 1 then
    return -1
end
local prize = redis.call('hget', KEYS[1], ARGV[1])
if not prize then
    return 0
end
local obj = cjson.decode(prize)
if obj.totalStock == 0 then
    return 1
end
if obj.remainStock >= tonumber(ARGV[2]) then
    obj.remainStock = obj.remainStock - tonumber(ARGV[2])
    redis.call('hset', KEYS[1], ARGV[1], cjson.encode(obj))
    redis.call('sadd', KEYS[3], ARGV[1])
    return 1
end
return 0
