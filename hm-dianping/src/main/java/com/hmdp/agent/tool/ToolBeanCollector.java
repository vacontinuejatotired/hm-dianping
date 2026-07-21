package com.hmdp.agent.tool;

import com.hmdp.annotation.TargetTool;
import com.hmdp.promptguard.GuardedToolCallback;
import com.hmdp.promptguard.ToolGuardManager;
import lombok.extern.slf4j.Slf4j;

import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 自动收集所有标注了 {@link TargetTool @TargetTool} 的 Spring Bean，
 * 并将其转换为 {@link ToolCallback} 数组，每个回调由 {@link GuardedToolCallback}
 * 包装以插入守卫逻辑。
 * <p>
 * 使用示例（AgentConfig）：
 * <pre>{@code
 * .defaultTools(toolBeanCollector.getToolCallbacks())
 * }</pre>
 *
 * @see TargetTool
 * @see GuardedToolCallback
 * @see org.springframework.ai.chat.client.ChatClient.Builder#defaultTools(Object...)
 */
@Slf4j
@Component
public class ToolBeanCollector implements ApplicationContextAware {

    private ToolCallback[] toolCallbacks = new ToolCallback[0];
    private final ToolGuardManager guardManager;

    /** 当前会话 ID（每轮对话更新一次） */
    private volatile String sessionId = UUID.randomUUID().toString().replace("-", "");

    public ToolBeanCollector(ToolGuardManager guardManager) {
        this.guardManager = guardManager;
    }

    @Override
    public void setApplicationContext(@NonNull ApplicationContext applicationContext) throws BeansException {
        Map<String, Object> beans = applicationContext.getBeansWithAnnotation(TargetTool.class);

        List<ToolCallback> collected = new ArrayList<>();
        for (Map.Entry<String, Object> entry : beans.entrySet()) {
            Object bean = entry.getValue();
            TargetTool annotation = resolveAnnotation(bean);

            if (annotation != null && annotation.active()) {
                // 将 Bean 转为 Spring AI 的 ToolCallback 数组（每个 @Tool 方法一个）
                ToolCallback[] rawArray = ToolCallbacks.from(bean);
                for (ToolCallback raw : rawArray) {
                    GuardedToolCallback guarded = new GuardedToolCallback(raw, guardManager, sessionId);
                    collected.add(guarded);
                    log.info("注册工具 [{}] -> GuardedToolCallback", raw.getToolDefinition().name());
                }
            } else if (annotation != null) {
                log.info("跳过已停用的工具 Bean [{}]: {}", entry.getKey(), bean.getClass().getSimpleName());
            }
        }

        this.toolCallbacks = collected.toArray(new ToolCallback[0]);
        log.info("工具回调收集完成，共 {} 个", toolCallbacks.length);
    }

    /**
     * 返回收集到的所有已包装的 {@link ToolCallback} 数组。
     * <p>
     * 可传入 {@code ChatClient.Builder.defaultTools(Object...)}。
     */
    public ToolCallback[] getToolCallbacks() {
        return toolCallbacks;
    }

    /** 获取当前会话 ID */
    public String getSessionId() {
        return sessionId;
    }

    /** 更新会话 ID（新对话开始时调用） */
    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    /** 解析注解（兼容 CGLIB 代理场景） */
    private TargetTool resolveAnnotation(Object bean) {
        TargetTool annotation = bean.getClass().getAnnotation(TargetTool.class);
        if (annotation == null) {
            Class<?> userClass = org.springframework.util.ClassUtils.getUserClass(bean.getClass());
            annotation = userClass.getAnnotation(TargetTool.class);
        }
        return annotation;
    }
}
