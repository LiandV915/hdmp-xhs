package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation
.Resource;
import jakarta.servlet

.http.HttpSession;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {


    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 发送验证码
     * @param phone
     * @param session
     * @return
     */
    @Override
    public Result sendCode(String phone, HttpSession session) {
        // 1、判断手机号是否合法
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式不正确");
        }
        // 2、手机号合法，生成验证码，并保存到redis中,有效期30min
        String code = "123456";
        stringRedisTemplate.opsForValue().set(RedisConstants.LOGIN_CODE_KEY + phone,
                code,
                RedisConstants.LOGIN_CODE_TTL,
                TimeUnit.MINUTES);
        // 3、发送验证码
        log.info("验证码:{}", code);
        return Result.ok();
    }


    /**
     * 验证码登录
     * @param loginForm
     * @param session
     * @return
     */
    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        User user = new User();
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式不正确");
        }
        String code = loginForm.getCode();
        //从redis中比对验证码
        String cachecode=stringRedisTemplate.opsForValue().get(RedisConstants.LOGIN_CODE_KEY + phone);
        if(code==null||!code.equals(cachecode)){
            return Result.fail("验证码错误");
        }
        user=query().eq("phone",phone).one();
        if(user==null){//查不到，则还没有这个用户
            user=createUserWithPhone(phone);
            initNewUserRedis(user.getId());//注册用户的时候初始化一系列redis表
        }
        UserDTO userDTO= BeanUtil.copyProperties(user,UserDTO.class);
        //将userDTO转为map类型，从而封装入redis
        Map<String,Object> map=BeanUtil.beanToMap(userDTO,new HashMap<>(),
                CopyOptions.create().setIgnoreNullValue(true).
                        setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));
        String token= UUID.randomUUID().toString();//生成随机令牌作为key
        String tokenname=RedisConstants.LOGIN_USER_KEY + token;
        //将用户的基本信息存入redis
        stringRedisTemplate.opsForHash().putAll(tokenname, map);

        UserHolder.saveUser(userDTO);

        stringRedisTemplate.expire(tokenname,30, TimeUnit.MINUTES);
        return Result.ok(token);
    }

    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(SystemConstants.USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        this.save(user);
        return user;
    }
    /**
     * 新用户注册后的推荐系统初始化
     * 采用 Redis Key 懒加载策略，首次行为自动创建
     */
    public void initNewUserRedis(Long userId) {
        // 无需写入任何空集合
    }

}


