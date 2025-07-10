package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import io.netty.util.internal.StringUtil;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
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
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result queryList() {
    //TODO 以后用zSet改一下
        String key= RedisConstants.CACHE_SHOPTYPE_KEY;
        String value = stringRedisTemplate.opsForValue().get(key);
        //先查缓存看是否存在
        if(!StringUtil.isNullOrEmpty(value)){
            List<ShopType> list = JSONUtil.toList(value, ShopType.class);
    return Result.ok(list);
        }//不存在则查数据然后插入缓存中
        List<ShopType>list=query().list();
        if (list==null||list.size()==0){
            return Result.fail("商店种类加载错误");
        }
        for (ShopType shopType:list){
            stringRedisTemplate.opsForList().rightPush(RedisConstants.CACHE_SHOPTYPE_KEY,JSONUtil.toJsonStr(shopType));
        }
        return Result.ok(list);
    }
}
