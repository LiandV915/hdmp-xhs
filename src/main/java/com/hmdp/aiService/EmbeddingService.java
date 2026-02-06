package com.hmdp.aiService;

public interface EmbeddingService {

    /**
     * 将文本向量化
     */
    float[] embed(String text);
}
