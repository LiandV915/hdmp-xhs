package com.hmdp.controller;

import com.hmdp.dto.Result;
import com.hmdp.service.IUserNoticeService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

/**
 * 消息通知 Controller
 * 通知由后端在点赞/评论/关注时自动写入，前端只做查询与管理
 */
@RestController
@RequestMapping("/notice")
public class NoticeController {

    @Resource
    private IUserNoticeService userNoticeService;

    /** 通知列表（分页，按时间倒序，含已读/未读） */
    @GetMapping("/list")
    public Result listNotices(
            @RequestParam(value = "current", defaultValue = "1") Integer current) {
        return userNoticeService.listNotices(current);
    }

    /** 未读通知数量 */
    @GetMapping("/unread/count")
    public Result getUnreadCount() {
        return userNoticeService.getUnreadCount();
    }

    /** 标记单条通知为已读 */
    @PutMapping("/read/{id}")
    public Result markRead(@PathVariable Long id) {
        return userNoticeService.markRead(id);
    }

    /** 删除单条通知 */
    @DeleteMapping("/{id}")
    public Result deleteNotice(@PathVariable Long id) {
        return userNoticeService.deleteNotice(id);
    }
}
