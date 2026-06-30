# Login 模块重构方案（v2）

> 本文档基于现有代码逐行分析，给出可落地的分阶段重构计划。
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
| ② | 75-78 | 从 Redis 取验证码并比对 | 正常 |
| ③ | 80-95 | 查用户 → 不存在则自动创建 | 创建用户不应放在登录方法中 |
| ④ | 96 | 用雪花ID生成 version | 属于 Token 生成流程 |
| ⑤ | 97 | JWT 签名生成 AccessToken | 属于 Token 生成流程 |
| ⑥ | 98-108 | 用户信息写入 Redis Hash | 属于用户缓存逻辑 |
| ⑦ | 110-117 | 组装 Lua 参数列表 | 属于 Token 存储逻辑 |
| ⑧ | 118-141 | 执行 Lua 脚本写入 Redis | 属于 Token 存储逻辑 |
| ⑨ | 142-151 | 组装返回 Map | 属于 Controller 层 |

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
| `GET /user/{id}` 无缓存 | Controller | 直接 MP getById()，其他模块均有缓存 |
| `AuditServiceImpl`/`AiService` 被注释 | service/ | 整个文件被注释掉，dead code |
| 拦截器 20000+ 字节 | RefreshTokenInterceptor | Token 校验+刷新+缓存+日志全部内联 |
| LoginSetToken.lua 逻辑重复 | 测试代码 | 测试方法中又用 Pipeline 重写了一遍 Lua 的逻辑 |

---

## 2. 目标架构

### 2.1 新增 AuthService 层

将认证逻辑从 `UserServiceImpl` 和 `RefreshTokenInterceptor` 中抽离：

```
┌─ UserController ──────┐    ┌─ AuthController (可选拆分) ─┐
│  /user/login          │    │  /auth/login                │
│  /user/logout         │    │  /auth/refresh              │
│  /user/code           │    │  /auth/logout               │
│  /user/me             │    │                             │
│  /user/{id}           │    └──────────┬──────────────────┘
│  /user/info/{id}      │               │
│  /user/sign           │               │
│  /user/sign/count     │               │
└──────────┬────────────┘               │
           │                            │
           ▼                            ▼
┌─ UserServiceImpl ────┐    ┌─ AuthServiceImpl ────────────┐
│  用户创建/查询        │    │  generateTokenPair()         │
│  签到                 │    │  validateAccessToken()       │
│  发送验证码            │    │  refreshTokenPair()          │
│                      │    │  revokeTokens()              │  ← logout
│                      │    │  sendVerifyCode()            │
└──────────────────────┘    └──────────┬──────────────────┘
                                       │
                                       ▼
                            ┌─ JwtUtil ────────────┐
                            │  sign/verify          │
                            │  RSA key pair         │
                            └──────────────────────┘
```

### 2.2 数据流（理想态）

```
POST /user/login { phone, code }
  │
  ├─ ① Controller: 参数校验（@Valid）
  ├─ ② UserService: 根据手机号查/创建用户
  ├─ ③ AuthService: generateTokenPair(userId)
  │     ├─ RedisIdWorker.nextVersion()
  │     ├─ JwtUtil.generateToken(access)
  │     ├─ UUID (refreshToken)
  │     └─ Redis Lua: 原子写入 token+version+refreshToken
  ├─ ④ AuthService: 将用户信息缓存到 Redis (userinfo map)
  └─ ⑤ Controller: 响应头写回 Token（与后续请求一致）
```

---

## 3. 分阶段实施计划

### Phase 0 — 立即清理（低风险，可并行）

| # | 操作 | 文件 | 说明 |
|---|------|------|------|
| 0.1 | 移除 `HttpSession` 参数 | UserController + IUserService + UserServiceImpl | 搜索 `HttpSession session`，全部删除 |
| 0.2 | 移除 `import javax.servlet.http.HttpSession` | UserController + IUserService + UserServiceImpl | 连带清理 |
| 0.3 | 删除被注释的 AiService / AiServiceImpl | service/AiService.java + impl/AiServiceImpl.java | 整文件删除 |
| 0.4 | 删除被注释的 VoucherOrderServiceImpl | service/impl/VoucherOrderServiceImpl.java | 整文件删除，已有 MqVoucherOrderServiceImpl |

### Phase 1 — 测试代码剥离

**目标**：将测试工具方法移出生产 Service

| # | 操作 | 说明 |
|---|------|------|
| 1.1 | 新建 `util/TokenTestUtil.java` | 将 `TOKEN_LIST`, `PHONE_LIST`, `REFRESHTOKEN_LIST`, `generateTestTokenAndRefreshToken()`, `exportTokenAndRefreshTokenToCsv()`, `generateTestPhone()`, `getTokenList()`, `getPhoneList()` 全部移入 |
| 1.2 | `IUserService` 移除 `exportTokenAndRefreshTokenToCsv()` | 接口只保留生产方法 |
| 1.3 | `UserServiceImpl` 删除上述静态变量和方法 | 清理约 200 行 |
| 1.4 | 更新 `TestServiceImpl` 中对 `exportTokenAndRefreshTokenToCsv` 的调用 | 改为调用 `TokenTestUtil` |

### Phase 2 — 抽取 AuthService

**目标**：创建独立的认证服务层，减少 UserServiceImpl 和拦截器负担

#### 2.1 新建 domain 类

```java
// dto/TokenPair.java
@Data
@AllArgsConstructor
public class TokenPair {
    private String accessToken;
    private String refreshToken;
    private Long version;
}

// dto/TokenValidationResult.java
@Data
public class TokenValidationResult {
    private boolean valid;
    private Long userId;
    private Long version;
    private boolean needsRefresh;  // 临期需要刷新
}
```

#### 2.2 新建 AuthService

```java
// service/AuthService.java
public interface AuthService {
    /** 登录时生成双Token + version，写入 Redis（Lua 原子操作） */
    TokenPair generateTokenPair(Long userId);

    /** 校验 Access Token，返回解码结果 */
    TokenValidationResult validateAccessToken(String token);

    /** 临期刷新：双Token + version 全部更新 */
    TokenPair refreshByDeadline(String oldAccessToken, String refreshToken, Long oldVersion, Long userId);

    /** 过期刷新：用 RefreshToken 换取新双Token */
    TokenPair refreshByExpired(String expiredAccessToken, String refreshToken);

    /** 登出：删除 Redis 中该用户的所有 Token/Version 记录 */
    void revokeTokens(Long userId);

    /** 缓存用户信息到 Redis Hash */
    void cacheUserInfo(UserDTO userDTO);
}
```

#### 2.3 Service 职责重新分配

```
UserServiceImpl 保留:              AuthServiceImpl 新承担:
├── sendCode()                     ├── generateTokenPair()
├── login() → 简化为:               ├── validateAccessToken()
│   ① 校验手机号                    ├── refreshByDeadline()
│   ② 校验验证码                    ├── refreshByExpired()
│   ③ 查/创建用户                   ├── revokeTokens()
│   ④ 调 AuthService 生成 Token     └── cacheUserInfo()
│   ⑤ 返回 Token                   └── (接管 LoginSetToken.lua 调用)
├── sign()
├── getSignCount()
```

#### 2.4 拦截器改造

```
RefreshTokenInterceptor (瘦身):
  preHandle() {
    ① checkTokenHeader()              ← 保留
    ② AuthService.validateAccessToken()   ← 替代内联JWT解析+版本校验
    ③ if (临期) AuthService.refreshByDeadline()  ← 替代内联Lua调用
    ④ 设置 UserHolder
    ⑤ 响应头写回 Token
  }

  移除：refreshExpiredToken()         → AuthService.refreshByExpired()
  移除：refreshDeadlineToken()        → AuthService.refreshByDeadline()
  移除：validateLocalVersionCache()   → AuthService 内部
  移除：updateLocalVersionCache()     → AuthService 内部
  移除：resolveAndSaveUser()          → AuthService.validateAccessToken()
```

### Phase 3 — Controller 层增强

| # | 操作 | 说明 |
|---|------|------|
| 3.1 | 实现 `logout()` | Controller 调 AuthService.revokeTokens()，然后清除 UserHolder |
| 3.2 | `GET /user/{id}` 增加缓存 | 参考 ShopServiceImpl 的 CacheClient 模式 |
| 3.3 | 实现密码登录 | 在 login() 中增加 `password` 分支：密码加密存入 `tb_user.password`，登录时比对 |
| 3.4 | 可选：拆分为 AuthController | `/auth/login`, `/auth/refresh`, `/auth/logout` 独立控制器 |

### Phase 4 — 统一 Token 传递约定

**目标**：消除响应体/响应头的不一致

**方案一（推荐）**：全员走响应头

| 场景 | 改为 |
|------|------|
| 登录成功 | `response.setHeader("authorization", token)` + `response.setHeader("Refresh-Token", refreshToken)` |
| 前端读取 | 统一从 `response.headers['authorization']` 读取 |
| 好处 | 与拦截器的刷新逻辑完全一致，前端只需一套读取逻辑 |

**方案二（备选）**：全员走响应体

| 场景 | 改为 |
|------|------|
| 刷新后返回新 Token | 拦截器将新 Token 写入请求 attribute，由 Controller/Filter 写入响应体 |
| 好处 | 前端 fetch 逻辑统一，读取 `.data.token` |
| 代价 | 需要新增一个后置处理组件来写响应体 |

### Phase 5 — 拦截器瘦身 + 日志分离

| # | 操作 | 说明 |
|---|------|------|
| 5.1 | `preHandle` 中的性能日志移入 `@RecordTime` 切面 | already have the annotation |
| 5.2 | Token 解析+校验全部委托给 `AuthService` | 拦截器只做编排 |
| 5.3 | 响应头写回逻辑可封装为工具方法 | 减少重复 |

---

## 4. 文件变更清单

| 操作 | 文件 | Phase |
|------|------|-------|
| 🆕 新建 | `service/AuthService.java` | P2 |
| 🆕 新建 | `service/impl/AuthServiceImpl.java` | P2 |
| 🆕 新建 | `dto/TokenPair.java` | P2 |
| 🆕 新建 | `dto/TokenValidationResult.java` | P2 |
| 🆕 新建 | `utils/TokenTestUtil.java` | P1 |
| ✏️ 修改 | `service/impl/UserServiceImpl.java` | P0-P3 |
| ✏️ 修改 | `service/IUserService.java` | P1 |
| ✏️ 修改 | `controller/UserController.java` | P0/P3 |
| ✏️ 修改 | `interceptor/RefreshTokenInterceptor.java` | P2/P5 |
| ✏️ 修改 | `config/MvcConfig.java` | P3 (若拆 AuthController) |
| 🗑️ 删除 | `service/AiService.java` | P0 |
| 🗑️ 删除 | `service/impl/AiServiceImpl.java` | P0 |
| 🗑️ 删除 | `service/impl/VoucherOrderServiceImpl.java` | P0 |

---

## 5. 预计工作量

| Phase | 内容 | 文件数 | 预估时间 | 风险 |
|-------|------|--------|---------|------|
| P0 | 清理 + 删除 dead code | 6 | 15min | 极低 |
| P1 | 剥离测试代码 | 3 | 30min | 低 |
| P2 | 抽取 AuthService（核心） | 6 | 2-3h | 中（需仔细迁移 Token 逻辑） |
| P3 | Controller 增强 | 3 | 1-2h | 低 |
| P4 | 统一 Token 约定 | 2 | 30min | 中（需前端配合） |
| P5 | 拦截器瘦身 | 2 | 1h | 低 |

---

## 6. 验收标准

| Phase | 验收方式 |
|-------|---------|
| P0 | `mvn compile` 通过，无 HttpSession 引用 |
| P1 | 生产 Service 无 `TOKEN_LIST` 等测试代码，测试工具类可正常调用 |
| P2 | `AuthService` 独立完成 Token 生成/校验，原有登录/刷新流程功能不变 |
| P3 | `POST /user/logout` 返回成功，之后原 Token 请求返回 401 |
| P4 | 登录接口返回头中携带 `authorization` 和 `Refresh-Token` |
| P5 | 拦截器减负，`@RecordTime` 切面接管耗时日志 |

---

## 7. 与前端对接注意事项

1. **Token 传递方式统一后**（Phase 4），前端登录代码需要调整：
   ```js
   // 当前（响应体取 Token）
   const data = await response.json();
   localStorage.setItem('token', data.data.token);

   // 改为（响应头取 Token）
   localStorage.setItem('token', response.headers.get('authorization'));
   localStorage.setItem('refreshToken', response.headers.get('Refresh-Token'));
   ```

2. **logout 实现后**，前端应在用户退出时调用 `POST /user/logout`，并清除本地存储的 Token。

3. **CORS 配置已完成**（`CorsConfig`），前端无需额外代理即可跨域调用。
