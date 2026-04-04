package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.Result;
import com.hmdp.entity.Follow;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IFollowService extends IService<Follow> {

    Result follow(Long userId, boolean flag);

    Result getfollow(Long userId);

    Result getCommonFollow(Long userId);

    /** 查看我的关注列表 */
    Result getFollowList(Long userId);

    /** 查看某用户的粉丝列表 */
    Result getFansList(Long userId);
}
