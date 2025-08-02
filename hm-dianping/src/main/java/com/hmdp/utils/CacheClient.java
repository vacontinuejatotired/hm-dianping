package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.annotations.Select;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@Slf4j
@Component
public class CacheClient {
    private final StringRedisTemplate stringRedisTemplate;
    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }
    public void set(String key, Object value, Long time, TimeUnit timeUnit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, timeUnit);
    }
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit timeUnit) {
        RedisData redisData = new RedisData();
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(timeUnit.toSeconds(time)));
        redisData.setData(value);
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }
    public  <R,ID> R queryById(ID id , Class<R> clazz, String keyPrefix, Function<ID,R> dbFallBack,Long time, TimeUnit timeUnit) {
        String key= keyPrefix +id;
        String Json= stringRedisTemplate.opsForValue().get(key);
        if(StrUtil.isNotBlank(Json)){
            return JSONUtil.toBean(Json, clazz, false);
        }
        //判断命中的是不是空值
        if(Json!=null){
            return null;
        }
        R result= dbFallBack.apply(id);
        if(result==null){
            stringRedisTemplate.opsForValue().set(key,"",RedisConstants.CACHE_NULL_TTL,TimeUnit.MINUTES);
            return null;
        }
        this.set(key,result,time,timeUnit);
        return result;
    }
    private static final ExecutorService CACHE_REBUILD_THREAD_POOL = Executors.newFixedThreadPool(10);
    public <R,ID> R queryWithLogicExpire(String keyPrefix,ID id,Class<R> clazz,Function<ID,R> dbFallBack,Long time1, TimeUnit timeUnit) {
        String key= keyPrefix+id;
        String redisData= stringRedisTemplate.opsForValue().get(key);
        //查数据库，看是否为空值
        if (StrUtil.isBlank(redisData)) {
            R result = dbFallBack.apply(id);  // 查数据库
            if(result != null) {
                this.setWithLogicalExpire(key, result, time1, timeUnit);
            }
            return null;
        }
        String lockKey=RedisConstants.LOCK_SHOP_KEY+id;
        //拿到redisData
        RedisData redisData1= JSONUtil.toBean(redisData, RedisData.class);
        LocalDateTime time=LocalDateTime.now();
        //拿到数据
        R r=JSONUtil.toBean((JSONObject) redisData1.getData(), clazz);
        if(time.isBefore(redisData1.getExpireTime())){
            return r;
        }
        //拿锁
        boolean isLock=tryLock(lockKey);
        if(isLock){
            CACHE_REBUILD_THREAD_POOL.submit(()->{
                try {
                    R result=dbFallBack.apply(id);
             //设置逻辑过期时间
                    this.setWithLogicalExpire(key,result,time1,timeUnit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    unlock(lockKey);
                }
            });
        }
        return r;
    }
    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", RedisConstants.LOCK_SHOP_TTL, TimeUnit.SECONDS);
        // 拆箱要判空，防止NPE
        return BooleanUtil.isTrue(flag);
    }

    /**
     * 释放锁
     *
     * @param key
     */
    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }

}
