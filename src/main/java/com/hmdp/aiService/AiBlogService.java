package com.hmdp.aiService;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.VO.BlogGenerateVO;
import com.hmdp.dto.BlogDraftDTO;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;


@Service
@RequiredArgsConstructor
public class AiBlogService {

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;

    public BlogGenerateVO generateBlog(String userPrompt, Long userId) {

        String systemPrompt = """
        你是一个本地生活社区的内容创作助手。

        ⚠️ 只允许返回合法 JSON，不要返回任何解释性文字或 Markdown。

        JSON 格式：
        {
          "title": "不超过30字",
          "content": "不超过800字",
          "tags": "3~5个关键词，用英文逗号分隔"
        }
        """;

        String json = chatClient.prompt()
                .system(systemPrompt)
                .user("""
                用户ID：%d
                用户需求：%s
                """.formatted(userId, userPrompt))
                .call()
                .content();

        // 1️⃣ 反序列化
        BlogDraftDTO draft;
        try {
            draft = objectMapper.readValue(json, BlogDraftDTO.class);
        } catch (Exception e) {
            throw new RuntimeException("AI 返回格式解析失败", e);
        }

        // 2️⃣ 组装 VO
        BlogGenerateVO vo = new BlogGenerateVO();
        vo.setTitle(draft.getTitle());
        vo.setContent(draft.getContent());

        List<String> tags = Arrays.stream(draft.getTags().split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();

        vo.setTags(tags);

        return vo;
    }
}


