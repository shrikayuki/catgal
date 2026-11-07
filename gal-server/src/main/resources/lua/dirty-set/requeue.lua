for _, member in ipairs(ARGV) do
    redis.call('sadd', KEYS[1], member)
    redis.call('srem', KEYS[2], member)
end
return #ARGV
