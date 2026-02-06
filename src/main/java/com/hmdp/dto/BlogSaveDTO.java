package com.hmdp.dto;

import lombok.Data;

@Data
public class BlogSaveDTO {

    private Long shopId;
    private String title;
    private String images;
    private String content;
    private String tags;
}