package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RabbitMqConstants;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.MessagePropertiesBuilder;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.aop.framework.AopContext;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.*;


@Service
@Slf4j
@Primary
public class MqVoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private RabbitTemplate rabbitTemplate;
    @Resource
    private ISeckillVoucherService seckillVoucherService;
    private static final String LOCK_KET_PREFIX = "voucher:order:lock";
    private static final int MAX_RETRY = 3;

    @RabbitListener(queues = RabbitMqConstants.QUEUE_NAME)
    public void voucherOrderHandler(
            VoucherOrder voucherOrder,
            Message message,                                      // 新增：用于读取和设置 headers
            Channel channel,
            @Header(AmqpHeaders.DELIVERY_TAG) Long deliveryTag) throws IOException {

        // 从消息头中获取当前重试次数（第一次消费为 null）
        Integer retryCount = message.getMessageProperties()
                .getHeader("x-retry-count");
        if (retryCount == null) {
            retryCount = 0;
        }

        // 超过最大重试次数 → 直接进入死信队列
        if (retryCount >= MAX_RETRY) {
            log.warn("deliveryTag：{} 已超过最大重试次数 {}，放入死信队列", deliveryTag, MAX_RETRY);
            channel.basicAck(deliveryTag, false);
            rabbitTemplate.convertAndSend(
                    RabbitMqConstants.DEAD_EXCHANGE_NAME,   // 推荐直接发交换机，更可靠
                    RabbitMqConstants.DEAD_ROUTING_KEY,
                    voucherOrder);
            return;
        }

        boolean success = false;
        try {
            success = seckillVoucherService.update()
                    .setSql("stock = stock -1")
                    .eq("voucher_id", voucherOrder.getVoucherId())
                    .gt("stock", 0)
                    .update();
        } catch (Exception e) {
            log.error("更新库存失败,订单id:{}", voucherOrder.getVoucherId(), e);
            // 系统异常：增加重试计数后重新入队
            rejectAndRequeueWithIncrement(channel, deliveryTag, message, retryCount + 1);
            return;
        }

        if (!success) {
            log.warn("库存不足，订单id:{}", voucherOrder.getId());
            // 业务失败也重试（根据您的要求）
            rejectAndRequeueWithIncrement(channel, deliveryTag, message, retryCount + 1);
            return;
        }

        // 成功：确认消息
        channel.basicAck(deliveryTag, false);
        log.info("订单{}扣减数据库库存成功", voucherOrder.getId());
    }

    /**
     * 拒绝消息并重新入队，同时在 header 中增加重试次数
     */
    // 在 rejectAndRequeueWithIncrement 方法中：
    private void rejectAndRequeueWithIncrement(Channel channel, Long deliveryTag,
                                               Message message, int nextRetryCount) throws IOException {

        // 1. 获取原消息属性（Spring 类型）
        MessageProperties originalProps = message.getMessageProperties();

        // 2. 构建新的属性副本，并设置重试计数
        MessageProperties newProps = MessagePropertiesBuilder
                .fromClonedProperties(originalProps)// 这里使用 Spring 的 MessagePropertiesBuilder
                .setHeader("x-retry-count", nextRetryCount)
                .build();

        // 3. 构建新消息
        Message newMessage = MessageBuilder
                .withBody(message.getBody())
                .andProperties(newProps)
                .build();

        // 4. 重新投递到当前队列（使用默认交换机 ""）
        Map<String, Object> headers = new HashMap<>(message.getMessageProperties().getHeaders());
        headers.put("x-retry-count", nextRetryCount);

        AMQP.BasicProperties nativeProps = new AMQP.BasicProperties.Builder()
                .headers(headers)
                .deliveryMode(2)  // persistent
                .build();
        channel.basicPublish(
                "",                               // 默认交换机（direct to queue）
                RabbitMqConstants.QUEUE_NAME,     // 队列名作为 routing key
                false,                            // mandatory
                nativeProps,  // 注意：这里要转成 Map 或用原生方式
                newMessage.getBody()
        );

        // 5. 确认原消息（防止重复）
        channel.basicAck(deliveryTag, false);
    }

    @RabbitListener(queues = RabbitMqConstants.DEAD_QUEUE_NAME)
    public void deadQueueHandler(VoucherOrder voucherOrder, Channel channel, @Header(AmqpHeaders.DELIVERY_TAG) Long deliverTag) throws IOException {
        boolean success = false;
        log.info("订单id：{}进入死信队列", voucherOrder.getId());
        channel.basicAck(deliverTag, false);
        log.info("order:{} has been down", voucherOrder.getId());
    }

    /**
     * 设置lua脚本
     */
    private static final DefaultRedisScript<Long> REDIS_UNLOCK_SCRIPT;

    static {
        REDIS_UNLOCK_SCRIPT = new DefaultRedisScript<>();
        REDIS_UNLOCK_SCRIPT.setResultType(Long.class);
        REDIS_UNLOCK_SCRIPT.setLocation(new ClassPathResource("MqSeckill.lua"));
    }

    @Override
    public Result querySeckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUserId();
        Long orderId = redisIdWorker.nextId("order");
        Long result = stringRedisTemplate.execute(REDIS_UNLOCK_SCRIPT, Collections.emptyList(), voucherId.toString(), userId.toString(), orderId.toString());
        int r = result.intValue();
        //0代表才加入缓存，1代表库存不足，2代表重复下单
        //集群部署的redis，你这怎么查？
        if (r != 0) {
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }
        //警示！！代理对象要变成全局使用的
        //TODO 集群下目前只会有一个会有代理对象，需要解决
        IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
        //返回订单号
        return Result.ok(orderId);
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        int count = query().eq("user_id", voucherOrder.getUserId()).eq("voucher_id", voucherOrder.getVoucherId()).count();
        if (count > 0) {
            log.error("该用户已经购买过");
            return;
        }
        //发送到消息队列处理扣减库存
        rabbitTemplate.convertAndSend(RabbitMqConstants.NORMAL_EXCHANGE_NAME, RabbitMqConstants.NORMAL_ROUTING_KEY, voucherOrder);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result saveOrder(Long voucherId) {
        Long userId = UserHolder.getUserId();
        //查询库存，然后扣减，lua脚本实现
        Long orderId = redisIdWorker.nextId("order");
        List<String> args = new ArrayList<>();
        args.add(String.valueOf(voucherId));
        args.add(String.valueOf(userId));
        args.add(String.valueOf(orderId));
        try {
            int result = stringRedisTemplate.execute(REDIS_UNLOCK_SCRIPT, Collections.emptyList(), args.toArray()).intValue();
            //0->扣减 1->不足 2->已下单
            if (result != 0) {
                return Result.fail(result == 1 ? "库存不足" : "重复下单");
            }
        } catch (Exception e) {
            log.info("执行Lua脚本失败");
            throw new RuntimeException(e);
        }
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);
        voucherOrder.setCreateTime(LocalDateTime.now());
        voucherOrder.setStatus(1);
        //插入雪花算法生成的Id吗？是不是要考虑页分裂消耗的时间影响，
        voucherOrder.setId(orderId);
        boolean saved = save(voucherOrder);
        if (!saved) {
            return Result.fail("订单创建失败，请稍后重试");
        }
        try {
            //扣减库存
            rabbitTemplate.
                    convertAndSend(RabbitMqConstants.NORMAL_EXCHANGE_NAME
                            , RabbitMqConstants.NORMAL_ROUTING_KEY
                            , voucherOrder);
        } catch (AmqpException e) {
            log.info("异步更新库存失败");
            throw new RuntimeException(e);
        }
        return Result.ok(orderId);
    }
}
