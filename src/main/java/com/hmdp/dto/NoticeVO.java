package com.hmdp.dto;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 消息通知展示 VO
 */
@Data
public class NoticeVO {

    private Long id;

    /**
     * 通知类型
     * 1:点赞博客  2:评论博客  3:关注我
     */
    private Integer type;

    /** 关联资源id（blogId / commentId） */
    private Long relatedId;

    /** 通知内容摘要 */
    private String content;

    /** 0:未读  1:已读 */
    private Integer isRead;

    private LocalDateTime createTime;

    /** 触发通知的用户id */
    private Long fromUserId;

    /** 触发通知的用户昵称 */
    private String fromUserNickName;

    /** 触发通知的用户头像 */
    private String fromUserIcon;
}
