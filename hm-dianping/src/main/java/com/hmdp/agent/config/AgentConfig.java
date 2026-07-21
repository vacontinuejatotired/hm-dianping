package com.hmdp.agent.config;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.hmdp.agent.tool.ToolBeanCollector;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
@Slf4j
public class AgentConfig {

    @Resource
    private JdbcTemplate jdbcTemplate;

    /**
     * 对话记忆（用于多轮对话）
     */
    @Bean
    public ChatMemory chatMemory() {
        // 生产环境建议使用 RedisChatMemory
        JdbcChatMemoryRepository repository = JdbcChatMemoryRepository.builder().jdbcTemplate(jdbcTemplate).build();
        return MessageWindowChatMemory.builder().maxMessages(10).chatMemoryRepository(repository).build();
    }

    /**
     * ChatClient（AI 客户端）
     * <p>
     * 工具自动注入：{@link ToolBeanCollector} 在启动时扫描所有含 {@code @Tool} 方法的 Bean，
     * 无需手动 {@code @Resource} 每个工具类。
     */
    @Bean("aliibabaChatClient")
    public ChatClient chatClient(DashScopeChatModel chatModel, ChatMemory chatMemory,
                                 ToolBeanCollector toolBeanCollector) {
        ToolCallback[] toolCallbacks = toolBeanCollector.getToolCallbacks();

        ChatClient chatClient = ChatClient.builder(chatModel)
                        // 系统提示词
                        .defaultSystem("""
                                你是电商客服，但当用户问天气时，你必须调用 queryWeather 工具，不要自己回答。
                                """)
                        // 对话记忆
                        .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                        // 自动注入所有已包装守卫的 ToolCallback
                        .defaultTools((Object[]) toolCallbacks)
                        .build();
        log.info("ChatClient 构建完成，注册守卫工具 {} 个", toolCallbacks.length);

        return chatClient;
    }

    // @Bean
    // public ChatClient chatClient(OpenAiChatModel chatModel, ChatMeory chatMemory) {
    //     return ChatClient.builder(chatModel)
    //             .defaultSystem("你是一个专业的电商智能客服，名叫"小黑助手"。")
    //             .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
    //             .build();
    // }
}
