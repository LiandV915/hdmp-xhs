package com.hmdp.aiService.impl;

import com.hmdp.aiService.VectorSearchService;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.autoconfigure.vectorstore.redis.RedisVectorStoreProperties;
import org.springframework.stereotype.Service;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.search.Document;
import redis.clients.jedis.search.Query;
import redis.clients.jedis.search.SearchResult;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class VectorSearchServiceImpl implements VectorSearchService {

    private final JedisPooled vectorJedisPooled;
    private final RedisVectorStoreProperties properties;

    @Override
    public List<String> search(float[] queryVector, int topK) {

        // 1. 向量转 byte[]（Redis VECTOR 必须）
        byte[] vectorBytes = floatArrayToByteArray(queryVector);

        // 2. 构造 KNN 查询 这句是整段代码的灵魂
        String knnQuery =
                "*=>[KNN " + topK + " @embedding $vector AS score]";

        Query query = new Query(knnQuery)
                .addParam("vector", vectorBytes)//把你的查询向量传进去
                .setSortBy("score", true)//按相似度排序（越小越相似）
                .returnFields("content", "score")
                .dialect(2);

        // 3. 执行搜索 去 Redis 里查向量库
        //RedisTemplate 主要面向基础数据结构操作，对 RediSearch 的支持较弱，
        // 而 Jedis 提供了对 FT.SEARCH 等命令的原生支持，因此在向量检索场景下选择 Jedis 更灵活。
        SearchResult result = vectorJedisPooled.ftSearch(
                properties.getIndex(),
                query
        );

        // 4. 提取内容 把查到的文本拿出来
        List<String> contexts = new ArrayList<>();
        for (Document doc : result.getDocuments()) {
            Object content = doc.get("content");
            if (content != null) {
                contexts.add(content.toString());
            }
        }

        return contexts;
    }

    /**
     * float[] → byte[]（小端序）
     */
    private byte[] floatArrayToByteArray(float[] vector) {
        ByteBuffer buffer = ByteBuffer
                .allocate(vector.length * 4)
                .order(ByteOrder.LITTLE_ENDIAN);

        for (float v : vector) {
            buffer.putFloat(v);
        }
        return buffer.array();
    }
}
