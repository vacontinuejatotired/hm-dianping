# Login 模块重构方案（v3）

> 本文档基于现有代码逐行分析 + 架构审查反馈，给出可落地的分阶段重构计划。
>
> 最后更新：2026-06

---

## 1. 现状全景

### 1.1 登录认证调用链路

```
┌─ 浏览器 ─────────────────────────────────────────────────┐
│                                                          │
│  1. POST /user/code?phone=xxx                            │
│  2. POST /user/login { phone, code }  ← 得到 Token       │
│  3. 后续请求 Header: { authorization, Refresh-Token }     │
│                                                          │
└──────────────────────┬───────────────────────────────────┘
                       │
┌─ RefreshTokenInterceptor (order=0, 拦截所有) ─────────────┐
│  ① checkTokenHeader()    ← 检查请求头中两个Token是否存在   │
│  ② JWT 解码 + Claims 提取                                 │
│  ③ 版本号两级校验（Caffeine 快速拒绝 → Redis 最终校验）     │
│  ④ 判断是否临期 → 临期则 Lua 刷新 Token                    │
│  ⑤ 过期则尝试 refreshExpiredToken()                       │
│  ⑥ 设置 UserHolder（userId + userDTO）                    │
│  ⑦ finally: 写回响应头 + 性能日志                          │
└──────────────────────┬───────────────────────────────────┘
                       │
┌─ LoginInterceptor (order=1, 仅拦截需登录接口) ────────────┐
│  检查 UserHolder.getUserId() == null → 401               │
└──────────────────────────────────────────────────────────┘
```

### 1.2 UserServiceImpl.login() 职责分解

当前 `login()` 方法（第69-152行）包含 **9 项独立职责**：

| 步骤 | 代码行 | 职责 | 问题 |
|------|--------|------|------|
| ① | 70-74 | 手机号格式校验 | 应前置在 Controller 或 DTO 校验 |
| ② | 75-78 | 从 Redis 取验证码并比对 | ⚠️ **没删除验证码，可重复使用** |
| ③ | 80-95 | 查用户 → 不存在则自动创建 | 创建用户不应放在登录方法中 |
| ④ | 96 | 用雪花ID生成 version | 属于 Token 生成流程 |
| ⑤ | 97 | JWT 签名生成 AccessToken | 属于 Token 生成流程 |
| ⑥ | 98-108 | 用户信息写入 Redis Hash | 属于用户缓存逻辑 |
| ⑦ | 110-117 | 组装 Lua 参数列表 | 属于 Token 存储逻辑 |
| ⑧ | 118-141 | 执行 Lua 脚本写入 Redis | ⚠️ **Lua 返回异常静默吞掉，未返回错误** |
| ⑨ | 142-151 | 组装返回 Map | Token 通过**响应体**返回，与拦截器不一致 |

### 1.3 Token 传递方式不统一

| 场景 | 传递方式 | 详情 |
|------|---------|------|
| 登录接口返回 Token | **响应体** | `Result.ok({ token: "...", refreshToken: "..." })` |
| Token 刷新后返回新 Token | **响应头** | `response.setHeader("authorization", newToken)` |
| 前端发送 Token | **请求头** | `request.getHeader("authorization")` |

> **后果**：前端登录时要从响应体取 Token，后续又要从响应头取——两套逻辑。

### 1.4 测试代码混入生产 Service

`UserServiceImpl` 中包含约 200 行的测试代码：

- `TOKEN_LIST`, `PHONE_LIST`, `REFRESHTOKEN_LIST` 三个静态列表
- `generateTestTokenAndRefreshToken()` — 批量生成 Token（含 Redis pipeline）
- `exportTokenAndRefreshTokenToCsv()` — 导出到 CSV
- `generateTestPhone()`, `getTokenList()`, `getPhoneList()` 辅助方法
- `IUserService` 接口中的 `exportTokenAndRefreshTokenToCsv()`

### 1.5 其他问题

| 问题 | 位置 | 说明 |
|------|------|------|
| `HttpSession` 未使用 | Controller/Service | 项目早已全面 Token 化，session 参数是遗留物 |
| `logout` 空实现 | Controller | 返回 `"功能未完成"` |
| 密码登录未实现 | LoginFormDTO/Service | `password` 字段存在但 Service 从不使用 |
| `GET /user/{id}` 无缓存+无权限 | Controller | 任何人都可遍历 userId，且无缓存 |
| `GET /user/info/{id}` 无权限 | Controller | 返回敏感字段（生日、性别），无权限控制 |
| 验证码可重复使用 | login() | GET 后没 DEL，同一验证码可多次登录 |
| 验证码发送无频率限制 | sendCode() | 可被脚本无限调用 |
| 拼写错误 | 多处 | `remaningTime`, `valiateAndGetClaimFromToken`, `fileName = fileName;` |
| 拦截器 20000+ 字节 | RefreshTokenInterceptor | Token 校验+刷新+缓存+日志全部内联 |
| Caffeine `get()` 阻塞 | 拦截器 | 使用 `get()` 而非 `getIfPresent()`，Redis 抖动时会同步穿透 |

---

## 2. 目标架构

### 2.1 新增 AuthService + AuthController

```
┌─ AuthController (新增) ──┐    ┌─ UserController ──────────┐
│  POST /auth/login        │    │  POST /user/code          │
│  POST /auth/refresh      │    │  GET  /user/me            │
│  POST /auth/logout       │    │  GET  /user/{id}          │
└──────────┬───────────────┘    │  GET  /user/info/{id}    │
           │                    │  POST /user/sign          │
           │                    │  GET  /user/sign/count    │
           │                    └───────────────────────────┘
           ▼
┌─ AuthServiceImpl (新增) ──────────────────────────────────┐
│  generateTokenPair(userId)    → TokenPair                 │
│  validateAccessToken(token)   → ValidationResult          │
│  refreshTokenPair(refresh)    → TokenPair                 │
│  revokeTokens(userId)         → void     ← logout         │
│  consumeVerifyCode(phone,code)→ boolean  ← 原子 GET+DEL   │
└───────────────────────────────────────────────────────────┘
```

### 2.2 AuthService 接口定义

```java
// 新增 DTO
public class TokenPair {
    private String accessToken;
    private String refreshToken;
    private Long version;
}

public class ValidationResult {
    private boolean valid;
    private Long userId;
    private Long version;
    private boolean needsRefresh;
}

// 新增接口
public interface AuthService {
    /** 登录：生成双Token + version，通过响应头写回 */
    TokenPair generateTokenPair(Long userId, HttpServletResponse response);

    /** 校验 Access Token + 版本号 */
    ValidationResult validateAccessToken(String token);

    /** 刷新双Token（临期或过期） */
    TokenPair refreshTokenPair(String refreshToken, HttpServletResponse response);

    /** 登出：删除 Redis 中该用户的所有 Token/Version */
    void revokeTokens(Long userId);

    /** 原子消费验证码：GET + DEL，防止重放 */
    boolean consumeVerifyCode(String phone, String code);

    /** 缓存用户信息到 Redis Hash */
    void cacheUserInfo(UserDTO userDTO);
}
```

### 2.3 拦截器目标形态

```
RefreshTokenInterceptor (瘦身):
  preHandle() {
    ① checkTokenHeader()
    ② AuthService.validateAccessToken(token)
          ├─ JWT 解码 + 版本提取
          ├─ Caffeine 快速拒绝 (getIfPresent, 不阻塞)
          └─ Redis 最终校验
    ③ if (临期/过期) AuthService.refreshTokenPair(refreshToken, response)
    ④ 设置 UserHolder
    ⑤ 响应头写回 accessToken / refreshToken
  }
  // 性能日志交给 @RecordTime 切面
```

---

## 3. 分阶段实施计划

### Phase 0 — 紧急修复（安全漏洞，立即执行）

| # | 操作 | 文件 | 风险/收益 |
|---|------|------|-----------|
| 0.1 | **实现 logout**：删除 Redis 中 accessToken + refreshToken + version 三个 key | UserController + UserServiceImpl | 🔴 关掉最高危漏洞，10 分钟 |
| 0.2 | **验证码原子消费**：新建 Lua 脚本 `ConsumeVerifyCode.lua`，GET + DEL 原子执行，防止同一验证码重复登录 | UserServiceImpl + `resources/ConsumeVerifyCode.lua` | 🔴 关掉重放攻击，30 分钟 |
| 0.3 | **验证码发送频率限制**：同一手机号 60 秒内不得重复发送 | UserServiceImpl.sendCode() | 🟠 防止短信接口滥用 |
| 0.4 | **移除 HttpSession**：搜索 `HttpSession session`，全部删除 | UserController + IUserService + UserServiceImpl | 清理遗留参数 |
| 0.5 | **删除被注释的 Dead Code**：AiService, AiServiceImpl, VoucherOrderServiceImpl | service/ + impl/ | 清理死代码 |
| 0.6 | **修复拼写错误** | RefreshTokenInterceptor:165 `remaningTime`→`remainingTime`；JwtUtil:181 `valiateAndGetClaimFromToken`→`validateAndGetClaimFromToken`；UserServiceImpl:403 `fileName = fileName;` 删除 | 消除编译警告 |

### Phase 1 — 测试代码剥离 + Token 约定统一

| # | 操作 | 说明 |
|---|------|------|
| 1.1 | 新建 `utils/TokenTestUtil.java` | 迁移 `TOKEN_LIST`, `PHONE_LIST`, `REFRESHTOKEN_LIST`, 所有测试方法 |
| 1.2 | `IUserService` 移除 `exportTokenAndRefreshTokenToCsv()` | 接口只保留生产方法 |
| 1.3 | `UserServiceImpl` 删除测试代码 | 清理约 200 行 |
| 1.4 | 更新 `TestServiceImpl` 调用 | 改为调用 `TokenTestUtil` |
| 1.5 | **统一 Token 约定**：登录接口改为通过**响应头**返回 Token | UserController.login() + UserServiceImpl.login() 改为 `response.setHeader("authorization", token)` + `response.setHeader("Refresh-Token", refreshToken)`，**不再通过响应体返回** |
| 1.6 | 同步更新 `前端开发文档.md` | Token 获取方式改为 `response.headers.get('authorization')` |

### Phase 2 — 抽取 AuthService

| # | 操作 | 文件 | 说明 |
|---|------|------|------|
| 2.1 | 新建 `dto/TokenPair.java` | dto/ | 含 accessToken, refreshToken, version |
| 2.2 | 新建 `dto/ValidationResult.java` | dto/ | 含 valid, userId, version, needsRefresh |
| 2.3 | 新建 `service/AuthService.java` | service/ | 接口定义（5 个方法） |
| 2.4 | 新建 `service/impl/AuthServiceImpl.java` | service/impl/ | 实现 Token 生成/校验/刷新/验证码消费/用户缓存 |
| 2.5 | 修改 `UserServiceImpl.login()` | UserServiceImpl | 简化为：①校验手机号→②消费验证码→③查/创建用户→④调 AuthService.generateTokenPair()→⑤返回 |
| 2.6 | **修复 Lua JSON 解析** | AuthServiceImpl | Lua 返回 null 或 code != 1 时返回错误，不静默吞掉 |
| 2.7 | **Caffeine 改为 getIfPresent** | RefreshTokenInterceptor | 避免 Redis 抖动时同步阻塞 Tomcat 线程 |

### Phase 3 — 权限 + 缓存增强

| # | 操作 | 说明 |
|---|------|------|
| 3.1 | `GET /user/{id}` 增加权限校验 | 仅当前登录用户（`UserHolder.getUserId() == id`）可查，否则 403 |
| 3.2 | `GET /user/info/{id}` 增加权限校验 | 同上，保护 `gender`, `birthday` 等敏感字段 |
| 3.3 | `GET /user/{id}` 增加缓存 | 参考 ShopServiceImpl 的 CacheClient 模式，减少数据库查询 |
| 3.4 | 实现密码登录 | `password` 字段逻辑：注册时 bcrypt 加密存入，登录时比对 |

### Phase 4 — 拦截器瘦身

| # | 操作 | 说明 |
|---|------|------|
| 4.1 | Token 解析+版本校验委托给 `AuthService.validateAccessToken()` | 替代内联 JWT 解析 + 两级缓存校验逻辑 |
| 4.2 | 刷新逻辑委托给 `AuthService.refreshTokenPair()` | 替代 `refreshDeadlineToken()` + `refreshExpiredToken()` |
| 4.3 | 性能日志移入 `@RecordTime` 切面 | 已有注解，拦截器 finally 块中移除 |
| 4.4 | 响应头写回封装为工具方法 | `AuthService.writeTokensToResponse(response, tokenPair)` |

---

## 4. 文件变更清单

| 操作 | 文件 | Phase |
|------|------|-------|
| 🆕 新建 | `service/AuthService.java` | P2 |
| 🆕 新建 | `service/impl/AuthServiceImpl.java` | P2 |
| 🆕 新建 | `dto/TokenPair.java` | P2 |
| 🆕 新建 | `dto/ValidationResult.java` | P2 |
| 🆕 新建 | `utils/TokenTestUtil.java` | P1 |
| 🆕 新建 | `resources/ConsumeVerifyCode.lua` | P0 |
| ✏️ 修改 | `controller/UserController.java` | P0/P1/P3 |
| ✏️ 修改 | `service/IUserService.java` | P1 |
| ✏️ 修改 | `service/impl/UserServiceImpl.java` | P0/P1/P2 |
| ✏️ 修改 | `interceptor/RefreshTokenInterceptor.java` | P2/P4 |
| ✏️ 修改 | `interceptor/LoginInterceptor.java` | 不改 |
| ✏️ 修改 | `config/MvcConfig.java` | 不改 |
| ✏️ 修改 | `utils/security/JwtUtil.java` | P0（修拼写） |
| ✏️ 修改 | `md/前端开发文档.md` | P1（同步 Token 约定） |
| 🗑️ 删除 | `service/AiService.java` | P0 |
| 🗑️ 删除 | `service/impl/AiServiceImpl.java` | P0 |
| 🗑️ 删除 | `service/impl/VoucherOrderServiceImpl.java` | P0 |

---

## 5. 优先级与工作量

```
Phase 0 ─── 紧急安全修复 ─── 6个子任务 ─── 约 1.5h ─── 🔴 立即执行
Phase 1 ─── 测试剥离+Token统一   6个子任务 ─── 约 1h  ─── 🟡 与P0可并行
Phase 2 ─── AuthService抽取      7个子任务 ─── 约 3h  ─── 🟡 核心重构
Phase 3 ─── 权限+缓存增强        4个子任务 ─── 约 2h  ─── 🟢 后续优化
Phase 4 ─── 拦截器瘦身           4个子任务 ─── 约 1h  ─── 🟢 后续优化
```

---

## 6. 验收标准

| Phase | 验收方式 |
|-------|---------|
| P0 | ✅ `POST /user/logout` 返回成功，原 Token 请求返回 401 |
| P0 | ✅ 同一验证码只能使用一次，第二次返回"验证码错误" |
| P0 | ✅ 同一手机号 60 秒内重复请求验证码返回"发送太频繁" |
| P0 | ✅ `mvn compile` 通过，无 HttpSession 引用 |
| P1 | ✅ 生产 Service 无测试代码，`TokenTestUtil` 可独立调用 |
| P1 | ✅ 登录接口返回头携带 `authorization` + `Refresh-Token` |
| P2 | ✅ `AuthService` 独立完成 Token 全生命周期管理 |
| P2 | ✅ Lua 异常时返回错误消息，不静默吞掉 |
| P2 | ✅ Caffeine 使用 `getIfPresent` 不阻塞 |
| P3 | ✅ 非本人查询 `GET /user/{id}` 返回 403 |
| P3 | ✅ 非本人查询 `GET /user/info/{id}` 返回 403 |
| P4 | ✅ 拦截器代码量减少 50%+，`@RecordTime` 接管耗时日志 |

---

## 7. 与前端对接注意事项

### 7.1 Token 获取方式变更（Phase 1 起生效）

```js
// 旧方式（v2 及之前）—— 从响应体取
const data = await response.json();
localStorage.setItem('token', data.data.token);
localStorage.setItem('refreshToken', data.data.refreshToken);

// 新方式（Phase 1 后）—— 从响应头取
localStorage.setItem('token', response.headers.get('authorization'));
localStorage.setItem('refreshToken', response.headers.get('Refresh-Token'));
```

### 7.2 logout 调用（Phase 0 起可用）

```js
// 用户退出时
await fetch('/user/logout', {
    method: 'POST',
    headers: {
        'authorization': localStorage.getItem('token'),
        'Refresh-Token': localStorage.getItem('refreshToken')
    }
});
localStorage.removeItem('token');
localStorage.removeItem('refreshToken');
window.location.href = '/login';
```

### 7.3 CORS

项目已配置 `CorsConfig`，开发期允许所有来源跨域，无需前端代理。

---

## 8. 架构审查追踪

| 审查问题 | 严重程度 | 在 v3 中如何解决 |
|----------|---------|-----------------|
| 验证码可重复使用 | 🔴 P0 | 0.2：新建 `ConsumeVerifyCode.lua` 原子 GET+DEL |
| logout 未实现 | 🔴 P0 | 0.1：提到 Phase 0，10 分钟实现 |
| GET /user/{id} 无权限 | 🔴 P0 | 3.1：仅本人可查 |
| Caffeine get() 阻塞 | 🟠 P1 | 2.7：改为 `getIfPresent()` |
| Token 传递不统一 | 🟠 P1 | 1.5-1.6：提前到 Phase 1 统一为响应头 |
| Lua JSON 解析脆弱 | 🟡 P2 | 2.6：Lua 异常时返回错误消息 |
| 方案二破坏无状态原则 | 🟠 P1 | v3 **已删除方案二**，仅保留响应头方案 |
| info() 接口无权限 | 🟡 P2 | 3.2：补充权限校验 |
| 验证码无限频 | 🟡 P2 | 0.3：60 秒频率限制 |
| 拼写错误 | 🔵 P3 | 0.6：修复 |

---

## 9. 拆分执行建议

建议按以下顺序执行，每完成一个 Phase 提交一次：

```
commit 1: "fix: implement logout, atomic verify code, and rate limit"           → Phase 0
commit 2: "refactor: extract test utilities and unify token delivery"            → Phase 1
commit 3: "feat: add AuthService for token lifecycle management"                  → Phase 2
commit 4: "fix: add auth check and cache for user endpoints"                      → Phase 3
commit 5: "refactor: slim down RefreshTokenInterceptor"                           → Phase 4
```
