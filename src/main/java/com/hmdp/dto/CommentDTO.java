package com.hmdp.dto;

import lombok.Data;

@Data
public class CommentDTO {

    private Long blogId;

    private Long parentId;

    private Long answerId;

    private String content;

}
