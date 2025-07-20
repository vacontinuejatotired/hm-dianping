package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.springframework.aop.framework.AopContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Resource
    private VoucherOrderMapper voucherOrderMapper;
    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisIdWorker redisIdWorker;

    /**
     * jmeter测试的时候选内容编码一定不要加空格，不然会识别失败
     * @param voucherId
     * @return
     */
    @Override

    public Result querySeckillVoucher(Long voucherId) {
        Long userId=UserHolder.getUser().getId();
        SeckillVoucher byId = seckillVoucherService.getById(voucherId);
        if(byId.getBeginTime().isAfter(LocalDateTime.now())){
            return Result.fail("活动未开始");
        }
        if(byId.getEndTime().isBefore(LocalDateTime.now())){
            return Result.fail("活动已结束");
        }
        if(byId.getStock() < 1){
            return Result.fail("库存不足");
        }
        /**
         * 注意事务失效，这里采用找代理解决，上网查一查吧
         */
        synchronized (userId.toString().intern()) {
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        }
    }
    @Transactional
    public Result createVoucherOrder(Long voucherId) {
        Long userId=UserHolder.getUser().getId();

            int count = query().eq("user_id", UserHolder.getUser().getId()).eq("voucher_id", voucherId).count();
            if (count > 0) {
                return Result.fail("该用户已经购买过");
            }
            boolean success = seckillVoucherService.update().setSql("stock = stock -1")
                    .eq("voucher_id", voucherId)
                    //.eq("stock", byId.getStock())
                    .gt("stock", 0)
                    .update();
            if (!success) {
                return Result.fail("库存不足");
            }
            VoucherOrder voucherOrder = new VoucherOrder();
            Long orderId = redisIdWorker.nexId("order");
            voucherOrder.setVoucherId(voucherId);
            voucherOrder.setId(orderId);
            voucherOrder.setUserId(UserHolder.getUser().getId());
            save(voucherOrder);
            return Result.ok(orderId);

    }
}
