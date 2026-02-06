package com.hmdp.aiService;

import java.util.List;

public interface VectorSearchService {
    List<String> search(float[] queryVector, int topK);
}
