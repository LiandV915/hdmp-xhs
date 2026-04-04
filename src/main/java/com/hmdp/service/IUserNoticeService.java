package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.Result;
import com.hmdp.entity.UserNotice;

public interface IUserNoticeService extends IService<UserNotice> {

    /**
     * 发送通知（内部调用，不对外暴露接口）
     *
     * @param toUserId   接收方用户id
     * @param fromUserId 触发通知的用户id
     * @param type       通知类型 1:点赞 2:评论 3:关注
     * @param relatedId  关联资源id（blogId / commentId）
     * @param content    通知内容摘要
     */
    void sendNotice(Long toUserId, Long fromUserId, Integer type, Long relatedId, String content);

    /** 通知列表（当前登录用户，分页） */
    Result listNotices(Integer current);

    /** 未读通知数量 */
    Result getUnreadCount();

    /** 标记单条通知为已读 */
    Result markRead(Long id);

    /** 删除单条通知 */
    Result deleteNotice(Long id);
}
