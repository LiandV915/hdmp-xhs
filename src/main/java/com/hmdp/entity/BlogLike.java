package com.hmdp.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("tb_blog_like")
public class BlogLike {
    private Long id;
    private Long userId;
    private Long blogId;
    private LocalDateTime createTime;
}
