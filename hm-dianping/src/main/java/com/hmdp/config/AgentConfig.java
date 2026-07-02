// package com.hmdp.config;

// import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
// import jakarta.annotation.Resource;
// import org.springframework.ai.chat.client.ChatClient;
// import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
// import org.springframework.ai.chat.memory.ChatMemory;
// import org.springframework.ai.chat.memory.MessageWindowChatMemory;
// import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
// import org.springframework.context.annotation.Bean;
// import org.springframework.context.annotation.Configuration;
// import org.springframework.jdbc.core.JdbcTemplate;

// @Configuration
// public class AgentConfig {

//     @Resource
//     private JdbcTemplate jdbcTemplate;
//     /**
//      * 对话记忆（用于多轮对话）
//      */
//     @Bean
//     public ChatMemory chatMemory() {
//         // 生产环境建议使用 RedisChatMemory
//         JdbcChatMemoryRepository repository = JdbcChatMemoryRepository.builder().jdbcTemplate(jdbcTemplate).build();
//         return MessageWindowChatMemory.builder().maxMessages(10).chatMemoryRepository(repository).build();
//     }

//     /**
//      * ChatClient（AI 客户端）
//      */
//     @Bean
//     public ChatClient chatClient(DashScopeChatModel chatModel, ChatMemory chatMemory) {
//         return ChatClient.builder(chatModel)
//                 // 系统提示词
//                 .defaultSystem("""
//                         你是一个专业的电商智能客服，名叫"小黑助手"。
//                         你的职责是帮助用户解答关于商品、订单、优惠券、售后等问题。
//                         回答要亲切、耐心、专业。
//                         如果涉及具体订单查询，请引导用户提供订单号。
//                         如果不知道答案，不要编造，可以说"这个问题我需要核实一下，请稍后再问"。
//                         """)
//                 // 对话记忆
//                 .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
//                 .build();
//     }
// }
