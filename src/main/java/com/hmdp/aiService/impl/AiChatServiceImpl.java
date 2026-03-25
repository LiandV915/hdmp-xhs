package com.hmdp.aiService.impl;

import com.hmdp.aiService.AiChatService;
import com.hmdp.aiService.EmbeddingService;
import com.hmdp.aiService.VectorSearchService;
import com.hmdp.utils.InMemoryChatMemory;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.Deque;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AiChatServiceImpl implements AiChatService {

    private final ChatClient chatClient;
    private final EmbeddingService embeddingService;
    private final VectorSearchService vectorSearchService;

    @Resource
    InMemoryChatMemory chatMemory;


    @Override
    public Flux<String> chat(String question,Long userId) {

        // 1. 读取上下文（不给任何判断规则）
        Deque<String> memory = chatMemory.getContext(userId);//基于userid，保证了每个用户的对话记忆是分开的，不会混淆。
        String contextText = String.join("\n", memory);

        // 2. 直接用原始 question 做 embedding（不做 rewrite）
        float[] queryVector = embeddingService.embed(question);

        // 3. 向量召回
        List<String> contexts = vectorSearchService.search(queryVector, 3);

        // 4. Prompt 环绕增强
        String ragPrompt = buildPrompt(contextText, contexts, question);
        //你是一个本地生活推荐助手
        //
        //上下文：
        //用户：推荐火锅
        //助手：海底捞不错
        //
        //参考信息：
        //海底捞火锅评分4.7
        //重庆火锅评分4.6
        //
        //用户问题：
        //附近还有吗

        // 5. 调用 LLM（streaming）
        Flux<String> flux = chatClient.prompt()
                .user(ragPrompt)
                .stream()
                .content();

        // 6. 写回 memory（用户输入 + AI 输出摘要）
        chatMemory.append(userId, "用户", question);
        chatMemory.append(userId, "助手", "正在推荐相关内容");

        /**
         * 写回的时候也带了 userId
         * 所以不同用户的输入和助手回答都存到各自的上下文里
         */

        return flux;
    }


    public String buildPrompt(String chatContext,
                               List<String> ragContexts,
                               String question) {

        String knowledge = String.join("\n\n", ragContexts);

        return """
            你是一个本地生活推荐助手。

            以下是与该用户最近对话相关的上下文（可能不完整）：
            %s

            以下是检索到的参考信息：
            %s

            请结合上下文，理解用户当前问题中的省略信息，并给出回答。

            用户当前问题：
            %s
            """.formatted(chatContext, knowledge, question);
    }

}
