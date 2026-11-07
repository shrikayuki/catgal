local members = redis.call('smembers', KEYS[2])
for _, member in ipairs(members) do
    redis.call('sadd', KEYS[1], member)
end
redis.call('del', KEYS[2])
return #members
