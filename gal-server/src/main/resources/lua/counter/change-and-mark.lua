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
