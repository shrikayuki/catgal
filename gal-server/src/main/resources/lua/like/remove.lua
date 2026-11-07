local removed = redis.call('srem', KEYS[1], ARGV[1])
if removed == 1 then
    if redis.call('scard', KEYS[1]) == 0 then
        redis.call('srem', KEYS[2], ARGV[2])
    end
    redis.call('sadd', KEYS[3], ARGV[2])
    redis.call('sadd', KEYS[4], ARGV[2])
end
return removed
