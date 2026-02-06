package com.hmdp.dto;

import lombok.Data;

@Data
public class BlogDraftDTO {
    private String title;
    private String content;
    private String tags; // "美食,探店,周末"
}