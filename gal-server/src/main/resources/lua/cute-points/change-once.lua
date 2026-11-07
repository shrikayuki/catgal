if redis.call('exists', KEYS[3]) == 1 then
    return -2
end
local current = tonumber(redis.call('hget', KEYS[1], ARGV[1]) or '0')
local delta = tonumber(ARGV[4])
local nextValue = current + delta
if nextValue < 0 then
    return -1
end
redis.call('set', KEYS[3], '1', 'EX', ARGV[3])
redis.call('hincrby', KEYS[1], ARGV[1], delta)
if delta > 0 then
    redis.call('hincrby', KEYS[1], ARGV[2], delta)
elseif delta < 0 then
    redis.call('hincrby', KEYS[1], ARGV[5], -delta)
end
redis.call('sadd', KEYS[2], ARGV[6])
return nextValue
