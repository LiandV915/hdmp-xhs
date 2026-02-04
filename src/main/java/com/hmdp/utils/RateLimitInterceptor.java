package com.hmdp.utils;

import com.hmdp.dto.UserDTO;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.annotation
.Resource;
import jakarta.servlet

.http.HttpServletRequest;
import jakarta.servlet

.http.HttpServletResponse;

@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    @Resource
    private ZSetRateLimiter rateLimiter;

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) {

        UserDTO userDTO = UserHolder.getUser();
        String key;

        if (userDTO != null) {
            // 已登录用户：按 userId 限流
            key = "rate:limit:user:" + userDTO.getId();
        } else {
            // 未登录用户：按 IP 限流
            String ip = getClientIp(request);
            key = "rate:limit:ip:" + ip;
        }

        if (!rateLimiter.allowRequest(key, 100)) {
            response.setStatus(429);
            return false;
        }
        return true;
    }


    //获取ip，对游客限流获取 IP（注意反向代理）
    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty()) {
            ip = request.getRemoteAddr();
        }
        return ip;
    }
}

