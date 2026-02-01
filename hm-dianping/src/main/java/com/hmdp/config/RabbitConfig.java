 package com.hmdp.config;

 import com.hmdp.utils.RabbitMqConstants;
 import org.springframework.amqp.core.Queue;
 import org.springframework.amqp.core.QueueBuilder;
 import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
 import org.springframework.amqp.rabbit.core.RabbitTemplate;
 import org.springframework.context.annotation.Bean;
 import org.springframework.context.annotation.Configuration;
 import org.springframework.context.annotation.Primary;

 import java.util.HashMap;
 import java.util.Map;


 @Configuration
 public class RabbitConfig {

     private static final String USERNAME = "qyh";

//     @Bean
//     @Primary
//     public CachingConnectionFactory connectionFactory() {
//         CachingConnectionFactory factory = new CachingConnectionFactory("192.168.49.130");
//         factory.setPort(5672);
//         factory.setUsername("qyh");
//         factory.setPassword("123321");
//         factory.setVirtualHost("/");
//         factory.setChannelCacheSize(25);           // 默认值，可调大
//         factory.setConnectionCacheSize(1);         // 单连接
//         factory.setPublisherConfirms(true);        // 启用确认，防止某些异常
//         factory.setPublisherReturns(true);
//         return factory;
//     }

     // 如果使用 RabbitTemplate，也可自定义
//     @Bean("myRabbitTemplate")
     @Bean
     public RabbitTemplate rabbitTemplate(CachingConnectionFactory cf) {
         RabbitTemplate template = new RabbitTemplate(cf);
         template.setMandatory(true);  // 如有返回，可观察
         return template;
     }

     /**
      * 声明一个仲裁对列
      * @return
      */
     @Bean
     public Queue voucherOrderQueue() {
         Map<String, Object> arguments = new HashMap<>();
         arguments.put("x-dead-letter-exchange", RabbitMqConstants.DEAD_EXCHANGE_NAME);
         arguments.put("x-dead-letter-routing-key",RabbitMqConstants.DEAD_ROUTING_KEY);
         arguments.put("x-message-ttl", 60000);
         arguments.put("x-max-length", 10000);
         arguments.put("x-queue-type","quorum");
         return QueueBuilder.durable(RabbitMqConstants.QUEUE_NAME).withArguments(arguments).build();
     }
 }