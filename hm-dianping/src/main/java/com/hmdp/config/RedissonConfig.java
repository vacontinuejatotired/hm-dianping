package com.hmdp.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedissonConfig {

    @Bean
    public RedissonClient redissonClient() {
        Config config = new Config();
        config.useSingleServer().setAddress("redis://192.168.49.130:6379").setPassword("281458");
        return Redisson.create(config);
    }
//    @Bean
//    public RedissonClient redissonClient2() {
//        Config config = new Config();
//        config.useSingleServer().setAddress("redis://192.168.49.130:6381").setPassword("281458");
//        return Redisson.create(config);
//    }
//    @Bean
//    public RedissonClient redissonClient3() {
//        Config config = new Config();
//        config.useSingleServer().setAddress("redis://192.168.49.130:6382").setPassword("281458");
//        return Redisson.create(config);
//   }
}
