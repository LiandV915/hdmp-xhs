/*
package com.hmdp.utils;
import com.hmdp.aiService.EmbeddingService;
import jakarta.annotation.Resource;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.stream.Collectors;

*/
/**
 * 基于 Redis Stack 向量索引的 ChatMemory
 *//*

@Component
public class RedisStackChatMemory {

    @Resource
    private EmbeddingService embeddingService;

    @Resource
    private RedisVectorClient redisVectorClient; // 封装 Redis Stack 向量操作

    // 每条消息最大存储时间（秒），可根据业务调整
    private static final long MESSAGE_TTL = 7 * 24 * 3600;

    // 每个用户最多保留消息条数
    private static final int MAX_HISTORY = 50;

    */
/**
     * 写入用户消息或助手回复
     * @param userId 用户ID
     * @param role "user" 或 "assistant"
     * @param content 消息内容
     *//*

    public void appendMessage(Long userId, String role, String content) {
        String key = "user:chat:" + userId;

        // 1. 构造向量文本
        String vectorText = role + "：" + content;

        // 2. 生成 embedding
        float[] embedding = embeddingService.embed(vectorText);

        // 3. 构造 metadata
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("userId", userId.toString());
        metadata.put("role", role);
        metadata.put("timestamp", System.currentTimeMillis());

        // 4. 构造 Document 并写入 Redis Stack
        Document doc = new Document(vectorText, metadata);
        redisVectorClient.ad*/
/*//*
d(key, doc, embedding, MESSAGE_TTL);

        // 5. 保证历史条数限制
        redisVectorClient.trim(key, MAX_HISTORY);
    }

    /**
     * 根据用户提问检索上下文
     * @param userId 用户ID
     * @param question 当前问题
     * @param topK 检索最相关的历史消息条数
     * @return 与问题最相关的历史消息列表（带角色信息）
     *//*

    public List<String> retrieveContext(Long userId, String question, int topK) {
        String key = "user:chat:" + userId;

        // 1. 生成问题 embedding
        float[] queryVector = embeddingService.embed(question);

        // 2. KNN 检索 topK
        List<Document> results = redisVectorClient.knnSearch(key, queryVector, topK);

        // 3. 拼接 role + 文本作为上下文
        return results.stream()
                .map(doc -> doc.getMetadata().get("role") + "：" + doc.getContent())
                .collect(Collectors.toList());
    }

}*/
