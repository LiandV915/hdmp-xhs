package com.hmdp.aiService;

import reactor.core.publisher.Flux;

import java.util.List;


public interface AiChatService {
    Flux<String> chat(String question,Long userId);

}