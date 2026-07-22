package com.hmdp.agent.config;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.hmdp.agent.tool.ToolBeanCollector;

import ch.qos.logback.classic.Logger;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
@Slf4j
public class AgentConfig {

    @Resource
    private JdbcTemplate jdbcTemplate;

    @PostConstruct
    void checkLogLevels() {
        log.info("========== 日志级别诊断 ==========");
        String[] checkLoggers = {
                "com.hmdp",
                "com.hmdp.agent",
                "com.hmdp.agent.tool",
                "com.hmdp.promptguard",
                "com.hmdp.promptguard.GuardedToolCallback",
                "com.hmdp.permission",
        };
        for (String name : checkLoggers) {
            Logger l = (Logger) LoggerFactory.getLogger(name);
            log.info("Logger[{}] level={} effective={} debugEnabled={}",
                    name, l.getLevel(), l.getEffectiveLevel(), l.isDebugEnabled());
        }
        log.info("========== 诊断结束 ==========");
    }

    /**
     * AI 专用线程池（用于流式响应中的异步 AI 调用）
     * <p>
     * 避免使用 ForkJoinPool.commonPool()，防止与项目其他异步任务竞争线程。
     * </p>
     */
    @Bean("aiTaskExecutor")
    public Executor aiTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("ai-worker-");
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }

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
                        .defaultToolCallbacks(toolCallbacks)
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
