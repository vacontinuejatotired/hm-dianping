 package com.hmdp.config;

 import com.hmdp.utils.RabbitMqConstants;
 import org.springframework.amqp.core.*;
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
     @Bean
     public Exchange AlternatEexchange(){
         Map<String, Object> arguments = new HashMap<>();
         return ExchangeBuilder.fanoutExchange(RabbitMqConstants.ALTERNATE_EXCHANGE_NAME).durable(true)
                 .build();
     }

     @Bean
     public Exchange deadExchange(){
         Map<String, Object> arguments = new HashMap<>();
         return ExchangeBuilder.topicExchange(RabbitMqConstants.DEAD_EXCHANGE_NAME).durable(true)
                 .build();
     }

     @Bean
     public Exchange TopicExchange() {
         Map<String, Object> args = new HashMap<>();
         args.put("alternate-exchange", RabbitMqConstants.ALTERNATE_EXCHANGE_NAME);
         return new TopicExchange(RabbitMqConstants.NORMAL_EXCHANGE_NAME,true,false,args);
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
         arguments.put("x-queue-type","classic");
         return QueueBuilder.durable(RabbitMqConstants.QUEUE_NAME).withArguments(arguments).build();
     }

     @Bean
     public Queue deadQueue() {
         Map<String, Object> arguments = new HashMap<>();
         arguments.put("x-message-ttl", 60000);
         arguments.put("x-max-length", 10000);
         arguments.put("x-queue-type","classic");
         arguments.put("x-overflow", "drop-head");
         // 可选：消息最大重试投递次数（防毒消息无限循环）

         arguments.put("x-delivery-limit", 20);
         return QueueBuilder.durable(RabbitMqConstants.DEAD_QUEUE_NAME).build();
     }

     @Bean
     public Binding voucherOrderBinding() {
         return BindingBuilder
                 .bind(voucherOrderQueue())
                 .to(TopicExchange()).
                 with(RabbitMqConstants.NORMAL_ROUTING_KEY).noargs();
     }

     @Bean
     public Binding deadQueueBinding() {
         return BindingBuilder
                 .bind(deadQueue())
                 .to(deadExchange())
                 .with(RabbitMqConstants.DEAD_ROUTING_KEY).noargs();
     }
 }