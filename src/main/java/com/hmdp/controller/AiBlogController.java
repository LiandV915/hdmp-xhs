package com.hmdp.controller;

import com.hmdp.VO.BlogGenerateVO;
import com.hmdp.aiService.AiBlogService;
import com.hmdp.dto.BlogGenerateDTO;
import com.hmdp.utils.UserHolder;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/ai/blog")
public class AiBlogController {

    @Resource
    private AiBlogService aiBlogService;

    @PostMapping("/generate")
    public BlogGenerateVO generate(@RequestBody BlogGenerateDTO dto) {
        Long userId = UserHolder.getUser().getId(); // ThreadLocal
        return aiBlogService.generateBlog(dto.getPrompt(), userId);
    }
}

