package com.hmdp.promptguard;

import com.hmdp.promptguard.GuardResult.Decision;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 工具回调守卫包装器 — 在原始 {@link ToolCallback#call(String)} 执行前插入守卫逻辑
 * <p>
 * 代理模式：持有原始 {@link ToolCallback} 委托对象，在 {@code call()} 中：
 * <ol>
 *   <li>构造 {@link ToolInvocationContext}</li>
 *   <li>委托 {@link ToolGuardManager#evaluate(ToolInvocationContext)} 做风险评估</li>
 *   <li>根据决策结果决定拦截还是放行</li>
 * </ol>
 * </p>
 *
 * @see ToolGuardManager
 * @see com.hmdp.agent.tool.ToolBeanCollector
 */
@Slf4j
public class GuardedToolCallback implements ToolCallback {

    private final ToolCallback delegate;
    private final ToolGuardManager guardManager;
    private final String sessionId;
    private final AtomicInteger invokeCounter;

    public GuardedToolCallback(ToolCallback delegate, ToolGuardManager guardManager,
                               String sessionId) {
        this.delegate = delegate;
        this.guardManager = guardManager;
        this.sessionId = sessionId;
        this.invokeCounter = new AtomicInteger(0);
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String functionPayload) {
        // 1. 构造上下文（无 ToolContext 时）
        String toolName = delegate.getToolDefinition().name();
        ToolInvocationContext context = ToolInvocationContext.builder()
                .toolName(toolName)
                .arguments(functionPayload)
                .sessionId(sessionId)
                .invocationCount(invokeCounter.incrementAndGet())
                .build();

        // 2. 执行守卫评估
        GuardResult result = guardManager.evaluate(context);

        // 3. 根据决策处理
        switch (result.getDecision()) {
            case BLOCK -> {
                String msg = result.getReason() != null ? result.getReason()
                        : "❌ 该操作已被安全策略拦截";
                log.warn("工具调用被拦截 [tool={}, policy={}]", toolName, result.getPolicyName());
                return msg;
            }
            case CONFIRM -> {
                log.info("工具调用需确认 [tool={}, policy={}]", toolName, result.getPolicyName());
                return "⚠️ 该操作需要你的确认才能执行";
            }
            case ALLOW -> {
                log.debug("工具调用放行 [tool={}]", toolName);
                return delegate.call(functionPayload);
            }
        }
        return delegate.call(functionPayload);
    }

    @Override
    public String call(String functionPayload, ToolContext toolContext) {
        // 2. 构造上下文（带 ToolContext，可提取 userId）
        String toolName = delegate.getToolDefinition().name();
        Long userId = null;
        if (toolContext != null && toolContext.getContext() != null) {
            Object uid = toolContext.getContext().get("userId");
            if (uid instanceof Long) {
                userId = (Long) uid;
            }
        }

        ToolInvocationContext context = ToolInvocationContext.builder()
                .toolName(toolName)
                .arguments(functionPayload)
                .sessionId(sessionId)
                .userId(userId)
                .invocationCount(invokeCounter.incrementAndGet())
                .build();

        GuardResult result = guardManager.evaluate(context);

        switch (result.getDecision()) {
            case BLOCK -> {
                String msg = result.getReason() != null ? result.getReason()
                        : "❌ 该操作已被安全策略拦截";
                log.warn("工具调用被拦截 [tool={}, policy={}, userId={}]",
                        toolName, result.getPolicyName(), userId);
                return msg;
            }
            case CONFIRM -> {
                log.info("工具调用需确认 [tool={}, policy={}, userId={}]",
                        toolName, result.getPolicyName(), userId);
                return "⚠️ 该操作需要你的确认才能执行";
            }
            case ALLOW -> {
                log.debug("工具调用放行 [tool={}, userId={}]", toolName, userId);
                return delegate.call(functionPayload, toolContext);
            }
        }
        return delegate.call(functionPayload, toolContext);
    }
}
