package com.hmdp.task;

import cn.hutool.core.util.RandomUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Blog;
import com.hmdp.service.IBlogService;
import jakarta.annotation.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Component
public class BlogHotCacheTask {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IBlogService blogService;

    private static final String BLOG_HOT_KEY = "blog:hot"+ LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
    private static final String BLOG_CACHE_KEY = "blog:cache:";

    /**
     * 每小时执行一次
     */
    @Scheduled(cron = "0 0 * * * ?")
    public void cacheHotBlogs() {
        // 1 获取热度前10的 blogId
        Set<String> blogIds = stringRedisTemplate.opsForZSet()
                .reverseRange(BLOG_HOT_KEY, 0, 9);

        if (blogIds == null || blogIds.isEmpty()) {
            return;
        }
        for (String blogId : blogIds) {
            // 2 查询数据库
            Blog blog = blogService.getById(Long.valueOf(blogId));
            if (blog == null) {
                continue;
            }
            // 3 设置随机TTL 防止雪崩
            int ttl = 3600 + RandomUtil.randomInt(600);

            // 4 写入Redis
            stringRedisTemplate.opsForValue().set(
                    BLOG_CACHE_KEY + blogId,
                    JSONUtil.toJsonStr(blog),
                    ttl,
                    TimeUnit.SECONDS
            );
        }
    }
}
