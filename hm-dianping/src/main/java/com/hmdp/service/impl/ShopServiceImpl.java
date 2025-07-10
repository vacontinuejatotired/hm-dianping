package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import io.netty.util.internal.StringUtil;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
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

        @Override
    public Result queryById(Long id) {
        String key= RedisConstants.CACHE_SHOP_KEY +id;
        String shopJson= stringRedisTemplate.opsForValue().get(key);
        if(!StringUtil.isNullOrEmpty(shopJson)){
            Shop bean = JSONUtil.toBean(shopJson, Shop.class, false);
            return Result.ok(bean);
        }
        Shop shop=getById(id);
        if(shop==null){
            return Result.fail("店铺不存在");
        }
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop));
        stringRedisTemplate.expire(key,30, TimeUnit.MINUTES);
        return Result.ok(shop);
    }
}
