package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements Lock{
    @Resource
    StringRedisTemplate stringRedisTemplate;

    public static final String ID_PREFIX = UUID.randomUUID().toString() + "-";


    /**
     * 锁的名称
     */
    private String name;

    public SimpleRedisLock(StringRedisTemplate stringRedisTemplate, String name) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.name = name;
    }

    /**
     * 获取锁
     * @param timeoutSecond
     * @return
     */
    @Override
    public boolean tryLock(long timeoutSecond) {
        String threadId = ID_PREFIX + Thread.currentThread().getId() + "";
        Boolean result = stringRedisTemplate.opsForValue()
                .setIfAbsent("lock:" + name, threadId, timeoutSecond, TimeUnit.SECONDS);//通过setnx尝试获取锁
        return Boolean.TRUE.equals(result);

    }


    /**
     * 释放锁
     */
    @Override
    public void unLock() {
        // 判断 锁的线程标识 是否与 当前线程一致
        String currentThreadFlag = ID_PREFIX + Thread.currentThread().getId();
        String redisThreadFlag = stringRedisTemplate.opsForValue().get("lock:" + name);
        if (currentThreadFlag != null &&currentThreadFlag.equals(redisThreadFlag)) {
            // 一致，说明当前的锁就是当前线程的锁，可以直接释放
            stringRedisTemplate.delete("lock:" + name);
        }
        // 不一致，不能释放
    }





}
