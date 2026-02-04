package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
import java.util.concurrent.TimeUnit;


@Component
public class CacheClient {
    @Resource
    StringRedisTemplate stringRedisTemplate;

    @Autowired
    RedissonClient redissonClient;

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);


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
     * 防止缓存穿透查数据
     * (不确定返回类型，id类型，用泛型,无法确定调用哪个mapper，函数式编程传方法）
     * 在「逻辑过期」这套方案下，一般不再需要靠“随机 TTL”来防缓存雪崩了。
     * 但这里面有一个前提条件，我给你讲清楚你才能在面试 / 项目说明里说得住。
     */
    public <R, ID> R queryPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallBack, Long time, TimeUnit timeUnit) {
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotEmpty(json)) {
            return JSONUtil.toBean(json, type);
        }
        if (json != null) {//查到“”
            return null;
        }
        R r = dbFallBack.apply(id);//传入某个类对应的查数据库的方法
        if (r == null) {//缓存空对象
            stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.SECONDS);
            return null;
        }
        long ttl = time + RandomUtil.randomLong(0, 300); // 随机 0~300 秒
        this.set(key, JSONUtil.toJsonStr(r), ttl, timeUnit);//缓存正确数据
        return r;
    }

    /**
     * 防止缓存击穿（设置逻辑过期时间）、互斥锁.同时通过
     */
    public <R, ID> R queryWithLogicalExpire(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallBack, Long time, TimeUnit timeUnit) {
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        if ("".equals(json)) {//查到的是空对象
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
                    this.setWithExpire(key, JSONUtil.toJsonStr(r1), time, timeUnit);//更新缓存
                } catch (Exception e) {

                } finally {
                    lock.unlock();
                    ;
                }
            });
        }
        return r;

    }
/**
 * 逻辑过期防穿透
 * 缓存过期不立即删除，而是继续返回脏数据，保证高并发请求可用性。
 * 对热点数据提前预热，减少第一次访问的数据库压力。
 * Redisson 分布式锁
 * 用 RLock 替代自己写的 setIfAbsent 锁，更安全，支持分布式多节点。
 * 避免多个线程同时重建缓存，保护 DB。
 * 双检机制
 * 异步刷新缓存前再次查询 Redis，避免重复查询 DB。
 * 保证即使多个线程竞争锁，只有一个真正去 DB 查询和更新缓存。
 * 异步刷新
 * 用线程池异步更新缓存，避免阻塞请求，提高系统吞吐
 */

}
