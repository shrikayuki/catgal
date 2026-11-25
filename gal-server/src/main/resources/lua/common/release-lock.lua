-- 阅读说明
-- 比较锁的当前 token 与本次所有者，匹配才删除，避免旧执行者释放新租约。
-- KEYS[1] 为锁键，ARGV[1] 为预期 token，返回实际删除数量。

if redis.call('get', KEYS[1]) == ARGV[1] then
    return redis.call('del', KEYS[1])
end
return 0
