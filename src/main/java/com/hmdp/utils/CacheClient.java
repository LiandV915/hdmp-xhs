package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;

import java.util.concurrent.*;
import java.util.function.Function;

import com.hmdp.entity.Shop;
import net.sf.jsqlparser.expression.JsonAggregateFunction;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation
.Resource;
import java.time.LocalDateTime;


@Component
public class CacheClient {
    @Resource
    StringRedisTemplate stringRedisTemplate;

    @Autowired
    RedissonClient redissonClient;

    private static final ExecutorService CACHE_REBUILD_EXECUTOR =
            new ThreadPoolExecutor(
                    5,                      // corePoolSize
                    10,                     // maxPoolSize
                    60,
                    TimeUnit.SECONDS,
                    new LinkedBlockingQueue<>(100),
                    Executors.defaultThreadFactory(),
                    new ThreadPoolExecutor.CallerRunsPolicy()
            );

    /**
     * 存数据
     *
     * @param key
     * @param value
     * @param time
     * @param timeUnit
     */
    public void set(String key, Object value, Long time, TimeUnit timeUnit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, timeUnit);
    }

    /**
     * 存数据逻辑过期
     */
    public void setWithExpire(String key, Object value, Long time, TimeUnit timeUnit) {
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(timeUnit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }


    /**
     * 项目中普通查询使用旁路缓存模式，解决缓存穿透问题；
     * 对于热点数据，引入逻辑过期和互斥锁，防止缓存击穿，在保证系统可用性的同时，接受短暂的数据不一致。
     🔹 ① 普通 key（占 90%+）
     用 旁路缓存
     TTL 随机化
     目标：防缓存雪崩
     🔹 ② 热点 key（占极少数）
     用 逻辑过期 + 互斥锁
     缓存几乎“永不过期”
     */

    /**
     * 旁路缓存思想
     * 旁路缓存 + 分布式互斥锁防击穿
     *热点 Blog 过期时：
     * 仍然只有一个线程去访问数据库。
     * 其它并发线程不会同时打到数据库 → 击穿被阻止。
     * 和逻辑过期方案相比：
     * queryPassThrough 会阻塞或轮询等待锁。
     * queryWithLogicalExpire 则是返回旧数据 + 异步刷新 → 不阻塞线程
     */
    public <R, ID> R queryPassThrough(
            String keyPrefix,
            ID id,
            Class<R> type,
            Function<ID, R> dbFallBack,
            Long time,
            TimeUnit timeUnit
    ) {
        String key = keyPrefix + id;
        // 1. 先查 Redis
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotEmpty(json)) {
            return JSONUtil.toBean(json, type);
        }
        if (json != null) { // 查到空值，防穿透
            return null;
        }
        // 2. 获取分布式锁
        String lockKey = "lock:" + key;
        RLock lock = redissonClient.getLock(lockKey);
        try {
            // 尝试加锁，最多等待100毫秒，锁过期时间 10秒
            boolean isLock = lock.tryLock(100, 10, TimeUnit.SECONDS);
            if (isLock) {
                // 再次查询 Redis，防止锁竞争期间已经有线程写入
                json = stringRedisTemplate.opsForValue().get(key);
                if (StrUtil.isNotEmpty(json)) {
                    return JSONUtil.toBean(json, type);
                }
                if (json != null) {
                    return null;
                }

                // 3. 数据库回退查询
                R r = dbFallBack.apply(id);
                if (r == null) {
                    stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.SECONDS);
                    return null;
                }
                // 4. 写入 Redis，随机 TTL 防雪崩
                long ttl = time + RandomUtil.randomLong(0, 300);
                stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(r), ttl, timeUnit);
                return r;
            } else {
                // 没拿到锁的线程，休眠50ms后重试
                Thread.sleep(50);
                return queryPassThrough(keyPrefix, id, type, dbFallBack, time, timeUnit);
            }
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }


    /**
     * 这个方案主要是为了解决缓存击穿问题，通过逻辑过期和分布式锁保证只有一个线程重建缓存，其余请求返回旧数据。
     * 同时通过缓存空对象可以一定程度解决缓存穿透。
     * 由于 Redis key 本身不会设置 TTL，而是使用逻辑过期时间，因此也可以避免大量 key 同时失效，从而在一定程度上缓解缓存雪崩。
     */
    public <R, ID> R queryWithLogicalExpire(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallBack, Long time, TimeUnit timeUnit) {
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        if (json == null) {
            // 缓存未命中 →
            // 逻辑过期方案通常只用于“热点数据”，这些数据在系统启动时就已经预热到缓存中，因此正常情况下不会出现缓存未命中。
            return null;
        }
        if ("".equals(json)) {
            // 命中空缓存
            return null;
        }
        //能查到数据，则判断是否逻辑过期
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();
        if (expireTime.isAfter(LocalDateTime.now())) {//如果未过期，直接返回查到的数据即可
            return r;
        }
        //如果缓存中数据已过期，开启新线程更新缓存，
        String lockkey = RedisConstants.LOCK_SHOP_KEY + id;
        RLock lock = redissonClient.getLock(lockkey);
        //如果得到了锁，开启新线程更新缓存，但是返回脏数据，以一致性换可用性。
        //如果拿不到锁，就直接返回数据，提高效率
        if (lock.tryLock()) {
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    // double check
                    String freshJson = stringRedisTemplate.opsForValue().get(key);
                    RedisData freshData = JSONUtil.toBean(freshJson, RedisData.class);
                    if (freshData.getExpireTime().isAfter(LocalDateTime.now())) {
                        return;
                    }
                    R r1 = dbFallBack.apply(id);
                    if (r1 == null) {
                        stringRedisTemplate.opsForValue().set(key, "", 2, TimeUnit.MINUTES);
                        return;
                    }//查不到数据，缓存空对象，防止缓存穿透
                    this.setWithExpire(key, r1, time, timeUnit);//更新缓存
                } catch (Exception e) {

                } finally {
                    lock.unlock();

                }
            });
        }
        return r;

    }

}
