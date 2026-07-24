package com.hmdp.agent.task;

/**
 * 子任务类型。
 * <p>
 * TOOL_CALL：调用 @Tool 方法，复用 GuardedToolCallback 执行。<br>
 * LLM_REASON：调用 ChatClient 做推理/聚合，基于已完成的结果生成结论。
 * </p>
 */
public enum TaskType {
    TOOL_CALL,
    LLM_REASON
}
