-- 阅读说明
-- KEYS[1] 为会话生成锁，ARGV[1] 为当前 token，匹配后才释放。
-- 取消与正常收尾共用，防止过期执行者删除另一轮生成的租约。

if redis.call('GET', KEYS[1]) == ARGV[1] then
    return redis.call('DEL', KEYS[1])
end
return 0
