package com.hmdp.promptguard.policy;

import com.hmdp.config.PromptGuardProperties;
import com.hmdp.promptguard.ToolGuardPolicy;
import com.hmdp.promptguard.ToolInvocationContext;
import com.hmdp.promptguard.Vote;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 高危工具名单策略
 * <p>
 * 从 {@link PromptGuardProperties#getBlockTools()} 中读取精确匹配名单，
 * 如果工具名称在名单中，返回 {@link Vote#BLOCK}。
 * </p>
 * <p>
 * 完全由 YAML 配置驱动，修改配置即可增删，无需改动代码。
 * </p>
 *
 * <pre>{@code
 * hmdp:
 *   prompt-guard:
 *     block-tools:
 *       - deleteBlog
 *       - deleteUser
 * }</pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HighRiskListPolicy implements ToolGuardPolicy {

    private final PromptGuardProperties properties;

    @Override
    public Vote vote(ToolInvocationContext context) {
        if (properties.getBlockTools().contains(context.getToolName())) {
            log.info("高危工具匹配: tool={}", context.getToolName());
            return Vote.BLOCK;
        }
        return Vote.ABSTAIN;
    }
}
