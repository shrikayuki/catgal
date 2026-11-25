-- 阅读说明
-- 验证完整性标记后新增点赞成员，同步修改条数、业务索引和明细计数任务。
-- 同目标写入已由 Java 目标行锁协调，脚本保证 Redis 键之间原子更新。

-- Validate every writable key before changing membership or dirty queues.
-- KEYS 依次为成员 Set、条数标记、业务索引、明细 pending、计数 pending。
-- ARGV 依次为用户 ID、目标 ID。返回 1=新增、0=重复、-2=快照不完整、-3=超出计数上限。
-- 所有键类型检查放在写入前，防止 Redis 脚本运行时错误留下部分修改。
local types = {'set', 'string', 'set', 'set', 'set'}
for i, expected in ipairs(types) do
    local actual = redis.call('TYPE', KEYS[i]).ok
    if actual ~= 'none' and actual ~= expected then return redis.error_reply('Like key type mismatch') end
end
local expected = redis.call('GET', KEYS[2])
if not expected or not string.match(expected, '^%d+$') or
   tonumber(expected) ~= redis.call('SCARD', KEYS[1]) then return -2 end
if tonumber(expected) >= 2147483647 then return -3 end
local added = redis.call('sadd', KEYS[1], ARGV[1])
if added == 1 then
    redis.call('SET', KEYS[2], tostring(redis.call('SCARD', KEYS[1])))
    redis.call('sadd', KEYS[3], ARGV[2])
    redis.call('sadd', KEYS[4], ARGV[2])
    redis.call('sadd', KEYS[5], ARGV[2])
end
return added
