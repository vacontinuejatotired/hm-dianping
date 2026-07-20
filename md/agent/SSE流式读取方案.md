# SSE 流式读取方案

## 1. 背景

后端 AI 聊天接口 `POST /string/send?content=xxx` 当前返回普通 JSON。未来可能需要切换为 **SSE (Server-Sent Events)** 流式响应，逐字吐出 AI 回复，提升用户体验。

SSE 使用 `text/event-stream` 格式：
```
data: {逐字或逐段文本}
data: {逐字或逐段文本}
data: [DONE]
```

## 2. 需求

- 前端**同时兼容**普通 JSON 和 SSE 两种后端返回格式，后端切换时前端不改代码
- SSE 模式下：逐字/逐段更新对话气泡内容，实现打字机效果
- 普通 JSON 模式下：保持现有行为不变
- 支持 `AbortController`，用户可取消正在进行的流式请求
- 零外部依赖，纯原生 `fetch` + `ReadableStream`

## 3. 方案设计

```
┌─────────────────────────────────────────────────────────┐
│                    前端调用方                             │
│  parseIntent() 调 agentApi.chatStream(content, onChunk)  │
└────────────────────┬────────────────────────────────────┘
                     │
                     ▼
┌─────────────────────────────────────────────────────────┐
│              src/utils/sse.ts — readSSE                  │
│                                                         │
│  ① fetch(POST, { Accept: text/event-stream })            │
│                                                         │
│  ② 检查 Content-Type                                    │
│     ├── 不是 text/event-stream → 一次性读 body → resolve │
│     └── 是 text/event-stream → 走流式解析路径             │
│                                                         │
│  ③ 流式解析路径：                                       │
│     · response.body.getReader() → ReadableStream        │
│     · TextDecoder 逐块解码                               │
│     · 按 \n 分割行，匹配 data: 前缀                      │
│     · [DONE] 终止标记                                    │
│     · onChunk 回调已收到的完整文本                        │
│                                                         │
│  ④ 返回完整文本 → resolve                                │
└─────────────────────────────────────────────────────────┘
                     ▲
                     │
POST /string/send?content=xxx
Accept: text/event-stream
───────────────────────────►
                             后端
                    ┌────────────────────┐
                    │ 普通 JSON 或 SSE    │
                    │ Content-Type 自动   │
                    │ 识别                │
                    └────────────────────┘
```

## 4. API 设计

### 4.1 `readSSE(url, options)`

```typescript
// src/utils/sse.ts

interface SSESendOptions {
  /** 查询参数，拼接到 URL */
  params?: Record<string, string>
  /** 每次流式数据到达时回调（接收已收到的完整文本） */
  onChunk?: (text: string) => void
  /** 取消信号 */
  signal?: AbortSignal
}

function readSSE(url: string, options?: SSESendOptions): Promise<string>
```

### 4.2 `agentApi.chatStream(content, onChunk)`

```typescript
// src/api/agent.ts

const agentApi = {
  /** 普通模式：单次请求，返回完整响应 */
  chat: (content: string) =>
    request.post<ApiResponse<string>>('/string/send', null, { params: { content } }),

  /** 流式模式：支持 SSE / 普通 JSON 双模 */
  chatStream: (content: string, onChunk?: (partial: string) => void) =>
    readSSE('/string/send', { params: { content }, onChunk }),
}
```

## 5. 调用方集成

```typescript
// AiChat.vue — parseIntent
const placeholder: ChatMessage = { role: 'assistant', type: 'text', content: '' }
messages.value.push(placeholder)

try {
  const fullText = await agentApi.chatStream(text, (partial) => {
    placeholder.content = partial
    messages.value = [...messages.value]  // 触发响应式
  })
  placeholder.content = fullText
  // 解析 fullText 为 JSON → 得到 intent
} catch {
  // 降级到普通 API 或 mock
}
```

## 6. 边界情况

| 场景 | 处理 |
|------|------|
| 后端返回普通 JSON | `Content-Type` 不含 `text/event-stream` → 一次读完整 body |
| 后端返回 SSE 但不含 `[DONE]` | 流结束后自然 resolve |
| SSE 行不完整（跨 chunk 分割） | 用 buffer 暂存未完成的行，下个 chunk 续接 |
| `response.body` 为 null | 回退到 `response.text()` |
| 请求被 cancel | `AbortController` → fetch 抛异常 → catch 走降级 |
| 流式 parse 失败或超时 | catch 降级到普通 API |
| `onChunk` 回调频率 | 每次抵达新的 `data:` 行触发，非逐字（节省渲染压力） |

## 7. 向后兼容

| 后端当前 | 前端处理 | 用户感知 |
|----------|----------|----------|
| 返回普通 JSON `Result` | 走非 SSE 分支，一次展示 | 无变化 |
| 返回 `text/event-stream` 流 | 走 SSE 分支，逐块展示 | 打字机效果 |
| 返回乱码或错误 | catch 降级到普通 `chat()` | 无变化 |

## 8. 审核发现（2026-07 审查）

> 以下问题基于文档 + 现有代码（`sse.ts`、`agent.ts`、`AiChat.vue`、`request.ts`）审计得出。

### 8.1 🔴 P0 — SSE 数据负载格式未定义（核心设计缺失）

**问题**：文档未明确 SSE `data:` 行传输的数据格式。当前 `parseIntent` 期望 `fullText` 是一个 JSON 字符串（`JSON.parse(fullText)` 得到 `{ query, type }`）。但 SSE 模式通常逐字输出 AI 聊天文本（自然语言），而不是 JSON。

**影响**：如果后端 SSE 流输出的是纯自然语言文本，则 `JSON.parse(fullText)` **必然失败**，SSE 路径永远无法成功解析 intent，永远走降级路径。核心功能失效。

**需要前后端对齐**：

| 方案 | SSE data 内容 | 前端处理 | 可行性 |
|------|--------------|---------|--------|
| A: SSE 流先输出 JSON 结构（一段内完成），再开始打字机 | `data: {"query":"date","type":"scene"}\ndata: [DONE]` | 累积后 JSON.parse，成功则跳过打字机 | ✅ 兼容，但无打字机效果 |
| B: SSE 流输出纯文本，前端用另一机制获得结构化结果 | `data: 我觉得...`（自然语言） | JSON.parse 预期失败，展示文本而非触发推荐 | ❌ 功能缺失 |
| C: SSE 流输出的 **本身就是 JSON 字符串**（逐段） | `data: {"que` → `data: ry":"` → `data: date`... | 累积后 JSON.parse | ❌ 逐段切分 JSON 字符串会破坏语法，累积前无法 parse，且片段本身无意义 |
| D: SSE 流最后一帧携带完整 JSON，前面是展示用文本 | `data: 正在思考...\ndata: {"query":"date","type":"scene"}\ndata: [DONE]` | 先展示文本，遇到 JSON 行再 parse | ✅ 需要前端识别 data 行是纯文本还是 JSON |

**建议**：**方案 D** 或类似"展示文本 + 尾帧结构化数据"的方案。前端需在 SSE 解析中区分 "展示用文本" 和 "结构化数据"，在最后 `[DONE]` 前识别 JSON 行并解析 intent。

> **最小约定（必须对齐）**：无论最终选择哪种方案，文档必须明确定义 SSE `data:` 的负载格式，包括：① 逐字/逐段的边界规则；② 结构化数据以何种格式在何时出现；③ 普通 JSON 回退路径下 `ApiResponse.data` 的取值规则与 SSE 路径的 `fullText` 取值规则的对应关系。

### 8.2 🔴 P0 — 原生 fetch 未携带认证凭据

**问题**：`readSSE` 使用原生 `fetch`，不经过 axios 拦截器，导致：

1. **缺少 `authorization` 请求头**：`request.ts` 的请求拦截器会从 `localStorage` 读取 access_token 并写入 `authorization` 头，但原生 fetch 不走这个拦截器。SSE 请求将因未携带 token 被后端 401 拒绝。
2. **缺少 `credentials: 'include'`**：`request.ts` 设置了 `withCredentials: true`，确保 httpOnly Cookie（Refresh-Token）随请求发送。原生 fetch 默认 `credentials: 'same-origin'`，跨域时不会携带 Cookie。

**影响**：**所有 SSE 请求都会认证失败**（401），回退到降级路径，SSE 功能形同虚设。

**修复**（`src/utils/sse.ts`）：

```typescript
const response = await fetch(fullUrl, {
  method: 'POST',
  headers: {
    'Accept': 'text/event-stream',
    'authorization': `Bearer ${localStorage.getItem('access_token') || ''}`,
  },
  credentials: 'include',       // ← 关键：携带 httpOnly Cookie
  signal,
})
```

### 8.3 🟠 P1 — `[DONE]` 标记只跳出了内层 `for` 循环

**问题**（`src/utils/sse.ts:77`）：

```typescript
if (data === '[DONE]') break   // 只跳出 for 循环，while 循环继续
```

`break` 仅跳出 `for (const line of lines)`，`while (true)` 循环继续执行下一次 `reader.read()`。如果服务端在 `[DONE]` 之后**没有关闭流**，`reader.read()` 会挂起等待，导致请求无法 resolve。

**修复**：

```typescript
if (data === '[DONE]') return fullText
```

或使用带标签的 break 跳出外层循环。

### 8.4 🟠 P1 — 未检查 HTTP 错误状态码

**问题**：`readSSE` 在 `fetch` 之后没有检查 `response.ok` / `response.status`。如果后端返回 4xx/5xx：

- 若 Content-Type 恰好包含 `text/event-stream` → 尝试将错误页面解析为 SSE 数据，产生垃圾内容
- 若 Content-Type 不包含 `text/event-stream` → 走非 SSE 分支，可能将错误页面的 HTML 传给 `onChunk`

**修复**：在 Content-Type 判断之前增加：

```typescript
if (!response.ok) {
  throw new Error(`SSE request failed: ${response.status}`)
}
```

### 8.5 🟡 P2 — 未处理 `\r\n` 换行符

**问题**：SSE 规范允许 `\n` 和 `\r\n` 两种换行格式。当前按 `\n` 分割行后，如果后端使用 `\r\n`，每个 `data:` 值末尾会残留 `\r` 字符。

修复示例：

```typescript
const data = line.slice(6).replace(/\r$/, '')
```

### 8.6 🟡 P2 — 尾部缓冲区未检查 `[DONE]` 标记

**问题**：流结束后处理缓冲区的剩余 `data:` 行时（`sse.ts:85-88`）：

```typescript
if (buffer.startsWith('data: ')) {
  fullText += buffer.slice(6)
```

没有判断是否为 `[DONE]`，如果 `[DONE]` 正好落在缓冲区末尾，会被当作普通文本加入到 `fullText`。

**修复**：

```typescript
if (buffer.startsWith('data: ')) {
  const data = buffer.slice(6).replace(/\r$/, '')
  if (data !== '[DONE]') {
    fullText += data
    if (onChunk) onChunk(fullText)
  }
}
```

### 8.7 🟡 P2 — SSE 路径与普通路径的返回结构不一致

**问题**：两条路径在 `parseIntent` 中的数据结构不同：

| 路径 | 返回数据 | 在 parseIntent 的使用方式 |
|------|---------|------------------------|
| SSE (`chatStream` → `readSSE`) | 原始字符串（`fullText`） | 直接 `JSON.parse(fullText)` |
| 普通 (`chat`) | `ApiResponse<string>` (`res.data.data`) | 从 `res.data?.data` 取字符串再 `JSON.parse` |

如果后端 SSE 模式也发送 `ApiResponse` 信封，两者的解析逻辑需要统一。但目前文档未约定 SSE 模式下 `data:` 内容是否包含 `ApiResponse` 信封。

### 8.8 🟡 P2 — 缺少 SSE 错误响应格式约定

**问题**：文档未定义后端在 SSE 模式下如何返回业务错误（如参数校验失败、AI 服务超时）。SSE 模式下，HTTP 状态码仍然是 200（流式响应），业务错误需要通过 SSE 事件帧传递。

**建议**：约定当 SSE 流中出现 `data:` 内容为 JSON 且含 `error` 字段时，前端应将其识别为错误并 reject：

```
data: {"error": "AI 服务超时", "code": 5001}
data: [DONE]
```

### 8.9 🔵 P3 — `onChunk` 实际未做节流

**问题**：文档 §6 边界情况声称 "每次抵达新的 `data:` 行触发，非逐字（节省渲染压力）"。但分析 `parseIntent` 中的回调：

```typescript
(partial) => {
  placeholder.content = partial
  messages.value = [...messages.value]    // 每次触发全量 diff
  nextTick(() => { ... scrollBottom })   // 每次触发 DOM 操作
}
```

如果后端以高频率发送 SSE 事件（如逐字输出，假设每 20ms 一个字符），上述操作会导致频繁的 Vue 响应式 diff + 滚动，在低端设备上可能出现卡顿。而文档所描述的"非逐字"实际上并未在代码中实现任何节流逻辑。

**建议**：在 `readSSE` 或 `AiChat.vue` 中增加节流（如 requestAnimationFrame 或 100ms 窗口合并），避免高频 SSE 事件引发渲染抖动。也可保持现状但文档需如实描述：**"每次 data: 行均触发回调，高频场景由 Vue 异步更新队列合并渲染"**。
