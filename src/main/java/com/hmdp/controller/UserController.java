package com.hmdp.controller;


import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UpdateUserDTO;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.UserInfo;
import com.hmdp.service.IUserInfoService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpSession;

/**
 * <p>
 * 前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@RestController
@RequestMapping("/user")
public class UserController {

    @Resource
    private IUserService userService;

    @Resource
    private IUserInfoService userInfoService;

    /** 发送手机验证码 */
    @PostMapping("code")
    public Result sendCode(@RequestParam("phone") String phone, HttpSession session) {
        return userService.sendCode(phone, session);
    }

    /** 登录 */
    @PostMapping("/login")
    public Result login(@RequestBody LoginFormDTO loginForm, HttpSession session) {
        return userService.login(loginForm, session);
    }

    /** 登出（需在请求头携带 token） */
    @PostMapping("/logout")
    public Result logout(@RequestHeader(value = "authorization", required = false) String token) {
        return userService.logout(token);
    }

    /** 当前登录用户信息 */
    @GetMapping("/me")
    public Result me() {
        UserDTO user = UserHolder.getUser();
        return Result.ok(user);
    }

    /** 查看用户主页（含博客数、粉丝数、关注数） */
    @GetMapping("/profile/{id}")
    public Result profile(@PathVariable("id") Long userId) {
        return userService.getUserProfile(userId);
    }

    /** 修改当前用户信息（昵称、头像、简介等） */
    @PutMapping("/info")
    public Result updateInfo(@RequestBody UpdateUserDTO dto) {
        return userService.updateUserInfo(dto);
    }

    /** 查询用户扩展详情（原接口保留兼容） */
    @GetMapping("/info/{id}")
    public Result info(@PathVariable("id") Long userId) {
        UserInfo info = userInfoService.getById(userId);
        if (info == null) {
            return Result.ok();
        }
        info.setCreateTime(null);
        info.setUpdateTime(null);
        return Result.ok(info);
    }

    /** 按昵称关键词搜索用户（分页） */
    @GetMapping("/search")
    public Result searchUsers(
            @RequestParam("keyword") String keyword,
            @RequestParam(value = "current", defaultValue = "1") Integer current) {
        return userService.searchUsers(keyword, current);
    }
}
