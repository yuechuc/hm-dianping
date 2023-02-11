package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;
@Component
@Slf4j
public class CacheClient {
    @Resource
    private StringRedisTemplate stringRedisTemplate;


    //写入redis
    public void set(String key,Object value,Long time,TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    //写入redis+逻辑过期时间
    public void setLogicalExpire(String key,Object value,Long time,TimeUnit unit) {
        //设置逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        // 写入redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    //使用空对象解决缓存穿透
    public <R,ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID,R> fallback, Long time, TimeUnit unit){
        // 1.从redis查询商铺缓存
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        // 2.判断是否存在
        if (StrUtil.isNotBlank(json)) {
            // 3.存在直接返回
            R r = JSONUtil.toBean(json, type);
            return r;
        }
        // 判断命中是否为空值字符串
        //  null
        //  ”“
        if (json != null) {      //如果 != null  就只能是""
            return null;
        }

        // 4.不存在，根据id查询数据库
        R r = fallback.apply(id);
        // 5.数据库不存在，返回错误
        if (r == null) {
            stringRedisTemplate.opsForValue().set(key, "", time, unit);
            return null;

        }
        // 6.存在，写入redis ,设置超时时间
        this.set(key,r,time,unit);
        // 7.返回
        return r;
    }


    // 线程池
    public static final ExecutorService CACHE_REBUILD_EXECUTE = Executors.newFixedThreadPool(10);


    // 添加互斥锁
    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    // 解除互斥锁
    private void unLock(String key) {
        stringRedisTemplate.delete(key);
    }


    public <R,ID> R queryWithMutex(String keyPrefix, ID id,Class<R> type,Function<ID,R> fallback,Long time, TimeUnit unit) {
        // 1.从redis查询商铺缓存
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        // 2.判断是否存在
        if (StrUtil.isNotBlank(json)) {
            // 3.存在直接返回
            R r = JSONUtil.toBean(json, type);
            return r;
        }
        // 判断命中是否为空值字符串
        //  null
        //  ”“
        if (json != null) {      // 如果 != null  就只能是""
            return null;
        }

        // 4.实现缓存重建
        // 获取互斥锁
        String lockShopKey = keyPrefix + id;
        R r = null;
        try {
            boolean isLock = tryLock(lockShopKey);
            // 判断获取是否成功
            if (!isLock) {
                // 失败，则休眠并重试
                Thread.sleep(50);
                return queryWithMutex(keyPrefix,id,type,fallback,time,unit);
            }
            // 不存在，根据id查询数据库
            r = fallback.apply(id);

            // 模拟重建延时
            Thread.sleep(200);


            // 5.数据库不存在，返回错误
            if (r == null) {
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            // 6.存在，写入redis ,设置超时时间
            // stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
            this.set(key,r,time,unit);
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            // 7.解锁
            unLock(lockShopKey);
        }

        // 8.返回结果
        return r;
    }

    // 使用逻辑锁解决缓存击穿
    public <R,ID> R queryWithLogicalExpire(String keyPrefix,ID id,Class<R> type,Function<ID,R> fallback,Long time, TimeUnit unit) {
        // 1.从redis查询商铺缓存
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        // 2.判断是否存在
        if (StrUtil.isBlank(json)) {
            // 3.不存在  直接返回
            return null;
        }
        // 4.命中，先把json对象反序列化为对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        R r = JSONUtil.toBean(data, type);
        LocalDateTime expireTime = redisData.getExpireTime();

        // 5.判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            // 5.1未过期，直接返回商铺信息
            return r;
        }
        // 5.2已过期，需要缓存重建
        // 6.缓存重建
        // 6.1获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        // 6.2判断是否成功
        if (isLock) {
            // 6.3成功，开启独立线程，实现缓存重建
            CACHE_REBUILD_EXECUTE.submit(() -> {
                try {
                    // 6.3重建缓存
                    // 查询数据库
                    R r1 = fallback.apply(id);
                    // 写入redis

                    this.setLogicalExpire(key,r1,time,unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    // 释放锁
                    unLock(lockKey);
                }
            });
        }
        // 6.4返回过期的商铺信息
        return r;
    }
}
