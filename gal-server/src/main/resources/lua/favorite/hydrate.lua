-- 阅读说明
-- 持有收藏夹和相关业务锁后，从已验证的数据库关系回灌缓存并建立条数标记。
-- 存在未落库任务时保留当前状态，避免旧数据库快照覆盖缓存新变更。

-- 调用方已锁定收藏夹。仅无 pending/processing 时允许数据库回灌，避免覆盖尚未落库的操作。
-- KEYS 为 Hash、完整性标记、pending、processing。ARGV 为 folderId、ownerId、gameId...
for index = 3, 4 do
    local kind = redis.call('TYPE', KEYS[index]).ok
    if kind ~= 'none' and kind ~= 'set' then return redis.error_reply('Invalid favorite dirty set') end
    if redis.call('SISMEMBER', KEYS[index], ARGV[1]) == 1 then return 0 end
end
redis.call('DEL', KEYS[1], KEYS[2])
for index = 3, #ARGV do redis.call('HSET', KEYS[1], ARGV[index], ARGV[2]) end
redis.call('SET', KEYS[2], tostring(#ARGV - 2))
return 1
