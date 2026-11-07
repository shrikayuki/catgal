for i = 2, #KEYS do
    if redis.call('get', KEYS[i]) ~= ARGV[i] then
        return 0
    end
end
return redis.call('srem', KEYS[1], ARGV[1])
