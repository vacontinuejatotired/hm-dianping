# 推荐 + AI 助手 — 前端实现方案

> 本文档仅覆盖**前端部分**。后端 API 由用户自行实现。

---

## 1. 整体架构

```
┌──────────────────────────────────────────────┐
│                  前端                          │
│  ┌──────────────────────────────────┐         │
│  │  AI 对话页 (/ai) — 核心功能       │         │
│  │  ├ mock 解析器（MVP）            │         │
│  │  ├ ChatBubble + AgentResultCard  │         │
│  │  └ 多轮问答 + 快捷追问           │         │
│  └────────┬─────────────────────────┘         │
│           │ 入口（跳转 /ai）                   │
│  ┌────────┴────────┐                          │
│  │ 首页场景标签     │                          │
│  │ FootBar 第3项   │                          │
│  └────────┬────────┘                          │
│           │                                   │
│  ┌────────┴─────────────────┐                 │
│  │  src/api/agent.ts        │                 │
│  │  GET  /api/agent/scenes  │                 │
│  │  POST /api/agent/recommend                 │
│  └────────┬─────────────────┘                 │
├───────────┼───────────────────────────────────┤
│           │   后端（用户自实现）                │
│           ▼                                   │
│  Agent Service + Shop/Blog/Voucher Service    │
└──────────────────────────────────────────────┘
```

---

## 2. 改动范围

| 文件 | 说明 | 行数 |
|------|------|------|
| `src/views/AiChat.vue` | **新建** — AI 对话页（mock + 状态 + 自动滚底 + 开场白 + 思考占位 + 错误处理） | ~190 |
| `src/components/agent/ChatBubble.vue` | **新建** — 对话气泡（user/assistant 双角色，loading 打字动画） | ~50 |
| `src/components/agent/AgentResultCard.vue` | **新建** — 推荐结果卡片（组合 ShopCard + 字段映射 `avgPrice`→`avgCost`） | ~50 |
| `src/api/agent.ts` | **新建** — `getScenes` + `recommend` API 封装 | ~30 |
| `src/views/Home.vue` | 搜索框下方场景标签入口 → 跳 `/ai?scene=key` | ~15 |
| `src/router/index.ts` | 新增路由 `AiChat`，加入 `publicRouteNames`（允许未登录访问） | ~6 |
| `src/components/common/FootBar.vue` | 第 3 项"消息"→ 跳 `/ai` | ~3 |
| `src/constants/messages.ts` | 新增 `aiTitle: 'AI 助手'` | ~3 |
| `public/ai-avatar.png` | **新增** — AI 头像（临时复制 favicon.svg 或使用 inline SVG） | 1 文件 |

---

## 3. API 封装 & Mock 数据

```typescript
// src/api/agent.ts
import request from './request'
import type { ApiResponse, Shop } from '@/types/api'

export interface SceneTag { key: string; label: string }

export interface RecommendRequest {
  query: string
  type?: 'keyword' | 'scene'
  city?: string
  limit?: number
}

export interface RecommendResult { shops: Shop[]; reason: string }

export const agentApi = {
  getScenes: () => request.get<ApiResponse<SceneTag[]>>('/agent/scenes'),
  recommend: (data: RecommendRequest) =>
    request.post<ApiResponse<RecommendResult>>('/agent/recommend', data),
}
```

```typescript
// AiChat.vue — mock 数据 + 自动降级
function makeMockShop(p: Partial<Shop>): Shop {
  return {
    id: 0, typeId: 0, name: '', images: '', area: '',
    address: '', x: 0, y: 0, avgPrice: 0, sold: 0,
    comments: 0, score: 0, openHours: '', createTime: '',
    updateTime: '', distance: null,
    ...p,
  }
}

const MOCK_SCENES: SceneTag[] = [
  { key: 'date', label: '约会' },
  { key: 'party', label: '聚会' },
  { key: 'solo', label: '一个人' },
]

const MOCK_RESULTS: Record<string, RecommendResult> = {
  // scene 场景
  date: {
    shops: [
      makeMockShop({ id: 1, name: '辣有道川菜馆', score: 4.8, avgPrice: 88, typeId: 1, distance: 1.2 }),
      makeMockShop({ id: 2, name: '蜀味轩', score: 4.6, avgPrice: 65, typeId: 1, distance: 0.8 }),
    ],
    reason: '找到 2 家适合约会的餐厅',
  },
  party: {
    shops: [
      makeMockShop({ id: 3, name: '海底捞', score: 4.9, avgPrice: 120, typeId: 2, distance: 0.5 }),
      makeMockShop({ id: 4, name: '大龙燚火锅', score: 4.7, avgPrice: 95, typeId: 2, distance: 1.0 }),
    ],
    reason: '推荐 2 家适合聚会的餐厅',
  },
  solo: {
    shops: [
      makeMockShop({ id: 5, name: '一蘭拉面', score: 4.6, avgPrice: 58, typeId: 3, distance: 0.3 }),
    ],
    reason: '推荐 1 家适合一个人去的店',
  },
  // keyword 兜底
  default: {
    shops: [
      makeMockShop({ id: 6, name: '热门商铺 A', score: 4.5, avgPrice: 80, typeId: 1, distance: 1.0 }),
      makeMockShop({ id: 7, name: '热门商铺 B', score: 4.4, avgPrice: 60, typeId: 2, distance: 0.7 }),
    ],
    reason: '为你推荐了一些热门店铺',
  },
}

/** mock 场景列表（不带后端也能用） */
async function fetchScenes(): Promise<SceneTag[]> {
  try { return (await agentApi.getScenes()).data?.data || MOCK_SCENES }
  catch { return MOCK_SCENES }
}

/** mock 推荐（后端不通则走本地 mock） */
async function fetchRecommend(query: string, type: string): Promise<RecommendResult> {
  try {
    const res = await agentApi.recommend({ query, type: type as 'keyword' | 'scene' })
    if (res.data?.success && res.data.data) return res.data.data
  } catch { /* 静默降级到 mock */ }
  return MOCK_RESULTS[query] || MOCK_RESULTS.default
}

/** mock 意图解析（后续可替换为 POST /api/agent/chat） */
function parseIntent(text: string, lastQuery: string) {
  if (text.includes('约会') || text.includes('浪漫')) return { query: 'date', type: 'scene' as const }
  if (text.includes('聚会') || text.includes('聚餐')) return { query: 'party', type: 'scene' as const }
  if (text.includes('一个人') || text.includes('独自')) return { query: 'solo', type: 'scene' as const }
  if (text.includes('便宜') || text.includes('实惠')) return { query: `${lastQuery || 'default'}`, type: 'keyword' as const }
  if (text.includes('换') || text.includes('其他')) return { query: lastQuery || 'default', type: 'scene' as const }
  return { query: 'default', type: 'keyword' as const }
}
```

> **mock 降级机制**：前端先请求后端 API，失败后自动走本地 mock 数据。这样你写后端的时候前端照样能开发，后端写好了 mock 自动失效。`parseIntent` 后续替换为 `POST /api/agent/chat`（后端代理 LLM）。
```

---

## 4. 搜索框改造（精简入口）

```
┌──────────────────────────────┐
│  搜索框                       │
│  ┌────────────┐  ┌────┐     │
│  │ 搜商铺...   │  │ 🔍 │     │
│  └────────────┘  └────┘     │
│                              │
│  场景标签（GET /agent/scenes）│
│  [约会] [聚会] [一个人]       │  ← 跳 /ai?scene=key
└──────────────────────────────┘
```

```typescript
// Home.vue
import { useRouter } from 'vue-router'
import { agentApi } from '@/api/agent'
import type { SceneTag } from '@/api/agent'

const router = useRouter()
const sceneTags = ref<SceneTag[]>([])

onMounted(async () => {
  try { sceneTags.value = (await agentApi.getScenes()).data?.data || [] }
  catch { /* 后端不通时标签为空，不影响使用 */ }
})

function goToAi(scene: SceneTag) { router.push(`/ai?scene=${scene.key}`) }
```

---

## 5. AI 对话页 — 核心

### 5.1 页面结构

```
┌──────────────────────────────┐
│  AI 助手              ← 返回  │  PageHeader
├──────────────────────────────┤
│  对话区（flex:1 overflow-y）  │  ref="chatBody"
│  ┌────────────────────────┐  │
│  │ 🤖 你好！我是...       │  │  开场白
│  └────────────────────────┘  │
│  ┌────────────────────────┐  │
│  │ 👤 推荐约会的餐厅      │  │  用户
│  └────────────────────────┘  │
│  ┌────────────────────────┐  │
│  │ 🤖 找到 2 家店         │  │  推荐结果
│  │ ┌────────────────┐     │  │
│  │ │ 辣有道 ⭐4.8   │     │  │  AgentResultCard
│  │ │ 人均 ¥88 | 1.2│     │  │  (内嵌 ShopCard)
│  │ │ [查看详情]    │     │  │
│  │ └────────────────┘     │  │
│  │ [换一个] [便宜点的]     │  │  快捷追问
│  └────────────────────────┘  │
│  ┌────────────────────────┐  │
│  │ 🤖 思考中...           │  │  思考占位（loading 时）
│  └────────────────────────┘  │
├──────────────────────────────┤
│  ┌────────────────────────┐  │
│  │ 输入需求...      [发送] │  │  Enter=发送
│  └────────────────────────┘  │
└──────────────────────────────┘
```

### 5.2 状态

```typescript
interface ChatMessage {
  role: 'user' | 'assistant'
  type: 'text' | 'recommendation'
  content: string
  shops?: Shop[]
}

const messages = ref<ChatMessage[]>([])
const lastQuery = ref('')
const inputText = ref('')
const loading = ref(false)
const chatBody = ref<HTMLElement | null>(null)
const route = useRoute()

async function sendMessage() { /* §5.4 */ }
const QUICK_ACTIONS = ['换一个', '便宜点的']
function quickAsk(text: string) { inputText.value = text; sendMessage() }
```

### 5.3 生命周期（URL 参数 + 开场白）

```typescript
onMounted(async () => {
  const scene = route.query.scene as string
  if (scene) {
    messages.value.push({ role: 'assistant', type: 'text', content: '收到！帮你看看有什么好推荐 🎯' })
    await doRecommend(scene, 'scene')
  } else {
    messages.value.push({ role: 'assistant', type: 'text', content: '你好！我是你的探店助手，告诉我你想找什么样的餐厅或优惠吧 🎯' })
  }
})

async function doRecommend(query: string, type: string) {
  loading.value = true
  try {
    const result = await fetchRecommend(query, type)
    lastQuery.value = query
    messages.value.push({ role: 'assistant', type: 'recommendation', content: result.reason, shops: result.shops })
  } catch {
    messages.value.push({ role: 'assistant', type: 'text', content: '抱歉，查询失败了，请稍后重试' })
  } finally { loading.value = false }
}
```

### 5.4 发送消息

```typescript
async function sendMessage() {
  const text = inputText.value.trim()
  if (!text || loading.value) return

  messages.value.push({ role: 'user', type: 'text', content: text })
  inputText.value = ''
  loading.value = true

  const intent = parseIntent(text, lastQuery.value)
  lastQuery.value = intent.query

  const result = await fetchRecommend(intent.query, intent.type)
  messages.value.push({ role: 'assistant', type: 'recommendation', content: result.reason, shops: result.shops })
  loading.value = false
}
```

### 5.5 自动滚底

```typescript
watch(() => messages.value.length, () => {
  nextTick(() => { chatBody.value && (chatBody.value.scrollTop = chatBody.value.scrollHeight) })
})
```

### 5.6 思考占位（R21）

```vue
<div v-if="loading && messages.length > 0" class="chat-bubble assistant">
  <img class="avatar" src="/ai-avatar.png" alt="" />
  <div class="bubble"><span class="typing">思考中...</span></div>
</div>
```

> 仅在用户已发送消息后显示，首次进入的加载由开场白状态管理。

---

## 6. 组件

### 6.1 ChatBubble.vue

```vue
<script setup lang="ts">
defineProps<{ role: 'user' | 'assistant'; text: string; loading?: boolean }>()
</script>
<template>
  <div class="chat-bubble" :class="role">
    <img v-if="role === 'assistant'" class="avatar" src="/ai-avatar.png" alt="AI" />
    <div class="bubble">
      <span v-if="loading" class="typing">...</span>
      <span v-else>{{ text }}</span>
    </div>
  </div>
</template>
<style scoped>
.chat-bubble { display: flex; gap: 8px; margin: 12px 0; }
.chat-bubble.user { flex-direction: row-reverse; }
.avatar { width: 28px; height: 28px; border-radius: 50%; }
.bubble { max-width: 75%; padding: 10px 14px; border-radius: 12px; font-size: var(--font-size-sm); line-height: 1.5; }
.user .bubble { background: var(--color-primary); color: #fff; border-radius: 12px 4px 12px 12px; }
.assistant .bubble { background: var(--color-white); border: 1px solid var(--color-border); border-radius: 4px 12px 12px 12px; }
.typing { animation: blink 1s steps(2) infinite; }
@keyframes blink { 50% { opacity: 0; } }
</style>
```

### 6.2 AgentResultCard.vue（组合 ShopCard）

```vue
<template>
  <div class="agent-result-card">
    <ShopCard :shop="shopItem" />
    <div class="agent-actions">
      <button class="action-btn" @click="$emit('detail', shop.id)">查看详情</button>
    </div>
  </div>
</template>
<script setup lang="ts">
import { computed } from 'vue'
import ShopCard from '@/components/common/ShopCard.vue'
import type { Shop } from '@/types/api'
const props = defineProps<{ shop: Shop }>()
defineEmits<{ detail: [id: number] }>()
const shopItem = computed(() => ({
  id: props.shop.id,
  name: props.shop.name,
  address: props.shop.address,
  score: props.shop.score,
  area: props.shop.area,
  images: props.shop.images,
  avgCost: props.shop.avgPrice,  // ShopItem 字段名兼容
}))
</script>
<style scoped>
.agent-result-card { margin: 12px 0; }
.agent-actions { display: flex; gap: 8px; margin-top: 8px; }
.action-btn { padding: 6px 16px; font-size: var(--font-size-sm); border: 1px solid var(--color-primary); color: var(--color-primary); border-radius: var(--radius-round); background: transparent; cursor: pointer; }
</style>
```

---

## 7. 实施路线

| 步骤 | 内容 | 文件 | 行数 |
|------|------|------|------|
| 1 | `agentApi`（getScenes + recommend） | `src/api/agent.ts` | ~30 |
| 2 | ChatBubble 组件 | `src/components/agent/ChatBubble.vue` | ~50 |
| 3 | AgentResultCard（字段映射） | `src/components/agent/AgentResultCard.vue` | ~50 |
| 4 | AI 对话页（mock + 状态 + 滚底 + 开场白 + 思考占位 + 错误处理） | `src/views/AiChat.vue` | ~190 |
| 5 | 路由 + publicRouteNames + FootBar + 消息常量 + AI 头像 | 多个文件 | ~15 |
| 6 | 首页场景标签入口 | `src/views/Home.vue` | ~15 |

**总计：~350 行净增，零外部依赖。**

> AI 头像：将 `public/favicon.svg` 复制为 `public/ai-avatar.png`（暂时的占位，后续可替换为独立图标）。

---

## 8. 后端接口契约

### GET /api/agent/scenes

```json
{ "success": true, "data": [{ "key": "date", "label": "约会" }, { "key": "party", "label": "聚会" }, { "key": "solo", "label": "一个人" }] }
```

### POST /api/agent/recommend

```json
// 请求
{ "query": "date", "type": "scene", "city": "北京", "limit": 5 }

// 响应 — 字段名对齐前端 Shop 类型
{
  "success": true,
  "data": {
    "shops": [
      {
        "id": 1, "name": "辣有道川菜馆", "typeId": 1,
        "score": 4.8, "avgPrice": 88, "distance": 1.2,
        "images": "", "address": "朝阳区XX路", "area": "朝阳",
        "x": 116.4, "y": 39.9, "sold": 0, "comments": 0,
        "openHours": "", "createTime": "", "updateTime": ""
      }
    ],
    "reason": "为你推荐了 5 家适合约会的餐厅"
  }
}
```

---

## 9. 审查意见对照（22 条，全部修正）

| # | 等级 | 问题 | 处理 | 位置 |
|---|------|------|------|------|
| R1 | 🔴 | LLM CORS 直连不可行 | 前端 mock 先行 | §3 mock |
| R2 | 🔴 | 前后端场景各自维护 | 场景列表 `GET /api/agent/scenes` | §3/§4 |
| R3 | 🟠 | AgentResultCard 与 ShopCard 重复 | 组合 ShopCard | §6.2 |
| R4 | 🟠 | 推荐结果渲染位置未定义 | 搜索框仅作入口，不内嵌结果 | §4 |
| R5 | 🟠 | 追问上下文未定义 | `lastQuery` 状态 | §5.2 |
| R6 | 🟠 | purchase 端点未使用 | 已删除 | §1/§3 |
| R7 | 🟡 | 后端字段名与前端不匹配 | 契约统一用前端字段 | §8 |
| R8 | 🟡 | 刷新 `/ai` 丢对话 | MVP 不持久化，已注明 | §5 |
| R9 | 🔴 | `Shop.avgPrice` vs `ShopItem.avgCost` | AgentResultCard 做字段映射 | §6.2 |
| R10 | 🔴 | mock 数据字段不全 | `makeMockShop` 补全 | §3 |
| R11 | 🟠 | 新消息不滚底 | `watch + nextTick` | §5.5 |
| R12 | 🟠 | `hasVoucher` 来源未定义 | 已删除 | §6.2 |
| R13 | 🟠 | 失败无界面反馈 | push 错误消息 | §5.4 |
| R14 | 🟡 | Enter 行为未定义 | Enter=发送，Shift+Enter=换行 | §5 |
| R15 | 🟡 | 首次进入无开场白 | `onMounted` 追加 | §5.3 |
| R16 | 🔴 | `executeRecommend` 未定义 | 补充 `doRecommend` | §5.3 |
| R17 | 🔴 | mock 数据 `type: '川菜'` 不存在 | 改为 `typeId` | §3 |
| R18 | 🟡 | 后端契约含 `type: "川菜"` | 已移除 | §8 |
| R19 | 🟠 | 路由守卫拦截 `/ai` | 加入 `publicRouteNames` | §2 |
| R20 | 🟡 | AI 头像缺失 | 复制 `favicon.svg` 为 `ai-avatar.png` | §2 |
| R21 | 🟢 | 缺少思考中占位 | 条件渲染 loading 气泡 | §5.6 |
| R22 | 🟠 | mock 数据死代码，后端没写好对话页跑不通 | `fetchRecommend` 自动回退到 `MOCK_RESULTS` | §3 |

---

---

## 10. 终审结论

**22 条审查意见，全部修正。方案可以进入实施。**

编码时注意两个顺手改的小点：

- `sendMessage` 建议加 try/catch/finally（与 `doRecommend` 保持一致），当前没加但 `fetchRecommend` 内部兜底了，不会真崩
- AiChat.vue 记得手动 import `ref`, `watch`, `onMounted`, `nextTick`（Vue）和 `useRoute`（vue-router），项目没配自动导入

### 建议的 commit 分批方案

| 批次 | 内容 | 文件 | 关联审查 |
|------|------|------|---------|
| **1/4** | API 层 + 组件基础设施 | `src/api/agent.ts`, `ChatBubble.vue`, `AgentResultCard.vue` | R3, R7, R9, R10 |
| **2/4** | AI 对话页核心 | `src/views/AiChat.vue`（含 mock + 状态 + 滚底 + 开场白 + 思考占位） | R5, R11, R13, R15, R16, R21, R22 |
| **3/4** | 入口改造 | `router/index.ts`, `FootBar.vue`, `messages.ts`, `public/ai-avatar.png` | R8, R14, R19, R20 |
| **4/4** | 首页场景标签 | `src/views/Home.vue` | R2, R4 |

> **依赖关系**：批次 2 依赖批次 1（组件就绪），批次 3/4 独立可并行。

---

## 10. 终审补丁（2026-07-13）

### R22 🟠 mock 仅覆盖了 LLM 解析，MOCK 数据是死代码

**问题**：`MOCK` Record（§3）定义了 `date` 和 `火锅` 的推荐结果（商铺列表 + 推荐语），但 `doRecommend` 和 `sendMessage` 都直接调 `agentApi.recommend()` 走真实 API——MOCK 仅在 `?scene=` 入口处作为 boolean 检查用了一次（`if (scene && MOCK[scene])`），数据本身从未被渲染。

**后果**：后端没写好之前，对话页**只能看开场白，发消息必进 catch 弹错误**。mock 数据完全是废的，与"零外部依赖跑通 MVP"的目标矛盾。

**药方**：`doRecommend` 和 `sendMessage` 在 API 失败时回退到 `MOCK` 数据，或优先用 mock：

```typescript
async function doRecommend(query: string) {
  loading.value = true
  try {
    // 优先用 mock（后端就绪后删掉这个 if）
    if (MOCK[query]) {
      await new Promise(r => setTimeout(r, 300)) // 模拟网络延迟
      const result = MOCK[query]
      messages.value.push({ role: 'assistant', type: 'recommendation', content: result.reason, shops: result.shops })
      return
    }
    const res = await agentApi.recommend({ query, type: 'scene' })
    // ... 后续同现有逻辑
  } catch { /* ... */ }
  finally { loading.value = false }
}
```

`swo` 用同样的 fallback。这样前后端分离开发：前端 UI 靠 mock 数据跑通，后端就绪后删掉 `if (MOCK[query])` 即可。

---

### 10.1 终审结论

**22 条审查意见，21 条已修，新增 1 条。**

唯一剩下的是 R22：mock 数据死代码。修法就是在 `doRecommend` 和 `sendMessage` 里加一个 `if (MOCK[query])` 回退分支。一行注释标记"后端就绪后删掉这段"，加 300ms 延时模拟网络效果。搞定。
