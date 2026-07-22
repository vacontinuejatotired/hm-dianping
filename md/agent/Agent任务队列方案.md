# Agent 任务队列方案

> 在现有 PromptHook 基础上，增加 AI 回复后的后处理阶段，将复杂任务拆解为子任务队列执行。

---

## 一、设计约束

| 约束 | 来源 | 影响 |
|------|------|------|
| TaskPlanner 依赖 AI 回复 | AI 回复可能来自**上一轮子任务**的执行结果 | 规划 → 执行 → 再规划 → 再执行，多轮迭代 |
| TaskQueue 在内存中 | CONFIRM 需用户另发请求确认，此时内存队列已丢失 | CONFIRM 类子任务不做队列拆分 |
| HookResult.REPLACE 与结果聚合冲突 | 二选一，互斥 | REPLACE 跳过 TaskPlanner |

---

## 二、整体流程

```
用户输入
    │
    ▼
┌──────────────────────┐
│ ① PromptHook 前置    │ ← 已有，不改
└──────────┬───────────┘
           │
           ▼
┌──────────────────────┐
│ ② AI 调用(含工具)   │ ← 已有，不改
│   call().content()   │    首次推理 + 工具执行
└──────────┬───────────┘
           │ aiResponse
           ▼
┌─────────────────────────────────────────┐
│ ③ TaskPlanner.planAndExecute()         │
│                                         │
│   history = []   ← 已执行历史，累计传递 │
│   round = 0                             │
│   current = aiResponse                  │
│                                         │
│   do {                                  │
│     tasks = decompose(input, current,   │ ← 传入 history，避免重复拆
│                           history)      │
│     if (tasks.isEmpty()) break          │
│     if (hasConfirmTool(tasks)) {        │
│       current += "\n\n⚠️ 需要确认…"     │
│       break                             │
│     }                                   │
│     results = TaskExecutor.run(tasks)   │
│     history.addAll(tasks)               │ ← 追加到历史
│     current = merge(current, results)   │
│     round++                             │
│   } while (round < MAX_ROUNDS           │
│             && totalTasks < MAX_TOTAL)   │
│                                         │
└─────────────────────────────────────────┘
           │ finalResponse
           ▼
┌──────────────────────┐
│ ④ SSE 推送最终结果   │ ← 已有
└──────────────────────┘
```

---

## 三、PromptHook 改动

### 接口只加一个默认方法

```java
@FunctionalInterface
public interface PromptHook {

    // 原有的，不改
    HookResult beforePrompt(String originalInput, String currentInput, ChatContext context);

    // 新增：AI 调用后的后处理，默认 PASS，现有 Hook 不受影响
    default HookResult afterAiResponse(String originalInput, String aiResponse,
                                        ChatContext context, List<SubTask> pendingTasks) {
        return HookResult.pass();
    }
}
```

### HookResult 语义

| 返回 | 含义 | 是否走 TaskPlanner |
|------|------|-------------------|
| **PASS** + pendingTasks 非空 | "需要规划执行" | ✅ |
| **PASS** + pendingTasks 空 | "无事发生" | ❌ |
| **REPLACE** | "我全权处理了" | ❌ |
| **BLOCK** | "阻断" | ❌ |

**REPLACE 和 TaskPlanner 互斥** — 返回 REPLACE 时直接跳过后处理。

---

## 四、TaskPlanningHook —— 触发开关

不做空壳，负责**判断是否需要触发任务规划**：

```java
@Component
public class TaskPlanningHook implements PromptHook {

    /** 触发词：包含这些词的请求可能需要多步规划 */
    private static final List<String> TRIGGER_KEYWORDS = List.of(
            "对比", "总结", "分析", "统计", "归纳", "报告",
            "比较", "差异", "变化", "趋势", "分别"
    );

    @Override
    public HookResult beforePrompt(String originalInput, String currentInput, ChatContext context) {
        return HookResult.pass();
    }

    @Override
    public HookResult afterAiResponse(String originalInput, String aiResponse,
                                       ChatContext context, List<SubTask> pendingTasks) {
        // 用户输入不含触发词 → 不需要规划
        if (TRIGGER_KEYWORDS.stream().noneMatch(k -> originalInput.contains(k))) {
            return HookResult.pass();
        }

        // AI 回复太短或明确拒绝 → 也不需要规划
        if (aiResponse.length() < 20 || aiResponse.contains("无法") || aiResponse.contains("不能")) {
            return HookResult.pass();
        }

        // 塞一个占位标记，告诉 AiServiceImpl 需要走 TaskPlanner
        pendingTasks.add(SubTask.builder()
                .id("__trigger__")
                .description("触发任务规划")
                .build());

        return HookResult.pass();
    }
}
```

这样 AiServiceImpl 的逻辑更内聚：Hook 只做判断，TaskPlanner 只做规划执行，不混在一起。

---

## 五、TaskPlanner —— 核心循环

### 5.1 decompose() 带 history 防重复

```java
/**
 * 将当前结果拆分为子任务
 * @param originalInput  用户原始输入（不变）
 * @param currentResponse 当前已累积的回复（逐轮增长）
 * @param history        已执行的子任务列表（避免重复拆分）
 * @return 本轮新子任务
 */
List<SubTask> decompose(String originalInput, String currentResponse, List<SubTask> history);
```

`history` 中记录了每轮已执行的任务 ID 和描述，`decompose` 解析时先排除已执行过的内容，避免：

```
第 1 轮: 拆出 [查博客1, 查博客2]
第 2 轮: currentResponse 里包含了博客1、博客2的数据
         → 不会再拆"查博客1"（因为 history 里已有）
```

### 5.2 完整实现

```java
@Component
public class TaskPlanner {

    private static final int MAX_ROUNDS = 3;
    private static final int MAX_TASKS_PER_ROUND = 5;
    private static final int MAX_TOTAL = 15;

    @Resource
    private PromptGuardProperties guardProperties;  // 读取 confirm-tools 列表

    public String planAndExecute(String originalInput, String aiResponse,
                                  ChatContext context, ToolCallback[] tools, Long userId) {
        String current = aiResponse;
        List<SubTask> history = new ArrayList<>();
        int totalTasks = 0;

        for (int round = 0; round < MAX_ROUNDS; round++) {
            // ① 拆解（传入 history 避免重复）
            List<SubTask> tasks = decompose(originalInput, current, history);
            if (tasks.isEmpty()) break;

            // ② 限制单轮数量
            if (tasks.size() > MAX_TASKS_PER_ROUND) {
                tasks = tasks.subList(0, MAX_TASKS_PER_ROUND);
            }

            // ③ CONFIRM 检测 —— 触发即终止
            if (hasConfirmTool(tasks)) {
                current += "\n\n⚠️ 部分操作需要你确认后才能执行，请明确告知是否继续。";
                break;
            }

            // ④ 执行
            TaskExecutor executor = new TaskExecutor(tools, userId);
            TaskQueue queue = new TaskQueue(tasks);
            executor.executeAll(queue, 3_000L);  // 单任务超时 3 秒

            // ⑤ 记录历史
            history.addAll(tasks);
            totalTasks += tasks.size();

            // ⑥ 合并结果（结构化格式）
            current = merge(current, queue);

            if (totalTasks >= MAX_TOTAL) break;
        }

        return current;
    }

    private boolean hasConfirmTool(List<SubTask> tasks) {
        List<String> confirmTools = guardProperties.getConfirmTools();  // 从 YAML 读取
        return tasks.stream().anyMatch(t -> confirmTools.contains(t.getToolName()));
    }
}
```

---

## 六、结构化聚合格式 merge()

不简单拼接，每轮结果按固定格式追加：

```text
【结论】
...

【补充信息 1】
...

【补充信息 2】
...
```

```java
private String merge(String currentResponse, TaskQueue queue) {
    List<SubTask> done = queue.getAllResults().stream()
            .filter(t -> t.getStatus() == COMPLETED)
            .collect(toList());

    if (done.isEmpty()) return currentResponse;

    StringBuilder sb = new StringBuilder(currentResponse);
    // 追加分隔线
    sb.append("\n\n---\n");

    for (int i = 0; i < done.size(); i++) {
        SubTask task = done.get(i);
        sb.append("**补充信息 ").append(i + 1).append("**：")
          .append(task.getDescription()).append("\n\n")
          .append(task.getResult()).append("\n\n");
    }

    return sb.toString().trim();
}
```

---

## 七、核心数据模型

### SubTask

```java
@Data
@Builder
public class SubTask {
    String id;
    String description;
    String toolName;
    Map<String, Object> params;
    List<String> dependsOn;          // Phase 1 不使用，预留给 Phase 2
    SubTaskStatus status;
    Object result;
}
```

### TaskQueue

```java
public class TaskQueue {
    private final List<SubTask> tasks;

    List<SubTask> getReadyTasks();        // 依赖已满足的 READY 任务
    void markDone(String id, Object result);
    void markFailed(String id, String error);
    boolean isAllDone();
    List<SubTask> getAllResults();        // 全部结果（含 DONE 和 FAILED）
}
```

### TaskQueue 生命周期

| 版本 | 生命周期 | 说明 |
|------|---------|------|
| Phase 1 | 每次循环新建 | 只用线性无依赖子任务，状态隔离简单 |
| Phase 2 | planAndExecute 成员变量 | 支持跨轮依赖，共享状态 |

---

## 八、TaskExecutor —— 复用 + 超时

```java
public class TaskExecutor {
    private final ToolCallback[] toolCallbacks;
    private final Long userId;

    public void executeAll(TaskQueue queue, long timeoutMs) {
        while (!queue.isAllDone()) {
            List<SubTask> ready = queue.getReadyTasks();
            if (ready.isEmpty()) break;

            for (SubTask task : ready) {
                executeOne(task, queue, timeoutMs);
            }
        }
    }

    private void executeOne(SubTask task, TaskQueue queue, long timeoutMs) {
        if (task.getToolName() == null) {
            queue.markDone(task.getId(), task.getDescription());
            return;
        }

        ToolCallback callback = findTool(task.getToolName());
        if (callback == null) {
            queue.markFailed(task.getId(), "未知工具: " + task.getToolName());
            return;
        }

        try {
            // 带超时的调用
            String result = CompletableFuture.supplyAsync(() ->
                    callback.call(serializeParams(task.getParams()),
                            new ToolContext(Map.of("userId", userId)))
            ).get(timeoutMs, TimeUnit.MILLISECONDS);

            queue.markDone(task.getId(), result);
        } catch (TimeoutException e) {
            log.warn("子任务超时 [tool={}, timeout={}ms]", task.getToolName(), timeoutMs);
            queue.markFailed(task.getId(), "执行超时");
        } catch (Exception e) {
            queue.markFailed(task.getId(), e.getMessage());
        }
    }
}
```

### 复用清单

| 复用什么 | 怎么拿 | 注意 |
|---------|--------|------|
| 包装后的 ToolCallback | `toolBeanCollector.getToolCallbacks()` | **必须确认返回的是 GuardedToolCallback**，可通过 `instanceof` 或日志验证 |
| 守卫投票 | GuardedToolCallback.call() 内部自动执行 | 子任务和主调用同一套规则 |
| AOP 权限 | 回调内部触发 @Around | 自动生效，无需配置 |
| ToolContext | `new ToolContext(Map.of("userId", userId))` | 和主调用一致 |

### ⚠️ 坑：验证 ToolCallback 是否已包装

`ToolBeanCollector.getToolCallbacks()` 返回的是 `GuardedToolCallback[]` 还是原始 `ToolCallback`？

建议在 `AgentConfig` 或启动日志中确认：

```java
for (ToolCallback cb : toolBeanCollector.getToolCallbacks()) {
    log.info("ToolCallback 类型: {}", cb.getClass().getName());
    // 输出应该类似: com.hmdp.promptguard.GuardedToolCallback
}
```

---

## 九、AiServiceImpl 改造

```java
public void chatWithToolcall(String content, SseEmitter emitter) {
    // ========== 前置（现有代码，不改） ==========
    HookResult preResult = hookChain.executeBefore(content, content, context);
    if (preResult.decision() == BLOCK) { ... }
    String finalContent = preResult.decision() == REPLACE ? preResult.replacedText() : content;

    // ========== AI 调用（现有代码，不改） ==========
    String aiResponse = chatClient.prompt()
            .user(finalContent)
            .toolContext(Map.of("userId", userId))
            .call()
            .content();

    // ========== 后处理 ==========
    List<SubTask> pendingTasks = new ArrayList<>();
    HookResult afterResult = hookChain.executeAfter(content, aiResponse, context, pendingTasks);

    if (afterResult.decision() == BLOCK) {
        emitter.send(escapeJson("❌ " + afterResult.reason()));
        emitter.complete();
        return;
    }

    String finalResponse;
    if (afterResult.decision() == REPLACE) {
        finalResponse = afterResult.replacedText();
    } else if (!pendingTasks.isEmpty()) {
        // 交给 TaskPlanner 做规划→执行→再规划循环
        finalResponse = taskPlanner.planAndExecute(
                content, aiResponse, context,
                toolBeanCollector.getToolCallbacks(), userId);
    } else {
        finalResponse = aiResponse;
    }

    emitter.send(escapeJson(finalResponse));
    emitter.complete();
}
```

---

## 十、边界情况

### ⚠️ SSE 连接关闭风险

子任务可能耗时数秒，若前端的 SSE 连接提前关闭，后面的推送会抛异常。

```java
// emitter.send() 失败时捕获，清理资源
try {
    emitter.send(...);
} catch (IOException e) {
    log.warn("SSE 推送失败，连接可能已关闭");
    // TaskPlanner 的剩余子任务通过 aiTaskExecutor.submit() 异步取消
}
```

### 其他边界

| 场景 | 处理 |
|------|------|
| 空分解 | decompose 返回空 → while 终止 |
| 所有子任务都 FAILED | 回复末尾追加"部分步骤执行失败" |
| 部分子任务 FAILED | 成功的合并到回复，失败的标注原因 |
| 未知工具名 | findTool 返回 null → FAILED |
| MAX_ROUNDS / MAX_TOTAL 到达 | 终止循环，回复末尾追加"已达到分析上限" |
| CONFIRM 触发 | 终止循环，追加确认提示 |
| 依赖死环 | Phase 1 不用依赖，Phase 2 加入构建 DAG 时检测 |
| 内存队列丢失 | Phase 1 不做跨请求任务，CONFIRM 直接返回提示 |

---

## 十一、Phase 1 实施范围

### 做

| 文件 | 内容 |
|------|------|
| `PromptHook.java` | 加 `afterAiResponse()` 默认方法 |
| `PromptHookChain.java` | 加 `executeAfter()` |
| `SubTask.java` | 数据模型（新建） |
| `TaskQueue.java` | 任务队列（新建，线性依赖） |
| `TaskExecutor.java` | 子任务执行器（新建，复用 GuardedToolCallback） |
| `TaskPlanner.java` | 规划（新建，规则模板 + while 循环） |
| `TaskPlanningHook.java` | 触发开关（新建，关键词检测） |
| `AiServiceImpl.java` | 后处理集成 |

### 不做

| 事项 | 原因 |
|------|------|
| LLM 推理型子任务 | Phase 1 只拆 TOOL_CALL |
| 子任务并行执行 | 串行更可控，后续加 |
| 跨轮 DAG 依赖 | Phase 1 线性无依赖 |
| 持久化 / 重试 | Phase 1 内存态 |
| TaskQueue 提升为成员变量 | Phase 1 每次循环新建 |
