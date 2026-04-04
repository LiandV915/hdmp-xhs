package com.hmdp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 用户消息通知表（tb_user_notice）
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_user_notice")
public class UserNotice implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 接收通知的用户id */
    private Long userId;

    /** 触发通知的用户id */
    private Long fromUserId;

    /**
     * 通知类型
     * 1:点赞博客  2:评论博客  3:关注我
     */
    private Integer type;

    /** 关联资源id（blogId / commentId） */
    private Long relatedId;

    /** 通知内容摘要（如博客标题、评论片段） */
    private String content;

    /** 0:未读  1:已读 */
    private Integer isRead;

    private LocalDateTime createTime;
}
