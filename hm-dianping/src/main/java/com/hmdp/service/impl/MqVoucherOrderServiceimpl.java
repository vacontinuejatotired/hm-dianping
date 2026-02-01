package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;

import com.hmdp.utils.RabbitMqConstants;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.io.IOException;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalUnit;


@Service
@Slf4j
public class MqVoucherOrderServiceimpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RabbitTemplate rabbitTemplate;
    @Resource
    private ISeckillVoucherService seckillVoucherService;
    private static final String LOCK_KET_PREFIX = "voucher:order:lock";
    private static final int MAX_RETRY = 3;
    @Resource
    private AmqpAdmin amqpAdmin;
    @PostConstruct
    public void init(){
        createQueue(RabbitMqConstants.QUEUE_NAME);
        createQueue(RabbitMqConstants.DEAD_QUEUE_NAME);
    }

    private void createQueue(String queueName) {
        QueueInformation exist= amqpAdmin.getQueueInfo(queueName) ;
        if(exist==null) {
            log.info("不存在队列{},即将添加",queueName);
            Queue queue =new Queue(queueName, true, false, false);
            queue.shouldDeclare();
            amqpAdmin.declareQueue(queue);
            log.info("队列{}创建",queueName);
        }
        else{
            log.info("{}已存在,详细信息{}",queueName,exist);
        }
    }

    @RabbitListener(queues = "voucher_order")
    public void voucherOrderHandler(Message message, Channel channel, @Header("orderId") Long orderId, @Header("voucherId")Long voucherId, @Header(AmqpHeaders.DELIVERY_TAG)Long deliveryTag,@Header("retryCounts") int retryCounts) throws IOException {
        if(retryCounts > MAX_RETRY){
            //TODO放入死信队列
            channel.basicReject(deliveryTag, false);
            deadVoucherOrderHandler(message,orderId,voucherId,deliveryTag);
            //再次放入主队列重试
            channel.basicNack(deliveryTag,false,true);
            return;
        }
        String key = LOCK_KET_PREFIX + orderId;
        boolean lock = Boolean.TRUE.equals(stringRedisTemplate.opsForValue().setIfAbsent(key, "processing", Duration.of(3, ChronoUnit.HOURS)));
        if(lock) {
            VoucherOrder voucherOrder = new VoucherOrder().setVoucherId(voucherId).setPayType(1).setId(orderId);
            boolean saved = save(voucherOrder);
            if (saved) {
                try {
                    //加个Lua检查
                    stringRedisTemplate.delete(key);
                    channel.basicAck(deliveryTag, false);
                } catch (IOException e) {
                    log.info("确定信息失败{}", e.getMessage());
                    retryCounts++;
                    //重新把消息放到前面处理
                    channel.basicNack(deliveryTag, false,true);
                    throw new RuntimeException(orderId+"消息确定失败,已重新投放消息",e);
                }
            } else {
                log.info("{}插入失败", orderId);
                retryCounts++;
                //是不是应该加个前提是其他人没有持有锁，Lua脚本合并redis操作？
                stringRedisTemplate.expire(key, Duration.of(3, ChronoUnit.MINUTES));
                channel.basicNack(deliveryTag, false,true);
            }
        }
        else{
            log.info("订单{}正在处理",orderId);
            channel.basicNack(deliveryTag, false,true);
        }
        return;
    }

    @RabbitListener(queues = "dead_voucher_order")
    public void deadVoucherOrderHandler(Message message,@Header("orderId") Long orderId,
                                        @Header("voucherId")Long voucherId,
                                        @Header(AmqpHeaders.DELIVERY_TAG)Long deliveryTag
                                        ) throws IOException {
        log.info("订单{}插入死信队列",orderId);

    }
}
