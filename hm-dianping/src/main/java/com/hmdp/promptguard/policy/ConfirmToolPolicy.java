package com.hmdp.promptguard.policy;

import com.hmdp.config.PromptGuardProperties;
import com.hmdp.promptguard.ToolGuardPolicy;
import com.hmdp.promptguard.ToolInvocationContext;
import com.hmdp.promptguard.Vote;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 需确认工具名单策略
 * <p>
 * 从 {@link PromptGuardProperties#getConfirmTools()} 中读取精确匹配名单，
 * 如果工具名称在名单中，返回 {@link Vote#CONFIRM}。
 * </p>
 *
 * <pre>{@code
 * hmdp:
 *   prompt-guard:
 *     confirm-tools:
 *       - publishTestBlog
 *       - updateProfile
 * }</pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConfirmToolPolicy implements ToolGuardPolicy {

    private final PromptGuardProperties properties;

    @Override
    public Vote vote(ToolInvocationContext context) {
        if (properties.getConfirmTools().contains(context.getToolName())) {
            log.info("需确认工具匹配: tool={}", context.getToolName());
            return Vote.CONFIRM;
        }
        return Vote.ABSTAIN;
    }
}
