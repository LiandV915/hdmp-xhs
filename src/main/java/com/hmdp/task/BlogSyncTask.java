package com.hmdp.task;

import com.hmdp.entity.Blog;
import com.hmdp.service.IBlogService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Component
@Slf4j
public class BlogSyncTask {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IBlogService blogService;

    /**
     * 每5分钟同步一次 Redis -> MySQL
     * 基于 dirty Set 只同步最近有变化的 blog，减轻定时任务压力
     * 由于点赞、浏览量等是高频操作，如果每次都同步数据库，会影响性能，
     * 所以采用“最终一致性”的方案，通过定时任务将 Redis 中的数据批量同步到数据库。
     * 数据丢了怎么办？开启了 AOF 持久化，即使 Redis 重启也可以恢复数据，同时定时对账可以修复少量不一致问题。
     */
    @Scheduled(cron = "0 */5 * * * ?")
    public void syncBlogStats() {
        log.info("开始同步 blog 浏览量、点赞数和评论数");
        syncDirtySet("view");
        syncDirtySet("liked");
        syncDirtySet("comments");
    }

    /**
     * 通用方法：同步脏数据
     * @param type view | liked | comments
     */
    private void syncDirtySet(String type) {
        String zsetKey = "blog:" + type;
        String dirtySetKey = "blog:dirty:" + type;

        Set<String> dirtyBlogs = stringRedisTemplate.opsForSet().members(dirtySetKey);
        if (dirtyBlogs == null || dirtyBlogs.isEmpty()) {
            return;
        }

        List<Blog> list = new ArrayList<>();
        for (String blogIdStr : dirtyBlogs) {
            Double score = stringRedisTemplate.opsForZSet().score(zsetKey, blogIdStr);
            if (score != null) {
                Blog blog = new Blog();
                blog.setId(Long.valueOf(blogIdStr));
                switch (type) {
                    case "view" -> blog.setViewCounts(score.intValue());
                    case "liked" -> blog.setLiked(score.intValue());
                    case "comments" -> blog.setComments(score.intValue());
                }
                list.add(blog);
            }
        }

        if (!list.isEmpty()) {
            blogService.updateBatchById(list);
        }
        // 同步完成后清空 dirty Set
        stringRedisTemplate.delete(dirtySetKey);
        log.info("同步完成 [{}] 条 blog [{}]", list.size(), type);
    }
}