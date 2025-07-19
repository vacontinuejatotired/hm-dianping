package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdWorker {
    private static final Long  BEGIN_TIME=1735689600L;
    private StringRedisTemplate stringRedisTemplate;
    /**
     * 序列号位数
     */
    private static final int COUNT_BITS=32;
    public RedisIdWorker(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }
    public long nexId(String keyPrefix){
        LocalDateTime now = LocalDateTime.now();
        long timeStamp=now.toEpochSecond(ZoneOffset.UTC)-BEGIN_TIME;
        String date= now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        Long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + date);
        return timeStamp<<COUNT_BITS | count ;
    }
}
