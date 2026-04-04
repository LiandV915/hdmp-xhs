package com.hmdp.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class CommentVO {
    private Long id;
    private Long blogId;
    private Long parentId;
    private Long answerId;
    private String content;
    private Integer liked;
    private Boolean isLike;
    private LocalDateTime createTime;

    /** 评论者信息 */
    private Long userId;
    private String nickName;
    private String icon;

    /** 子评论（仅根评论携带） */
    private List<CommentVO> children;
}
