package com.hmdp.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;

@Configuration
@Slf4j
public class RedisLuaConfig {

    /**
     * 通用Lua脚本创建方法
     */
    private <T> DefaultRedisScript<T> createScript(String path, Class<T> resultType) {
        DefaultRedisScript<T> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(path));
        script.setResultType(resultType);
        log.info("Lua脚本 [{}] 创建成功, 返回类型: {}", path, resultType.getSimpleName());
        return script;
    }

    @Bean(name = "seckillScript")
    public DefaultRedisScript<Long> seckillScript() {
        return createScript("MqSeckill.lua", Long.class);
    }

    @Bean(name = "refreshDeadlineTokenScript")
    public DefaultRedisScript<Long> refreshTokenScript() {
        return createScript("RefreshToken.lua", Long.class);
    }

    @Bean(name = "refreshDeadTokenScript")
    public DefaultRedisScript<Long> refreshTokenScript2() {
        return createScript("RefreshRefreshToken.lua", Long.class);
    }
}