package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.entity.User;

import jakarta.servlet

.http.HttpSession;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IUserService extends IService<User> {

    Result sendCode(String phone, HttpSession session);

    Result login(LoginFormDTO loginForm, HttpSession session);

    /** 登出（删除 Redis Token） */
    Result logout(String token);

    /** 更新当前用户信息（昵称、头像、简介等） */
    Result updateUserInfo(com.hmdp.dto.UpdateUserDTO dto);

    /** 查看用户主页（含统计数据） */
    Result getUserProfile(Long userId);

    /**
     * 按昵称关键词搜索用户（分页）
     * @param keyword 昵称关键词
     * @param current 页码
     */
    Result searchUsers(String keyword, Integer current);
}
