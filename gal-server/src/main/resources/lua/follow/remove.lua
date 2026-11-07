redis.call('zrem', KEYS[1], ARGV[1])
redis.call('zrem', KEYS[2], ARGV[2])
return 1
