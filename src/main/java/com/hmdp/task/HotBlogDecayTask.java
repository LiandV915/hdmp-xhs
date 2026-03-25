package com.hmdp.task;

import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

@Component
public class HotBlogDecayTask {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 定时任务：每日更新热点博客排行榜
     *
     * 核心策略：
     * 1️⃣ 使用“每日增量热度 + 总榜滚动衰减”生成总榜
     * 2️⃣ 避免全量扫描历史数据，通过Top-N截断控制数据规模
     * 3️⃣ 基于总榜派生每个标签的热度榜，支持标签召回和推荐
     */
    @Scheduled(cron = "0 0 0 * * ?")
    public void updateHotBlog() {
        double DECAY = 0.9;
        int TOP_N = 1000;
        String HOT_KEY = "blog:hot";
        // 1️⃣ 衰减总榜（只处理TopN）
        Set<ZSetOperations.TypedTuple<String>> topBlogs =
                stringRedisTemplate.opsForZSet()
                        .reverseRangeWithScores(HOT_KEY, 0, TOP_N - 1);

        if (topBlogs != null) {
            for (var t : topBlogs) {
                stringRedisTemplate.opsForZSet()
                        .add(HOT_KEY, t.getValue(), t.getScore() * DECAY);
            }
        }
        // 2️⃣ 累加昨天热度
        String yesterdayKey = "blog:hot:" + LocalDate.now().minusDays(1)
                .format(DateTimeFormatter.BASIC_ISO_DATE);

        Set<ZSetOperations.TypedTuple<String>> yesterdayBlogs =
                stringRedisTemplate.opsForZSet()
                        .rangeWithScores(yesterdayKey, 0, -1);

        if (yesterdayBlogs != null) {
            for (var t : yesterdayBlogs) {
                stringRedisTemplate.opsForZSet()
                        .incrementScore(HOT_KEY, t.getValue(), t.getScore());
            }
        }
        // 3️⃣ 截断 TopN
        Long size = stringRedisTemplate.opsForZSet().zCard(HOT_KEY);
        if (size != null && size > TOP_N) {
            stringRedisTemplate.opsForZSet()
                    .removeRange(HOT_KEY, 0, size - TOP_N - 1);
        }
        // ==========================
        // 4️⃣ 派生标签榜（优化版🔥）
        // ==========================
        Set<ZSetOperations.TypedTuple<String>> finalTopBlogs =
                stringRedisTemplate.opsForZSet()
                        .reverseRangeWithScores(HOT_KEY, 0, TOP_N - 1);

        if (finalTopBlogs == null || finalTopBlogs.isEmpty()) {
            return;
        }

        // tagId -> (blogId -> score)
        Map<String, Map<String, Double>> tagMap = new HashMap<>();

        for (var t : finalTopBlogs) {
            String blogId = t.getValue();
            Double score = t.getScore();
            // ✅ 从 Redis 取 tag（避免查 DB）
            String tagKey = "blog:tags:" + blogId;
            Set<String> tags = stringRedisTemplate.opsForSet().members(tagKey);

            if (tags == null || tags.isEmpty()) continue;

            for (String tag : tags) {
                tagMap
                        .computeIfAbsent(tag, k -> new HashMap<>())
                        .put(blogId, score);
            }
        }
        // ✅ 批量写入（减少IO）
        for (var entry : tagMap.entrySet()) {
            String tag = entry.getKey();
            String tagHotKey = "blog:tag:" + tag;
            // 删除旧榜（避免脏数据）
            stringRedisTemplate.delete(tagHotKey);
            Map<String, Double> blogScores = entry.getValue();
            // ⚠️ 可选：只保留前200（防止某tag过大）
            blogScores.entrySet().stream()
                    .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                    .limit(200)
                    .forEach(e -> {
                        stringRedisTemplate.opsForZSet()
                                .add(tagHotKey, e.getKey(), e.getValue());
                    });
        }
        System.out.println("Hot blog & tag ranking updated.");
    }
}