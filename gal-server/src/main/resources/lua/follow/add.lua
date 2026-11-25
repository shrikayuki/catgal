-- 阅读说明
-- 原子更新关注者与被关注者的 Redis 关系集合，避免两次独立命令之间出现半更新。
-- 数据库关系及唯一约束仍由 FollowRecordServiceImpl 维护。

redis.call('zadd', KEYS[1], ARGV[1], ARGV[2])
redis.call('zadd', KEYS[2], ARGV[1], ARGV[3])
return 1
