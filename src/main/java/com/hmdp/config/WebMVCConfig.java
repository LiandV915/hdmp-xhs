package com.hmdp.config;

import com.hmdp.utils.LoginInterceptor;
import com.hmdp.utils.RateLimitInterceptor;
import com.hmdp.utils.RefreshTokenInterceptor;
import com.hmdp.utils.ZSetRateLimiter;
import org.intellij.lang.annotations.JdkConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.annotation.Resource;

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
                .order(-1);

/*        限流是「系统保护」，
        应该在任何业务、任何鉴权之前就拦掉恶意请求*/


        // 1. 刷新 token 拦截器（必须先执行）
        registry.addInterceptor(refreshTokenInterceptor)
                .addPathPatterns("/**")
                .order(0);


        // 2. 登录拦截器（后执行）
        registry.addInterceptor(loginInterceptor)
                .excludePathPatterns(
                        "/user/code",
                        "/user/login",
                        "/blog/hot",
                        "/shop/**",
                        "/shop-type/**",
                        "/upload/**",
                        "/voucher/**",
                        "/blog/recommend"
                )
                .order(1);
    }
}
