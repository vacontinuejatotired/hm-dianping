 package com.hmdp.config;

 import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
 import org.springframework.amqp.rabbit.core.RabbitTemplate;
 import org.springframework.context.annotation.Bean;
 import org.springframework.context.annotation.Configuration;
 import org.springframework.context.annotation.Primary;


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
     @Bean("myRabbitTemplate")
     public RabbitTemplate rabbitTemplate(CachingConnectionFactory cf) {
         RabbitTemplate template = new RabbitTemplate(cf);
         template.setMandatory(true);  // 如有返回，可观察
         return template;
     }
 }