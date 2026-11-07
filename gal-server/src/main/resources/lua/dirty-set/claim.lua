local result = {}
for i = 1, tonumber(ARGV[1]) do
    local member = redis.call('spop', KEYS[1])
    if not member then
        break
    end
    redis.call('sadd', KEYS[2], member)
    table.insert(result, member)
end
return result
