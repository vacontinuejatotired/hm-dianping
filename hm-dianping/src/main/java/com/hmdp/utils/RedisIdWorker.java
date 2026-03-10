package com.hmdp.utils;

import com.esotericsoftware.minlog.Log;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
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
    public long nextId(String keyPrefix){
        LocalDateTime now = LocalDateTime.now();
        long timeStamp=now.toEpochSecond(ZoneOffset.UTC)-BEGIN_TIME;
        String date= now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        Long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + date);
        return timeStamp<<COUNT_BITS | count ;
    }

    /**
     * 本方法只可在设置上下文的线程上使用
     * @return
     */
    public Long nextVersion(){
        return nextVersion(UserHolder.getUserId());
    }

    /**
     * 通用方法，未设置上下文也可使用
     * @param userId
     * @return
     */
    public Long nextVersion(Long userId){
        if(userId==null){
            Log.info("current userId is null");
            return -1L;
        }
        String key = RedisConstants.CURRENT_TOKEN_VERSION_KEY +userId;
        Long count = stringRedisTemplate.opsForValue().increment(key);
// 如果是第一次increment（返回1），说明key是新建的，设置过期时间
        if (count == 1) {
            stringRedisTemplate.expire(key, 8, TimeUnit.DAYS);
            log.debug("Set initial expire for new version key: {}", key);
        } else {
            log.debug("Version key already exists (value={}), expire will be handled in Lua", count);
        }
        return count;
    }
}
