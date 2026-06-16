package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.sql.Time;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    private final StringRedisTemplate stringRedisTemplate;

    // 新建线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    private final CacheClient cacheClient;

    public ShopServiceImpl(StringRedisTemplate stringRedisTemplate, CacheClient cacheClient) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.cacheClient = cacheClient;
    }

    /**
     * 更新店铺
     * @param shop
     */
    @Override
    public void update(Shop shop) {
        // 核心逻辑：先更新数据库，再删除缓存，等下次查询时更新
        Long id = shop.getId();
        if (id == null) {
            throw new RuntimeException("店铺id不能为空！");
        }
        updateById(shop);
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
    }

    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag); // 避免空指针
    }

    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }

    private void saveShop2Redis(Long id) {
        Shop shop = getById(id);
        // 逻辑过期时间存在RedisDate对象中，不设置TTL
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(20L));
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }
    /**
     * 根据id查询店铺
     * @param id
     * @return
     */
    @Override
    public Shop queryById(Long id) {
        return cacheClient.queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, this::getById, LOCK_SHOP_TTL, TimeUnit.SECONDS);
    }

    /**
     * 缓存击穿(逻辑过期)
     * @param id
     * @return
     */
    public Shop queryWithLogicalExpire(Long id) {
        String key = CACHE_SHOP_KEY + id;
        String lockKey = LOCK_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 缓存未命中
        if(StrUtil.isBlank(shopJson)) {
            return null;
        }
        // 缓存命中，判断逻辑时间是否过期
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        // 未过期，直接返回
        if(expireTime.isAfter(LocalDateTime.now())) {
            return shop;
        }
        try {
            // 已过期，尝试获取锁
            if(tryLock(lockKey)) {
                // 获取到锁了, 进行double check，检验此时是否其他线程插队完成了缓存的更新
                shopJson = stringRedisTemplate.opsForValue().get(key);
                redisData = JSONUtil.toBean(shopJson, RedisData.class);
                shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
                expireTime = redisData.getExpireTime();
                if(expireTime.isAfter(LocalDateTime.now())) {
                    unlock(lockKey);
                    return shop;
                }
                // double check 失败，启动线程更新缓存
                CACHE_REBUILD_EXECUTOR.submit(() -> {
                    try {
                        saveShop2Redis(id);
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
        return shop;
    }
    /**
     * 缓存穿透
     * @param id
     * @return
     */
    public Shop queryWithPassThrough(Long id) {
        // 核心逻辑：先查询缓存，若存在则直接返回，否则先查询数据库，再更新缓存，并设置失效时间兜底
        String key = CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 缓存是真实数据
        if(StrUtil.isNotBlank(shopJson)) {
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        // 缓存是空字符串
        if ("".equals(shopJson)) {
            throw new RuntimeException("店铺信息不存在！");
        }
        // 缓存是null，查数据库
        Shop shop = getById(id);
        if(shop == null) {
            // 查询为空值，缓存空数据
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            throw new RuntimeException("店铺不存在！");
        }
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return shop;
    }

    /**
     * 缓存击穿(互斥锁)+穿透
     * @param id
     * @return
     */
    public Shop queryWithMutex(Long id) {
        String key = CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 缓存是真实数据
        if(StrUtil.isNotBlank(shopJson)) {
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        // 缓存是空字符串
        if ("".equals(shopJson)) {
            throw new RuntimeException("店铺信息不存在！");
        }
        // 缓存是null，表明key不存在，需要从数据库中获取数据
        String lockKey = LOCK_SHOP_KEY + id;
        Shop shop;
        try {
            // 尝试获取互斥锁直到成功
            while(!tryLock(lockKey)) {
                shopJson = stringRedisTemplate.opsForValue().get(key);

                // 如果查到了，说明已经数据已经更新，直接返回
                if (StrUtil.isNotBlank(shopJson)) {
                    return JSONUtil.toBean(shopJson, Shop.class);
                }
                if ("".equals(shopJson)) {
                    throw new RuntimeException("店铺信息不存在！");
                }
                Thread.sleep(50);
                // 如果还是没查到，继续下一轮 while 循环抢锁
            }
            // 成功获取锁
            shop = getById(id);
            if(shop == null) {
                // 查询为空值，缓存空数据
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                throw new RuntimeException("店铺不存在！");
            }
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            unlock(lockKey);
        }
        return shop;
    }
}
