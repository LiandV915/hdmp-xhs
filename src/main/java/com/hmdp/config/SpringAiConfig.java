package com.hmdp.config;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
@Data
@Configuration
public class SpringAiConfig {
    @Bean
    public ChatClient testChatClient(OpenAiChatModel model) {
        return ChatClient
                .builder(model)
                .defaultSystem("你是科比，请以科比的口吻回答问题")
                .defaultAdvisors(new SimpleLoggerAdvisor())
                .build();
    }
}
