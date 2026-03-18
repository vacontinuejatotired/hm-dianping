package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.service.ITestService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

@Service
public class TestServiceimpl implements ITestService {
    @Resource
    private IVoucherOrderService voucherOrderService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;


    @Override
    public Result restart(Long num,Long voucherId) {
        stringRedisTemplate.delete(RedisConstants.SECKILL_ORDERIFNO_KEY+voucherId);
        stringRedisTemplate.delete(RedisConstants.SECKILL_ORDER_EXIST_USER_ZSET_KEY+voucherId);
        stringRedisTemplate.opsForValue().set(RedisConstants.SECKILL_STOCK_KEY+voucherId,String.valueOf(num));
        voucherOrderService.deleteVoucherOrders(voucherId,num);
        return Result.ok("删除成功");
    }
}
