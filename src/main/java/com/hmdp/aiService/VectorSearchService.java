package com.hmdp.aiService;

import java.util.List;

public interface VectorSearchService {

    /**
     * 用户长期记忆检索
     */
    List<String> searchUserMemory(Long userId, float[] queryVector, int topK);

    /**
     * 知识库 RAG 检索
     */
    List<String> searchKnowledge(float[] queryVector, int topK);

    /**
     * 保存用户长期记忆
     */
    void saveChatMemory(Long userId, String question, String answer);
}
