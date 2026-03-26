package com.hmdp.aiService.impl;

import com.hmdp.aiService.VectorSearchService;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.autoconfigure.vectorstore.redis.RedisVectorStoreProperties;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.search.Document;
import redis.clients.jedis.search.Query;
import redis.clients.jedis.search.SearchResult;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class VectorSearchServiceImpl implements VectorSearchService {

    private static final int DEFAULT_TOP_K = 3;

    /**
     * 示例阈值，实际要按你的距离度量调
     */
    private static final double MEMORY_SCORE_THRESHOLD = 0.35D;
    private static final double KB_SCORE_THRESHOLD = 0.40D;
    private final JedisPooled vectorJedisPooled;
    private final RedisVectorStoreProperties properties;
    @Resource
    private VectorStore vectorStore;

    /**
     * 查询用户长期记忆，基于 userId 过滤 + embedding 向量检索，返回相关上下文列表
     * @param userId
     * @param queryVector
     * @param topK
     * @return
     */
    @Override
    public List<String> searchUserMemory(Long userId, float[] queryVector, int topK) {
        if (userId == null || queryVector == null || queryVector.length == 0) {
            return List.of();
        }

        int finalTopK = topK > 0 ? topK : DEFAULT_TOP_K;
        byte[] vectorBytes = floatArrayToByteArray(queryVector);
        // userId / type 的写法要与你的索引 schema 一致
        String knnQuery = "(@userId:{" + userId + "} @type:{chat_memory})=>[KNN "
                + finalTopK + " @embedding $vector AS score]";

        return doVectorSearch(knnQuery, vectorBytes, MEMORY_SCORE_THRESHOLD);
    }

    /**
     * 查询知识库，基于 embedding 向量检索，返回相关上下文列表
     * @param queryVector
     * @param topK
     * @return
     */
    @Override
    public List<String> searchKnowledge(float[] queryVector, int topK) {
        if (queryVector == null || queryVector.length == 0) {
            return List.of();
        }

        int finalTopK = topK > 0 ? topK : DEFAULT_TOP_K;
        byte[] vectorBytes = floatArrayToByteArray(queryVector);

        // 知识库检索：查公共知识
        String knnQuery = "(@type:{kb})=>[KNN "
                + finalTopK + " @embedding $vector AS score]";

        return doVectorSearch(knnQuery, vectorBytes, KB_SCORE_THRESHOLD);
    }

    /**
     * 保存用户的聊天记忆到向量存储中，构造文档内容和元数据，并调用 VectorStore 的 add 方法持久化
     * @param userId
     * @param question
     * @param answer
     */
    @Override
    public void saveChatMemory(Long userId, String question, String answer) {
        if (userId == null || isBlank(question) || isBlank(answer)) {
            return;
        }

        String content = """
                用户问题：%s
                AI回答：%s
                """.formatted(question.trim(), answer.trim());

        org.springframework.ai.document.Document document =
                new org.springframework.ai.document.Document(
                        content,
                        Map.of(
                                "userId", String.valueOf(userId),
                                "type", "chat_memory",
                                "timestamp", String.valueOf(System.currentTimeMillis())
                        )
                );

        vectorStore.add(List.of(document));
    }

    /**
     * 执行向量搜索的核心方法，构造 KNN 查询，执行检索，并根据分数阈值过滤结果，返回相关上下文列表
     * @param knnQuery
     * @param vectorBytes
     * @param threshold
     * @return
     */
    private List<String> doVectorSearch(String knnQuery, byte[] vectorBytes, double threshold) {
        Query query = new Query(knnQuery)
                .addParam("vector", vectorBytes)
                .setSortBy("score", true)
                .returnFields("content", "score", "type", "userId", "timestamp")
                .dialect(2);

        SearchResult result = vectorJedisPooled.ftSearch(properties.getIndex(), query);// 直接使用 Jedis 执行 KNN 查询，获取原始结果
        List<String> contexts = new ArrayList<>();
        for (Document doc : result.getDocuments()) {
            Object contentObj = doc.get("content");
            Object scoreObj = doc.get("score");

            if (contentObj == null || scoreObj == null) {
                continue;
            }

            double score;
            try {
                score = Double.parseDouble(scoreObj.toString());
            } catch (Exception e) {
                continue;
            }
            if (score <= threshold) {
                contexts.add(contentObj.toString());
            }
        }
        return contexts;
    }

    /**
     * 将 float 数组转换为 Redis 向量搜索所需的 byte 数组格式（小端序）
     * @param vector
     * @return
     */
    private byte[] floatArrayToByteArray(float[] vector) {
        ByteBuffer buffer = ByteBuffer
                .allocate(vector.length * Float.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN);

        for (float v : vector) {
            buffer.putFloat(v);
        }
        return buffer.array();
    }

    private boolean isBlank(String text) {
        return text == null || text.trim().isEmpty();
    }
}
