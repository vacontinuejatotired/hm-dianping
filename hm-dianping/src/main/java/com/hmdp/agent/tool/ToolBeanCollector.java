package com.hmdp.tool;

import com.hmdp.annotation.TargetTool;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 自动收集所有标注了 {@link TargetTool @TargetTool} 的 Spring Bean。
 * <p>
 * 替代手动在 {@code AgentConfig} 中逐个 {@code @Resource} + {@code .defaultTools(...)} 注册。
 * 新增工具类时只需：
 * <ol>
 *   <li>在类上标注 {@code @TargetTool}（已含 {@code @Component} 语义）；</li>
 *   <li>在方法上标注 {@code @Tool}。</li>
 * </ol>
 * 无需修改配置代码。
 * <p>
 * 使用示例（AgentConfig）：
 * <pre>{@code
 * .defaultTools(toolBeanCollector.getToolBeans())
 * }</pre>
 *
 * @see TargetTool
 * @see org.springframework.ai.chat.client.ChatClient.Builder#defaultTools(Object...)
 */
@Slf4j
@Component
public class ToolBeanCollector implements ApplicationContextAware {

    private Object[] toolBeans = new Object[0];

    @Override
    public void setApplicationContext(@NonNull ApplicationContext applicationContext) throws BeansException {
        // 利用 Spring 内置的注解索引，获取所有标注 @TargetTool 的 Bean
        Map<String, Object> beans = applicationContext.getBeansWithAnnotation(TargetTool.class);

        List<Object> collected = new ArrayList<>();
        for (Map.Entry<String, Object> entry : beans.entrySet()) {
            Object bean = entry.getValue();
            TargetTool annotation = bean.getClass().getAnnotation(TargetTool.class);

            if (annotation == null) {
                // 处理 CGLIB 代理场景：取原始类
                Class<?> userClass = org.springframework.util.ClassUtils.getUserClass(bean.getClass());
                annotation = userClass.getAnnotation(TargetTool.class);
            }

            if (annotation != null && annotation.active()) {
                collected.add(bean);
                log.info("收集到工具 Bean [{}]: {}", entry.getKey(), bean.getClass().getSimpleName());
            } else if (annotation != null) {
                log.info("跳过已停用的工具 Bean [{}]: {}", entry.getKey(), bean.getClass().getSimpleName());
            }
        }

        this.toolBeans = collected.toArray();
        log.info("工具 Bean 自动收集完成，共 {} 个（激活 {} / 总计 {}）",
                toolBeans.length, collected.size(), beans.size());
    }

    /**
     * 返回收集到的所有激活的工具 Bean 实例数组。
     * <p>
     * 可直接传入 {@code ChatClient.Builder.defaultTools(Object...)}。
     */
    public Object[] getToolBeans() {
        return toolBeans;
    }
}
