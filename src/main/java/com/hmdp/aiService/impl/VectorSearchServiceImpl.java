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

        // 2. 构造 KNN 查询
        // 注意：vector_field 一定要和你建 index 时的字段名一致
        String knnQuery =
                "*=>[KNN " + topK + " @embedding $vector AS score]";

        Query query = new Query(knnQuery)
                .addParam("vector", vectorBytes)
                .setSortBy("score", true)
                .returnFields("content", "score")
                .dialect(2);

        // 3. 执行搜索
        SearchResult result = vectorJedisPooled.ftSearch(
                properties.getIndex(),
                query
        );

        // 4. 提取内容
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
