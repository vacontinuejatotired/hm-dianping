package com.hmdp.config;

import com.hmdp.interceptor.RefreshTokenInterceptor;
import com.hmdp.interceptor.loginInterceptor;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisIdWorker;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.annotation.Resource;

@Configuration
public class MvcConfig implements WebMvcConfigurer {
    @Resource
    private RefreshTokenInterceptor refreshTokenInterceptor;
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new loginInterceptor())
                .excludePathPatterns("/user/login",
                "/shop/**",
                        "/voucher/**",
                        "/blog/hot",
                        "/upload",
                        "/user/code",
                        "/shop-type/**").order(1);
        registry.addInterceptor(refreshTokenInterceptor).addPathPatterns("/**").excludePathPatterns("/blog/hot","/user/login","/user/code","/shop-type/list").order(0);
    }
}
