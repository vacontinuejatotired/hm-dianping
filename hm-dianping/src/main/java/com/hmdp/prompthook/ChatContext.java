package com.hmdp.prompthook;

import org.springframework.ai.chat.messages.Message;

import java.util.Collections;
import java.util.List;

/**
 * Prompt Hook 评估上下文
 * <p>
 * 携带本次 LLM 调用前的全部可观测信息，传递给 {@link PromptHookChain} 中的每个 {@link PromptHook}。
 * </p>
 * <p>
 * 注意：history 为当前会话中已有的对话历史（不含本轮用户输入），不可变。
 * </p>
 */
public class ChatContext {

    /** 当前登录用户 ID */
    private final Long userId;

    /** 会话 ID（用于多轮对话标识） */
    private final String conversationId;

    /** 全量对话历史（不含本轮输入），不可修改 */
    private final List<Message> history;

    private ChatContext(Builder builder) {
        this.userId = builder.userId;
        this.conversationId = builder.conversationId;
        this.history = builder.history != null
                ? Collections.unmodifiableList(builder.history)
                : Collections.emptyList();
    }

    // ---- getters ----

    public Long getUserId() { return userId; }
    public String getConversationId() { return conversationId; }
    public List<Message> getHistory() { return history; }

    // ---- builder ----

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private Long userId;
        private String conversationId;
        private List<Message> history;

        public Builder userId(Long userId) { this.userId = userId; return this; }
        public Builder conversationId(String conversationId) { this.conversationId = conversationId; return this; }
        public Builder history(List<Message> history) { this.history = history; return this; }
        public ChatContext build() { return new ChatContext(this); }
    }
}
