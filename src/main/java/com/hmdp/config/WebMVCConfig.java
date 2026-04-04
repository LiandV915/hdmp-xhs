package com.hmdp.config;

import com.hmdp.utils.LoginInterceptor;
import com.hmdp.utils.RateLimitInterceptor;
import com.hmdp.utils.RefreshTokenInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import jakarta.annotation.Resource;

@Configuration
public class WebMVCConfig implements WebMvcConfigurer {
    @Resource
    private LoginInterceptor loginInterceptor;

    @Resource
    private RefreshTokenInterceptor refreshTokenInterceptor;

    @Resource
    private RateLimitInterceptor rateLimitInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {


        // 0️⃣ 限流（最先）
        registry.addInterceptor(rateLimitInterceptor)
                .addPathPatterns("/**")
                .order(-1);/*

        限流是「系统保护」， 应该在任何业务、任何鉴权之前就拦掉恶意请求*/


        // 1. 刷新 token 拦截器（必须先执行）
        registry.addInterceptor(refreshTokenInterceptor)
                .addPathPatterns("/**")
                .order(0);


        // 2. 登录拦截器（后执行）
        registry.addInterceptor(loginInterceptor)
                .excludePathPatterns(
                        "/user/code",
                        "/user/login",
                        "/user/search",
                        "/blog/hot",
                        "/blog/search",
                        "/blog/recommend",
                        "/shop/**",
                        "/shop-type/**",
                        "/upload/**",
                        "/voucher/**"
                )
                .order(1);
    }
}
