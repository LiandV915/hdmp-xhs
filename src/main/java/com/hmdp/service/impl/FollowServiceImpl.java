package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private RedisTemplate<Object, Object> redisTemplate;
    @Autowired
    private IUserService userService;
    @Override
    public Result follow(Long userId, boolean flag) {
        Long thisUserId = UserHolder.getUser().getId();
        if(flag) {
            Follow follow = new Follow();
            follow.setUserId(thisUserId);
            follow.setFollowUserId(userId);
            boolean success = save(follow);
            if (success) {
                String key = "follow:" + thisUserId;
                redisTemplate.opsForSet().add(key, userId.toString());
            }
        }
        else{
            boolean success=remove(query().getWrapper().eq("user_id",userId).eq("follow_user_id",thisUserId));
            if (success) {
                String key = "follow:" + thisUserId;
                redisTemplate.opsForSet().remove(key, userId.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result getfollow(Long userId) {
        Long thisUserId = UserHolder.getUser().getId();
        QueryWrapper<Follow> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("user_id", thisUserId).eq("follow_user_id", userId);
        Follow follow = this.getOne(queryWrapper, false);
        return Result.ok(follow != null);
    }

    @Override
    public Result getCommonFollow(Long userId) {
        Long thisUserId = UserHolder.getUser().getId();
        String key1 = "follow:" + thisUserId;
        String key2 = "follow:" + userId;
        Set<String> commonSet=stringRedisTemplate.opsForSet().intersect(key1,key2);
        if(commonSet==null){
            return Result.ok(Collections.emptyList());
        }
        List<Long> ids= commonSet.stream().map(Long::valueOf).toList();
        List<UserDTO> list1 = userService.listByIds(ids).stream().
                map(user ->
                {
                    UserDTO dto = new UserDTO();
                    BeanUtils.copyProperties(user, dto);
                    return dto;
                })
                .toList();
        return Result.ok(list1);
    }


}
