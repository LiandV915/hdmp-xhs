package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.NoticeVO;
import com.hmdp.dto.Result;
import com.hmdp.entity.User;
import com.hmdp.entity.UserNotice;
import com.hmdp.mapper.UserNoticeMapper;
import com.hmdp.service.IUserNoticeService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class UserNoticeServiceImpl extends ServiceImpl<UserNoticeMapper, UserNotice>
        implements IUserNoticeService {

    @Autowired
    private IUserService userService;

    /**
     * 发送通知
     * - 自己给自己的操作（如给自己点赞）不发通知
     */
    @Override
    public void sendNotice(Long toUserId, Long fromUserId, Integer type, Long relatedId, String content) {
        if (toUserId == null || fromUserId == null) return;
        if (toUserId.equals(fromUserId)) return;
        UserNotice notice = new UserNotice()
                .setUserId(toUserId)
                .setFromUserId(fromUserId)
                .setType(type)
                .setRelatedId(relatedId)
                .setContent(StrUtil.subPre(content, 100))
                .setIsRead(0)
                .setCreateTime(LocalDateTime.now());
        save(notice);
    }

    @Override
    public Result listNotices(Integer current) {
        Long userId = UserHolder.getUser().getId();
        Page<UserNotice> page = query()
                .eq("user_id", userId)
                .orderByDesc("create_time")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));

        List<UserNotice> records = page.getRecords();
        if (records.isEmpty()) {
            return Result.ok(Collections.emptyList(), 0L);
        }

        // 批量查询触发通知的用户信息
        List<Long> fromUserIds = records.stream()
                .map(UserNotice::getFromUserId)
                .distinct()
                .collect(Collectors.toList());
        Map<Long, User> userMap = userService.listByIds(fromUserIds).stream()
                .collect(Collectors.toMap(User::getId, u -> u));

        List<NoticeVO> voList = records.stream().map(n -> {
            NoticeVO vo = new NoticeVO();
            vo.setId(n.getId());
            vo.setType(n.getType());
            vo.setRelatedId(n.getRelatedId());
            vo.setContent(n.getContent());
            vo.setIsRead(n.getIsRead());
            vo.setCreateTime(n.getCreateTime());
            vo.setFromUserId(n.getFromUserId());
            User fromUser = userMap.get(n.getFromUserId());
            if (fromUser != null) {
                vo.setFromUserNickName(fromUser.getNickName());
                vo.setFromUserIcon(fromUser.getIcon());
            }
            return vo;
        }).collect(Collectors.toList());

        return Result.ok(voList, page.getTotal());
    }

    @Override
    public Result getUnreadCount() {
        Long userId = UserHolder.getUser().getId();
        long count = query().eq("user_id", userId).eq("is_read", 0).count();
        return Result.ok(count);
    }

    @Override
    public Result markRead(Long id) {
        Long userId = UserHolder.getUser().getId();
        boolean ok = update()
                .set("is_read", 1)
                .eq("id", id)
                .eq("user_id", userId)
                .update();
        return ok ? Result.ok() : Result.fail("操作失败");
    }

    @Override
    public Result deleteNotice(Long id) {
        Long userId = UserHolder.getUser().getId();
        boolean ok = remove(
                query().getWrapper().eq("id", id).eq("user_id", userId)
        );
        return ok ? Result.ok() : Result.fail("操作失败");
    }
}
