package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.service.IFollowService;
import com.hmdp.service.impl.FollowServiceImpl;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation
.Resource;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/follow")
public class FollowController {

    /**
     *关注或取关
     */
    @Resource
    IFollowService followService;


    @PutMapping("{userId}/{flag}")
    public Result follow(@PathVariable Long userId, @PathVariable Boolean flag) {
        return followService.follow(userId,flag);
    }

    /**
     *
     */
    @GetMapping("or/not/{userId}")
    public Result getFollow(@PathVariable Long userId){
        return followService.getfollow(userId);
    }

    @GetMapping("common/{userId}")
    public Result getCommonFollow(@PathVariable Long userId) {
        return followService.getCommonFollow(userId);
    }

    /** 查看某用户的关注列表 */
    @GetMapping("/list/{userId}")
    public Result getFollowList(@PathVariable Long userId) {
        return followService.getFollowList(userId);
    }

    /** 查看某用户的粉丝列表 */
    @GetMapping("/fans/{userId}")
    public Result getFansList(@PathVariable Long userId) {
        return followService.getFansList(userId);
    }
}
