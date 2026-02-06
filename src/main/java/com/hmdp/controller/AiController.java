package com.hmdp.controller;

import com.hmdp.aiService.AiChatService;
import com.hmdp.aiService.impl.AiChatServiceImpl;
import com.hmdp.utils.UserHolder;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/ai")
public class AiController {

    @Resource
    private AiChatService aiChatService;
    /**
     * RAG + 流式输出
     * 实现ai小助手
     */
    @GetMapping(value = "/chat", produces = "text/html;charset=utf-8")
    public Flux<String> chat(String prompt) {
        Long userId= UserHolder.getUser().getId();
        return aiChatService.chat(prompt,userId);
    }
}
