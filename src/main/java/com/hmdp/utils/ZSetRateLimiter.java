package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

@Component
public class ZSetRateLimiter {

    @Resource
    private StringRedisTemplate stringRedisTemplate;


    private static final int WINDOW_SECONDS=60;

    public boolean allowRequest(String key, int maxCount) {

        long now = System.currentTimeMillis();
        long windowStart = now - WINDOW_SECONDS * 1000L;

        // 1. 删除窗口外请求
        stringRedisTemplate.opsForZSet()
                .removeRangeByScore(key, 0, windowStart);

        // 2. 当前窗口请求数 统计最近 60 秒内还有多少次请求
        Long count = stringRedisTemplate.opsForZSet().zCard(key);
        if (count != null && count >= maxCount) {
            return false;
        }

        // 3. 记录本次请求
        stringRedisTemplate.opsForZSet()
                .add(key, String.valueOf(now), now);

        // 4. 设置过期时间,60秒，防止用户一直不访问了占内存，
        stringRedisTemplate.expire(key, WINDOW_SECONDS, TimeUnit.SECONDS);

        return true;
    }
}
