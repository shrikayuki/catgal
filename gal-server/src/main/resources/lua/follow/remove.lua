-- 阅读说明
-- 原子清除 Redis 关注与粉丝双向关系，供取消关注后的缓存维护使用。

redis.call('zrem', KEYS[1], ARGV[1])
redis.call('zrem', KEYS[2], ARGV[2])
return 1
