local added = redis.call('sadd', KEYS[1], ARGV[1])
if added == 1 then
    redis.call('sadd', KEYS[2], ARGV[2])
    redis.call('sadd', KEYS[3], ARGV[2])
    redis.call('sadd', KEYS[4], ARGV[2])
end
return added
