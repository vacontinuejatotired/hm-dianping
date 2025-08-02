package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IVoucherService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Resource
    private VoucherOrderMapper voucherOrderMapper;
    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;
    private static final String KEY_PREFIX = "order:";
    private static final DefaultRedisScript<Long> REDIS_UNLOCK_SCRIPT;
    private IVoucherOrderService proxy;

    static {
        REDIS_UNLOCK_SCRIPT = new DefaultRedisScript<>();
        REDIS_UNLOCK_SCRIPT.setResultType(Long.class);
        REDIS_UNLOCK_SCRIPT.setLocation(new ClassPathResource("Seckill.lua"));
    }

    //TODO这里怎么用的是单线程池
    private static final ExecutorService secKillThreadPool = Executors.newSingleThreadExecutor();

    @PostConstruct
    public void init() {
        log.info("异步秒杀线程池启动");
        log.info("初始化代理对象{}",AopContext.currentProxy());
        this.proxy= (IVoucherOrderService) AopContext.currentProxy();
        log.info("线程池状态: shutdown={}, terminated={}",
                secKillThreadPool.isShutdown(),
                secKillThreadPool.isTerminated());
        secKillThreadPool.submit(new VoucherOrderHandler());
    }

    private class VoucherOrderHandler implements Runnable {
        private final String queueName="stream.orders";
        @Override
        public void run() {
            while (true) {
                try {
                    //从阻塞队列拿订单处理
                    List<MapRecord<String, Object, Object>> read = stringRedisTemplate.opsForStream().read(Consumer.from("g1", "c1")
                            , StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2))
                            , StreamOffset.create(queueName, ReadOffset.lastConsumed()));
                    if (read == null || read.isEmpty()) {
                        continue;
                    }
                    MapRecord<String, Object, Object> redcord = read.get(0);
                    Map<Object, Object> values = redcord.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    handleVoucherOrder(voucherOrder);
                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",redcord.getId());
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                    handlePendingList();
                }
            }
        }

        private void handlePendingList() {
            while (true) {
                try {
                    List<MapRecord<String, Object, Object>> pendingList = stringRedisTemplate.opsForStream().read(Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(queueName, ReadOffset.from("0")));
                    if (pendingList == null || pendingList.isEmpty()) {
                        //return;
                        //这里return和break有什么区别
                        break;
                    }
                    MapRecord<String, Object, Object> redcord = pendingList.get(0);
                    Map<Object, Object> values = redcord.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    handleVoucherOrder(voucherOrder);
                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",redcord.getId());
                } catch (Exception e) {
                    log.info("pendingList订单异常",e);
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException ex) {
                        throw new RuntimeException(ex);
                    }
                }
            }
        }
//        private BlockingQueue<VoucherOrder> orderBlockingQueue = new ArrayBlockingQueue<>(1024 * 1024);
//        @Override
//        public void run() {
//            while (true) {
//                try {
//                    //从阻塞队列拿订单处理
//                    VoucherOrder voucherOrder = orderBlockingQueue.take();
//                    handleVoucherOrder(voucherOrder);
//                } catch (InterruptedException e) {
//                    log.error("订单异常", e);
//                }
//            }
//        }
    }

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        RLock simpleRedisLock = redissonClient.getLock(KEY_PREFIX + userId);
        boolean isLock = simpleRedisLock.tryLock();
        /**
         * 能触发失败也就说明同一用户有多个线程
         */
        if (!isLock) {
            log.error("不允许重复下单");
            return;
        }
        //synchronized (userId.toString().intern()) {
        try {
            //proxy = (IVoucherOrderService) AopContext.currentProxy();
            proxy.createVoucherOrder(voucherOrder);
        } catch (IllegalStateException e) {
            throw new RuntimeException(e);
        } finally {
            simpleRedisLock.unlock();
        }
    }

    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        int count = query().eq("user_id", voucherOrder.getUserId()).eq("voucher_id", voucherOrder.getVoucherId()).count();
        if (count > 0) {
            log.error("该用户已经购买过");
            return;
        }
        boolean success = seckillVoucherService.update().setSql("stock = stock -1")
                .eq("voucher_id", voucherOrder.getVoucherId())
                //.eq("stock", byId.getStock())
                .gt("stock", 0)
                .update();
        if (!success) {
            log.error("库存不足");
            return;
        }
        save(voucherOrder);
        log.info("订单{}插入mysql", voucherOrder.getId());
    }

    public Result querySeckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        Long orderId = redisIdWorker.nexId("order");
        Long result = stringRedisTemplate.execute(REDIS_UNLOCK_SCRIPT, Collections.emptyList(), voucherId.toString(), userId.toString(),orderId.toString());
        int r = result.intValue();
        //0代表才加入缓存，1代表库存不足，2代表重复下单
        if (r != 0) {
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }
        //警示！！代理对象要变成全局使用的
        //proxy = (IVoucherOrderService) AopContext.currentProxy();
        //返回订单号
        return Result.ok(orderId);
    }
//    public Result querySeckillVoucher(Long voucherId) {
//        Long userId = UserHolder.getUser().getId();
//        Long result = stringRedisTemplate.execute(REDIS_UNLOCK_SCRIPT, Collections.emptyList(), voucherId.toString(), userId.toString());
//        int r = result.intValue();
//        //0代表才加入缓存，1代表库存不足，2代表重复下单
//        if (r != 0) {
//            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
//        }
//        Long orderId = redisIdWorker.nexId("order");
//        VoucherOrder voucherOrder = new VoucherOrder();
//        voucherOrder.setVoucherId(voucherId);
//        voucherOrder.setId(orderId);
//        voucherOrder.setUserId(UserHolder.getUser().getId());
//        //插入数据库
//        //放入阻塞队列
//        try {
//            orderBlockingQueue.add(voucherOrder);
//        } catch (Exception e) {
//            log.info("放入订单失败");
//        }
//        //警示！！代理对象要变成全局使用的
//        proxy = (IVoucherOrderService) AopContext.currentProxy();
//        //返回订单号
//        return Result.ok(orderId);
//    }
//    /**
//     * jmeter测试的时候选内容编码一定不要加空格，不然会识别失败
//     * @param voucherId
//     * @return
//     */
//    @Override
//
//    public Result querySeckillVoucher(Long voucherId) {
//        Long userId=UserHolder.getUser().getId();
//        SeckillVoucher byId = seckillVoucherService.getById(voucherId);
//        if(byId.getBeginTime().isAfter(LocalDateTime.now())){
//            return Result.fail("活动未开始");
//        }
//        if(byId.getEndTime().isBefore(LocalDateTime.now())){
//            return Result.fail("活动已结束");
//        }
//        if(byId.getStock() < 1){
//            return Result.fail("库存不足");
//        }
//        /**
//         * 注意事务失效，这里采用找代理解决，上网查一查吧
//         */
//
//        /**
//         * 两个项目启动的话会有两台不同的jvm机，因而锁监视器不共享
//         * 这里采用分布式锁来处理
//         */
////        SimpleRedisLock simpleRedisLock = new SimpleRedisLock( stringRedisTemplate,KEY_PREFIX + userId);
//        RLock simpleRedisLock = redissonClient.getLock(KEY_PREFIX + userId);
//        boolean isLock = simpleRedisLock.tryLock();
//        /**
//         * 能触发失败也就说明同一用户有多个线程
//         */
//        if(!isLock){
//            return Result.fail("不允许重复下单");
//        }
//        //synchronized (userId.toString().intern()) {
//        try {
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId);
//        } catch (IllegalStateException e) {
//            throw new RuntimeException(e);
//        } finally {
//            simpleRedisLock.unlock();
//        }
//        //}
//    }

}
