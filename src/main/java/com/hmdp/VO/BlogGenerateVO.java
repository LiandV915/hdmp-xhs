package com.hmdp.VO;

import lombok.Data;

import java.util.List;

@Data
public class BlogGenerateVO {
    private String title;
    private String content;
    private List<String> tags;
}