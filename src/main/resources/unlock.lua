


-- 比较 线程中标识 与 锁中的标识 一致性
if (redis.call('get', KEYS[1]) == ARGV[1]) then
    -- 释放锁
    return redis.call('del',KEYS[1])
end
return 0