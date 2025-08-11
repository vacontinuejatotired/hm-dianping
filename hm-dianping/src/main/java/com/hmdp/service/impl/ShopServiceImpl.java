package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.SystemConstants;
import io.netty.util.internal.StringUtil;
import org.apache.tomcat.util.buf.StringUtils;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;
        @Override
    public Result queryById(Long id) {
        //互斥锁实现
            //Shop shop = queryWithLock(id);
            Shop shop = cacheClient.queryWithLogicExpire(RedisConstants.CACHE_SHOP_KEY,id,Shop.class,this::getById,20L,TimeUnit.SECONDS);
            if (shop == null) {
                return Result.fail("店铺不存在");
            }
            return Result.ok(shop);
    }
    private Shop queryWithLock(Long id) {
        String key= RedisConstants.CACHE_SHOP_KEY +id;
        String shopJson= stringRedisTemplate.opsForValue().get(key);

        if(StrUtil.isNotBlank(shopJson)){
            return JSONUtil.toBean(shopJson, Shop.class, false);
        }
        if(shopJson!=null){
            return null;
        }
        String lockKey=RedisConstants.LOCK_SHOP_KEY+id;
        Shop shop= null;
        try {
            boolean isLock=tryLock(lockKey);
            if(!isLock){
                Thread.sleep(50);
                return queryWithLock(id);
            }
            shop = getById(id);
            //模拟重建的延时
            Thread.sleep(200);
            if(shop==null){
                //空值写入redis
                stringRedisTemplate.opsForValue().set(key,"",RedisConstants.CACHE_NULL_TTL,TimeUnit.MINUTES);
                return null;
            }

            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop),RedisConstants.CACHE_SHOP_TTL,TimeUnit.MINUTES);

        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            unlock(lockKey);
        }
        return shop;
    }
    private static final ExecutorService CACHE_REBUILD_THREAD_POOL = Executors.newFixedThreadPool(10);
//    private Shop queryShopWithLogicExpire(Long id){
//        String key= RedisConstants.CACHE_SHOP_KEY +id;
//        String redisData= stringRedisTemplate.opsForValue().get(key);
//
//        if (StrUtil.isBlank(redisData)) {
//            return null;
//        }
//            String lockKey=RedisConstants.LOCK_SHOP_KEY+id;
//            RedisData redisData1= JSONUtil.toBean(redisData, RedisData.class);
//            LocalDateTime time=LocalDateTime.now();
//            Shop shop=JSONUtil.toBean((JSONObject) redisData1.getData(), Shop.class);
//            if(time.isBefore(redisData1.getExpireTime())){
//                return shop;
//            }
//            boolean isLock=tryLock(lockKey);
//            if(isLock){
//    CACHE_REBUILD_THREAD_POOL.submit(()->{
//        try {
//            this.saveShopRedis(id,1800L);
//        } catch (Exception e) {
//            throw new RuntimeException(e);
//        } finally {
//            unlock(lockKey);
//        }
//    });
//        }
//        return shop;
//    }
    private Shop queryShopById(Long id){
        String key= RedisConstants.CACHE_SHOP_KEY +id;
        String shopJson= stringRedisTemplate.opsForValue().get(key);
        if(StrUtil.isNotBlank(shopJson)){
            Shop bean = JSONUtil.toBean(shopJson, Shop.class, false);
            return bean;
        }
        //判断命中的是不是空值
        if(shopJson!=null){
            return null;
        }
        Shop shop=getById(id);
        if(shop==null){
            stringRedisTemplate.opsForValue().set(key,"",RedisConstants.CACHE_NULL_TTL,TimeUnit.MINUTES);
            return null;
        }
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop),RedisConstants.CACHE_SHOP_TTL,TimeUnit.MINUTES);
        return shop;
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
//    private boolean tryLock(String key){
//        Boolean flag=stringRedisTemplate.opsForValue().setIfAbsent(key,"1",RedisConstants.LOCK_SHOP_TTL,TimeUnit.MINUTES);
//        return BooleanUtil.isTrue(flag);
//    }
//    private void unlock(String key){
//            stringRedisTemplate.delete(key);
//    }
    @Override
    @Transactional
    public Result updateShop(Shop shop) {
        Long id = shop.getId();
        if(id==null){
            return Result.fail("店铺id不存在");
        }
        updateById(shop);
        stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY+id);
        return Result.ok();
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        if (x==null || y==null){
                    Page<Shop> page = query()
                .eq("type_id", typeId)
                .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            return Result.ok(page);
        }
        int start = (current-1)*SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current* SystemConstants.DEFAULT_PAGE_SIZE;
        String key ="shop:geo:"+typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo()
                .search(key, GeoReference.fromCoordinate(x, y),
                        new Distance(5000),
                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end));
        if (results==null){
            return Result.ok(Collections.emptyList());
        }

        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> content = results.getContent();
        if ( content.size()<start){
            return Result.ok(Collections.emptyList());
        }
        List<Long >ids=new ArrayList<>(content.size());
        Map<Long,Distance>map=new HashMap<>(content.size());
        content.stream().skip(start)
                .forEach(item->{
                    String shopId=item.getContent().getName();
                    Distance distance = item.getDistance();
                    ids.add(Long.parseLong(shopId));
                    map.put(Long.parseLong(shopId),distance);
                });
        String idStr = StrUtil.join(",",ids);
        List<Shop> shops = query().in("id", ids).last("order by field (id," + idStr + ")").list();
        shops.forEach(item->{
            item.setDistance(map.get(item.getId()).getValue());
        });
        return Result.ok(shops);

    }

    /**
     * 设置逻辑过期时间,为热点key设置
     * @param id
     * @param expireSeconds
     */
    public void saveShopRedis(Long id, Long expireSeconds) {
        Shop shop = getById(id);
        try {
            //线程休眠不要太长了，不然数据不明显
            Thread.sleep(20);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        RedisData redisData=new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(redisData));
    }
}
