package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

@Slf4j
@Component
public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;

    @Autowired
    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag); // 避免空指针
    }

    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }


    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        RedisData redisData = new RedisData();
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        redisData.setData(value);
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    /**
     * 缓存穿透
     * @param prefix key前缀
     * @param id 查询id
     * @param type 类型令牌
     * @param dbFallback 目标数据库方法，ID为参数类型，R为返回值类型
     * @return 泛型声明+泛型返回值：查询结果对象
     */
    public <R, ID> R queryWithPassThrough(String prefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        // 核心逻辑：先查询缓存，若存在则直接返回，否则先查询数据库，再更新缓存，并设置失效时间兜底
        String key = prefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        // 缓存是真实数据
        if(StrUtil.isNotBlank(json)) {
            return JSONUtil.toBean(json, type);
        }
        // 缓存是空字符串
        if ("".equals(json)) {
            return null;
        }
        // 缓存是null，查数据库
        R r = dbFallback.apply(id);
        if(r == null) {
            // 查询为空值，缓存空数据
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        set(key, r, time, unit);
        return r;
    }

    public <R, ID> R queryWithLogicalExpire(String keyPrefix, String lockPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long toExpire, TimeUnit unit) {
        String key = keyPrefix + id;
        String lockKey = lockPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        // 缓存未命中
        if(StrUtil.isBlank(json)) {
            return null;
        }
        // 缓存命中，判断逻辑时间是否过期
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();
        // 未过期，直接返回
        if(expireTime.isAfter(LocalDateTime.now())) {
            return r;
        }
        try {
            // 已过期，尝试获取锁
            if(tryLock(lockKey)) {
                // 获取到锁了, 进行double check，检验此时是否其他线程插队完成了缓存的更新
                json = stringRedisTemplate.opsForValue().get(key);
                redisData = JSONUtil.toBean(json, RedisData.class);
                r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
                expireTime = redisData.getExpireTime();
                if(expireTime.isAfter(LocalDateTime.now())) {
                    unlock(lockKey);
                    return r;
                }
                // double check 失败，启动线程更新缓存
                CACHE_REBUILD_EXECUTOR.submit(() -> {
                    try {
                        R result = dbFallback.apply(id);
                        setWithLogicalExpire(key, result, toExpire, unit);
                        // 更新完成后解锁
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    } finally {
                        unlock(lockKey);
                    }
                });
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        // 已过期，直接返回旧数据
        return r;
    }
}
