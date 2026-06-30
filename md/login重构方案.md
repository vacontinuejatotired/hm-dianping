# Login 模块重构方案

> 本文档分析当前登录认证模块（UserController + UserServiceImpl + RefreshTokenInterceptor）的问题，并给出分阶段重构计划。
> 
> 最后更新：2026-06

---

## 1. 当前架构概览

```
UserController
  ├── POST /user/code   →  sendCode(phone, HttpSession)
  ├── POST /user/login  →  login(loginForm, HttpSession)
  ├── POST /user/logout →  TODO: 未实现
  ├── GET  /user/me     →  从 UserHolder 获取
  ├── GET  /user/{id}   →  直接调 MP getById（无缓存）
  ├── GET  /user/info/{id}
  ├── POST /user/sign
  └── GET  /user/sign/count

UserServiceImpl.login() 职责链（~80行，9项职责）：
  ① 手机号格式校验
  ② 验证码校验（从 Redis 取）
  ③ 用户不存在则自动创建（含昵称生成）
  ④ 生成 version（Redis 雪花ID）
  ⑤ 生成 AccessToken（JWT RSA 签名）
  ⑥ 用户信息写入 Redis Hash
  ⑦ 生成 RefreshToken（UUID）
  ⑧ 执行 Lua 脚本（原子写入 token/version/refreshToken）
  ⑨ 组装返回 Map

RefreshTokenInterceptor（20000+ 字节）：
  ① Token 存在性校验
  ② JWT 解码与 Claims 提取
  ③ Token 临期自动续期（Lua 脚本）
  ④ Token 过期刷新
  ⑤ 版本号校验（Caffeine + Redis 两级）
  ⑥ UserHolder 设置/清理
  ⑦ 性能耗时日志
```

---

## 2. 识别的问题

### 2.1 Controller 层

| 问题 | 位置 | 影响 |
|------|------|------|
| `HttpSession` 形参仍保留 | `sendCode()`, `login()` | 参数已无用，项目已全部 Token 化 |
| `logout` 未实现 | `logout()` | 返回 `"功能未完成"` |
| `queryUserById` 无缓存 | `GET /user/{id}` | 每次查库，其他模块（Shop）有缓存 |
| 密码登录未实现 | `LoginFormDTO.password` | 字段定义了但 Service 从不使用 |

### 2.2 Service 层

| 问题 | 位置 | 影响 |
|------|------|------|
| `login()` 过长 | UserServiceImpl:69-152 | 9 项职责混合，违反单一职责 |
| 测试代码混入生产 | `TOKEN_LIST`, `PHONE_LIST`, `exportTokenToCsv` | 测试专用静态变量和方法不应在 Service 中 |
| Token 通过响应体返回 | `return Result.ok(map)` | 前端从 `data.token` 取，但拦截器从请求头读，约定不一致 |
| 密码登录无实现 | `login()` | 代码中有 `password` 判断分支但实际没用 |

### 2.3 拦截器层

| 问题 | 位置 | 影响 |
|------|------|------|
| 职责过多 | RefreshTokenInterceptor | Token校验+刷新+版本号验证+UserHolder+性能日志，拆分后更清晰 |
| Token刷新逻辑硬编码 | `refreshExpiredToken()` | 与 Service 层没有明确边界 |
| 性能日志耦合 | `finally` 块中 | 生产环境可考虑剥离开 |

### 2.4 认证流程

| 问题 | 说明 |
|------|------|
| Token 传递方式不统一 | 登录接口通过**响应体**返回 Token，其他情况拦截器通过**响应头**返回新 Token |
| 密码登录流程缺失 | 有密码字段但未实现密码注册和校验 |
| 登出功能缺失 | 无法主动失效 Token |

---

## 3. 分阶段重构计划

### Phase 1 — 跨域配置 ✅（已完成）

- [x] 添加 `CorsConfig` 配置类
- [x] 允许所有来源（开发期），暴露 `authorization` 和 `Refresh-Token` 响应头

### Phase 2 — Controller 层清理

**目标**：消除废弃参数和未实现接口，职责单一化

| 步骤 | 改动文件 | 说明 |
|------|---------|------|
| 1 | UserController | 移除 `sendCode()` 和 `login()` 中的 `HttpSession` 参数 |
| 2 | UserController | 实现 `logout()`：调用 AuthService 删除 Redis 中的 Token 和 Version |
| 3 | UserServiceImpl | 实现密码登录：密码加密存储 + `password` 字段校验 |
| 4 | UserController / UserServiceImpl | `queryUserById` 增加缓存（参考 Shop 的 CacheClient 模式） |
| 5 | UserServiceImpl | 移除测试代码：`TOKEN_LIST`, `PHONE_LIST`, `exportTokenToCsv` |

### Phase 3 — 抽取 AuthService

**目标**：将认证相关的逻辑从 `UserServiceImpl` 和 `RefreshTokenInterceptor` 中抽离为独立的 `AuthService`

```java
// 新抽取的认证服务
@Service
public class AuthService {
    // 生成双Token + version，写入 Redis
    TokenPair generateTokens(Long userId);
    
    // 校验 Access Token 合法性
    ValidationResult validateAccessToken(String token);
    
    // 刷新双Token（临期/过期）
    TokenPair refreshTokens(String refreshToken);
    
    // 登出：失效所有 Token
    void logout(Long userId);
}

// 支持的数据类
@Data
public class TokenPair {
    private String accessToken;
    private String refreshToken;
    private Long version;
}

@Data
public class ValidationResult {
    private boolean valid;
    private Long userId;
    private Long version;
    private boolean needsRefresh;  // 是否临期需要刷新
}
```

| 步骤 | 改动文件 | 说明 |
|------|---------|------|
| 1 | 新建 `AuthService` | 抽取 UserServiceImpl 中 Token 生成逻辑 |
| 2 | 新建 `AuthServiceImpl` | 含 `generateTokens`, `logout` |
| 3 | UserServiceImpl | `login()` 简化为：校验手机号→校验验证码→创建/查找用户→调用 AuthService |
| 4 | RefreshTokenInterceptor | 注入 `AuthService`，替换内联的 Token 刷新逻辑 |
| 5 | LoginFormDTO | 可选：拆分为 `CodeLoginDTO` + `PasswordLoginDTO` |

### Phase 4 — 统一 Token 传递约定

**目标**：前后端 Token 传递方式统一

**方案**：统一使用 **HTTP 请求头 + 响应头** 传递 Token

| 场景 | 当前 | 改为 |
|------|------|------|
| 登录成功返回 Token | 响应体 `data.token` | **响应头** `authorization` + `Refresh-Token`（保持与拦截器一致） |
| 前端发送 Token | 请求头 `authorization` | 不变（已经是请求头） |
| 拦截器刷新 Token | 响应头返回新 Token | 不变（已经是响应头） |

> **影响**：前端登录后需从 `response.headers['authorization']` 获取 Token，而非 `response.data.token`

### Phase 5 — 拦截器瘦身

**目标**：将 RefreshTokenInterceptor 拆分为多个职责单一的组件

| 步骤 | 说明 |
|------|------|
| 1 | 将 Token 校验逻辑移至 `AuthService.validateAccessToken()` |
| 2 | 将 Token 刷新逻辑移至 `AuthService.refreshTokens()` |
| 3 | 拦截器只保留：调用 AuthService → 设置 UserHolder → 响应头写回 |
| 4 | 性能日志抽取为独立切面（已有 `@RecordTime` 注解） |

---

## 4. 新架构（目标形态）

```
UserController (干净)
  ├── sendCode(phone)        ← 无 HttpSession
  ├── login(loginForm)       ← 调 AuthService 生成 Token
  ├── logout()               ← 调 AuthService.logout()
  ├── me()                   ← 不变
  ├── {id}                   ← 加缓存
  └── info/{id}              ← 不变

AuthService (新)              ← 认证专用服务
  ├── generateTokens()       ← 双Token生成 + Redis 落库
  ├── validateAccessToken()  ← JWT 校验 + 版本号
  ├── refreshTokens()        ← 临期/过期刷新
  └── logout()               ← 删除 Redis Token/Version

RefreshTokenInterceptor (瘦身)
  ├── 调 AuthService 校验
  ├── 调 AuthService 刷新（如需）
  ├── 设置/清理 UserHolder
  └── 写回响应头

UserServiceImpl
  └── 只保留用户业务逻辑（创建、查询、签到）
```

---

## 5. 优先级建议

| 优先级 | 阶段 | 工作量 | 收益 |
|--------|------|--------|------|
| 🔴 P0 | Phase 2-1: 移除 HttpSession | 极小 | 消除无用参数 |
| 🔴 P0 | Phase 2-3: 实现 logout | 小 | 完整登录生命周期 |
| 🟡 P1 | Phase 2-5: 移除测试代码 | 小 | 生产代码整洁 |
| 🟡 P1 | Phase 3: 抽取 AuthService | 中 | 核心重构，后续步骤依赖此 |
| 🟢 P2 | Phase 4: 统一 Token 约定 | 小 | 前后端对接清晰 |
| 🟢 P2 | Phase 5: 拦截器瘦身 | 中 | 可维护性提升 |
| 🔵 P3 | Phase 2-4: 用户查询加缓存 | 中 | 性能优化 |
| 🔵 P3 | Phase 2-3: 密码登录 | 大 | 扩展登录方式 |
