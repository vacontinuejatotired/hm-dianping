package com.hmdp.config;

import com.hmdp.interceptor.RefreshTokenInterceptor;
import com.hmdp.interceptor.loginInterceptor;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisIdWorker;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.annotation.Resource;

@Configuration
public class MvcConfig implements WebMvcConfigurer {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IUserService userService;

    @Resource
    private RedisIdWorker redisIdWorker;
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
        registry.addInterceptor(new RefreshTokenInterceptor(stringRedisTemplate,userService,redisIdWorker)).addPathPatterns("/**").order(0);
    }
}
