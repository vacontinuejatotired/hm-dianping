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
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IUserService userService;

    @Resource(name = "refreshDeadTokenScript")
    private DefaultRedisScript<Long> refreshDeadTokenScript;

    @Resource(name = "refreshDeadlineTokenScript")
    private DefaultRedisScript<Long> refreshDeadlineTokenScript;

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
        registry.addInterceptor(new RefreshTokenInterceptor(stringRedisTemplate,userService,redisIdWorker,refreshDeadTokenScript,refreshDeadlineTokenScript)).addPathPatterns("/**").order(0);
    }
}
