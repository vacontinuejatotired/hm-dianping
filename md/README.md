# hm-dianping 项目文档索引

> 本项目文档统一存放在 `md/` 目录下，按类型分为技术设计、开发指南、优化报告三大类。

---

## 📋 文档一览

### 🏗️ 架构与设计

| 文档 | 说明 | 适用对象 |
|------|------|---------|
| [登录流程](login-process-flow.md) | 用户登录的完整时序图（验证码→双Token生成） | 前后端开发 |
| [Token 刷新拦截器流程](refresh-token-interceptor-flow.md) | RefreshTokenInterceptor 校验与刷新的完整流程 | 后端 |
| [过期 Token 刷新流程](refresh-expired-token-flow.md) | Access Token 过期后通过 Refresh Token 续期的处理链路 | 后端 |
| [Login 模块重构方案](login重构方案.md) | v3 — 6 个 Phase，含 AuthService 抽取、拦截器瘦身、密码登录 | 后端 |
| [密码登录方案](密码登录方案.md) | Phase 3.4 — BCrypt 升级、账户锁定、频率限制 | 后端 |
| [项目优化方案](项目优化方案.md) | 秒杀/商铺/Upload/RabbitMQ 等非 Login 模块问题 | 后端 |
| [请求头设计规范](请求头设计规范.md) | Token 刷新场景分析、安全决策、前后端对接规范 | 前后端开发 |
| [阿里云 OSS 图片上传方案](阿里云OSS图片上传方案.md) | FileService 接口设计、本地/OSS 双实现、文件校验 | 后端 |

### 📡 前端对接

| 文档 | 说明 | 适用对象 |
|------|------|---------|
| [前端开发文档](前端开发文档.md) | 完整 API 接口文档（含数据模型、认证机制、分页说明） | 前端 |
| [请求头设计规范](请求头设计规范.md) | Token 刷新场景分析、安全决策、前端实现要点 | 前后端开发 |

### ⚡ 性能优化

| 文档 | 说明 | 适用对象 |
|------|------|---------|
| [下单优化压测报告](下单优化压测报告.md) | 秒杀场景下 Redis+MQ 异步落库的压测数据与对比 | 后端 |

### 🛠️ 全项目优化

| 文档 | 说明 | 适用对象 |
|------|------|---------|
| [项目优化方案](项目优化方案.md) | 秒杀/商铺/Upload/RabbitMQ 等非 Login 模块的问题与建议 | 后端 |
| [项目优化记录](项目优化记录.md) | Phase 0-4 已落地的优化项清单（含涉及文件） | 后端 |
| [架构毁灭者审查报告](架构毁灭者-代码挑战专家.md) | 第三方代码审查的发现与修复建议 | 后端 |

### 📐 开发规范

| 文档 | 说明 | 适用对象 |
|------|------|---------|
| [Git 提交规范](git规范.md) | Commit Message 格式、Type/Scope 定义、提交粒度规范 | 全团队 |

---

## 📁 项目结构

```
hm-dianping/
├── src/main/java/com/hmdp/
│   ├── annotation/          ← 自定义注解（如 @RecordTime）
│   ├── aspect/              ← AOP 切面（如方法耗时统计）
│   ├── config/              ← 配置类（CORS/拦截器/缓存/RabbitMQ/Redisson...）
│   ├── controller/          ← 控制器（11 个，User/Shop/Blog/Voucher/Upload/...）
│   ├── dto/                 ← 数据传输对象（TokenPair / ValidationResult / PasswordChangeDTO / ...）
│   ├── entity/              ← 数据库实体（13 个）
│   ├── enums/               ← 枚举（TokenRefreshCode / SeckillOrderCode）
│   ├── interceptor/         ← 拦截器（LoginInterceptor + RefreshTokenInterceptor）
│   ├── mapper/              ← MyBatis-Plus Mapper（10 个）
│   ├── service/
│   │   ├── impl/            ← 服务实现（16 个，含 AuthService / FileService 双实现）
│   │   └── interfaces       ← 服务接口（14 个）
│   ├── utils/
│   │   ├── cache/           ← 缓存工具（CacheClient / CaffeineConstants / BatchLoadCache...）
│   │   ├── redis/           ← Redis 工具（RedisConstants / RedisIdWorker / 分布式锁...）
│   │   ├── security/        ← 安全工具（JwtUtil / PasswordEncoder）
│   │   ├── constants/       ← 常量（SystemConstants / RabbitMqConstants / RegexPatterns）
│   │   ├── RegexUtils.java
│   │   └── UserHolder.java
│   └── HmDianPingApplication.java
└── md/                      ← 项目文档（本目录）
```

---

## 🔗 关键外部资源

| 资源 | 说明 |
|------|------|
| `resources/application-dev.yaml` | 开发环境配置（Redis/MySQL/RabbitMQ 地址、端口、密码） |
| `resources/application-prod.yaml` | 生产环境配置模板（OSS、SSL 等） |
| `resources/*.lua` | Redis Lua 脚本（LoginSetToken / 刷新 / 秒杀扣库存等） |
| `resources/db/hmdp.sql` | 数据库建表脚本 |
| `resources/logback-spring.xml` | 日志配置（按天归档） |
| `pom.xml` | Maven 依赖管理 |

---

## 📝 文档编写约定

1. 新增文档请在本索引中添加条目
2. 文档使用 Markdown 格式，存放在 `md/` 目录下
3. 涉及 API 变更时同步更新 `前端开发文档.md`
4. 涉及架构变更时更新对应流程文档
