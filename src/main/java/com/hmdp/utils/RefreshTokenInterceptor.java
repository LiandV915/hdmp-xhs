package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.http.HttpStatus;
import com.hmdp.dto.UserDTO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
public class RefreshTokenInterceptor implements HandlerInterceptor {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //从请求头获取token
        String token=request.getHeader("authorization");
        if(token==null){
            return true;
        }
        String key=RedisConstants.LOGIN_USER_KEY+token;
        //从redis查这个token对应的用户数据
        Map<Object,Object> usermap=stringRedisTemplate.opsForHash().entries(key);
        if(usermap==null){//用户没登录，本层可以放过去，交给登录拦截器处理
            return true;
        }
        //封装为userDTO，存入threadLocal`
        UserDTO userDTO= BeanUtil.fillBeanWithMap(usermap,new UserDTO(),false);
        // 用户存在，则将用户信息保存到ThreadLocal中，方便后续逻辑处理
        // 比如：方便获取和使用用户信息，session获取用户信息是具有侵入性的
        UserHolder.saveUser(userDTO);

        //刷新redis有效期
        stringRedisTemplate.expire(key,30, TimeUnit.MINUTES);
        return true;
    }
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        UserHolder.removeUser();
    }
}
