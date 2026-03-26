package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.service.ITestService;
import com.hmdp.service.IUserService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisIdWorker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.ReturnType;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

@Service
@Slf4j
public class TestServiceimpl implements ITestService {
    @Resource
    private IVoucherOrderService voucherOrderService;

    @Resource
    private IUserService userService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;


    @Override
    public Result restart(Long num,Long voucherId) {
        stringRedisTemplate.delete(RedisConstants.SECKILL_ORDERIFNO_KEY+voucherId);
        stringRedisTemplate.delete(RedisConstants.SECKILL_ORDER_EXIST_USER_ZSET_KEY+voucherId);
        stringRedisTemplate.opsForValue().set(RedisConstants.SECKILL_STOCK_KEY+voucherId,String.valueOf(num));
        Long l = deleteByPattern(RedisConstants.SECKILL_ORDERIFNO_KEY + "*");
        System.out.println("删除 "+l +" 条");
        voucherOrderService.deleteVoucherOrders(voucherId,num);
        return Result.ok("删除成功");
    }

    public Long deleteByPattern(String pattern) {
        // 返回删除了多少个key
        String luaScript =
                "local keys = redis.call('keys', KEYS[1]) " +
                        "local count = 0 " +
                        "for _, key in ipairs(keys) do " +
                        "    redis.call('del', key) " +
                        "    count = count + 1 " +
                        "end " +
                        "return count";  // 返回删除数量

        return stringRedisTemplate.execute(
                (RedisCallback<Long>) connection ->
                        (Long) connection.eval(luaScript.getBytes(),
                                ReturnType.INTEGER,
                                1,
                                pattern.getBytes())
        );
    }
    @Override
    public Result generateTestToken(Long num,String fileName) {
        userService.exportTokenAndRefreshTokenToCsv(Math.toIntExact(num),fileName);
        return Result.ok("success");
    }

    @Override
    public Result checkSnowFlake(int num) {
        redisIdWorker.showSnowflakeIdQueueInfo(num);
        log.info("取出的ID数量: {}", num);
        return Result.ok();
    }
}
