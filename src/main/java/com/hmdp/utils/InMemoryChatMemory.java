package com.hmdp.utils;

import jakarta.annotation.Resource;
import org.springframework.ai.document.Document;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

@Component
public class InMemoryChatMemory {


    @Resource
    private RedisTemplate<String, String> redisTemplate;

    //短期内存设计
    //Redis List + TTL 7天，最多保留最近10条消息，作为对话上下文的短期记忆
    //长期记忆设计
    //向量库存储用户所有问答对，基于 embedding + metadata(userId) 做 RAG 检索
    //优化方向
    //Redis Pipeline / 事务保证顺序
    //token控制，避免 prompt 超长
    //元数据过滤，向量检索按 userId 隔离

    public List<String> getRecentContext(Long userId) {
        List<String> list = redisTemplate.opsForList()
                .range("chat:history:" + userId, 0, 9);

        Collections.reverse(list); // 关键！
        return list;
    }

    /*
    我短期内存只保留最近 10 条消息，每条消息占用极小内存，保证 prompt 不超长；
    同时设置 TTL 7 天，用于清理长期不活跃用户，避免冷用户占用 Redis 空间，实现整体内存管理。
     */
    public void appendMessage(Long userId, String role, String content) {
        String key = "chat:history:" + userId;

        redisTemplate.opsForList().leftPush(key, role + ":" + content);
        redisTemplate.opsForList().trim(key, 0, 49);

        // 设置TTL（关键！）
        redisTemplate.expire(key, Duration.ofDays(7));
    }

}
