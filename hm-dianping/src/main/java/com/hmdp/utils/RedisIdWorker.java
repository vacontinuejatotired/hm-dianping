package com.hmdp.utils;

import com.esotericsoftware.minlog.Log;
import com.hmdp.entity.SnowflakeIdQueue;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@Slf4j
public class RedisIdWorker {
    private static final Long BEGIN_TIME = 1735689600L;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private static final SnowflakeIdQueue SNOW_FLAKE_ID_QUEUE = new SnowflakeIdQueue();
    /**
     * 序列号位数
     */
    private static final int COUNT_BITS = 32;

    @PostConstruct
    public void init() throws InterruptedException {
        log.info("正在初始化ID生成器...");
        batchGenerateId();
    }

    /**
     * 根据订单Id使用情况动态获取新Id，避免一次性生成过多Id导致内存占用过高
     */
    public void batchGenerateId() throws InterruptedException {
        log.info("准备批量生成ID");
        int targetSize = SNOW_FLAKE_ID_QUEUE.getInitCapacity() - SNOW_FLAKE_ID_QUEUE.size();
        stringRedisTemplate.executePipelined((RedisCallback<?>) (connection) -> {
            for (int i = 0; i < targetSize; i++) {
                LocalDateTime now = LocalDateTime.now();
                Long timeStamp = now.toEpochSecond(ZoneOffset.UTC) - BEGIN_TIME;
                Long incr = connection.incr(("icr:order:" + now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd")).getBytes()).getBytes());
                try {
                    SNOW_FLAKE_ID_QUEUE.put(timeStamp << COUNT_BITS | incr);
                } catch (InterruptedException e) {
                    log.info("批量生成ID时线程被中断", e);
                    throw new RuntimeException(e);
                }
            }
            log.info("批量生成ID完成，当前队列大小: {}", SNOW_FLAKE_ID_QUEUE.size());
            return null;
        });
    }

    public Long getIdFromQueue() throws InterruptedException {
        Long id = SNOW_FLAKE_ID_QUEUE.take();
        if (id == -1L) {
            log.info("ID队列需要刷新，正在批量生成新ID...");
            try {
                batchGenerateId();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            } finally {
                SNOW_FLAKE_ID_QUEUE.finishRefresh();
            }
            id = SNOW_FLAKE_ID_QUEUE.take();
        }
        return id;
    }

    public RedisIdWorker(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public long nextId(String keyPrefix) {
        LocalDateTime now = LocalDateTime.now();
        long timeStamp = now.toEpochSecond(ZoneOffset.UTC) - BEGIN_TIME;
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        Long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + date);
        return timeStamp << COUNT_BITS | count;
    }


    /**
     * 本方法只可在ThreadLocal置上下文的线程上使用
     *
     * @return
     */
    public Long nextVersion() {
        return nextVersion(UserHolder.getUserId());
    }

    /**
     * 通用方法，ThreadLocal未设置上下文也可使用
     *
     * @param userId
     * @return
     */
    public Long nextVersion(Long userId) {
        if (userId == null) {
            Log.info("current userId is null");
            return -1L;
        }
        String key = RedisConstants.CURRENT_TOKEN_VERSION_KEY + userId;
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
