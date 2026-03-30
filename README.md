# hm-dianping - 高并发点评平台（黑马点评扩展版）

基于黑马点评课程项目进行的深度扩展与重构，重点优化用户认证登录流程和高并发订单/库存扣减逻辑。项目采用 Spring Boot 技术栈，强调安全、并发一致性与性能优化。

## 项目亮点

- **登录认证模块**：完整重构双令牌机制，实现严格单设备登录 + 安全旋转刷新，防止重放攻击与并发覆盖。
- **订单/库存扣减模块**：Redis 预减 + Lua 原子操作 + RabbitMQ 异步落库，支撑高并发场景下的库存准确性。
- **性能优化**：通过压测数据验证优化效果，吞吐量与数据库压力显著改善。

## 主要优化点

本项目在黑马点评基础项目上进行了深度优化，主要聚焦高并发场景下的性能、安全与一致性。以下是关键优化内容：

### 1. 用户认证与安全优化
- **双令牌机制重构**：原单Token改为Access Token + Refresh Token，增强安全性。
- **单设备登录限制**：Redis存储用户当前有效Token，新登录自动失效旧Token，防止多设备并发登录问题。
- **Refresh Token旋转策略**：每次刷新生成新Refresh Token，旧Token一次性使用，防止重放攻击。
- **版本号机制**：引入version字段，严格校验Token组合的有效性，避免过期Token滥用。
- **原子化刷新流程**：使用Redis + Lua脚本保证Token刷新原子性，解决并发覆盖问题。
- **用户信息缓存优化**：Redis Hash缓存用户数据，减少数据库查询压力，支持异步批量加载。

[查看登录流程](login-process-flow.md)  
[查看Refresh拦截器拦截流程](refresh-token-interceptor-flow.md)  
[查看刷新过期token流程](refresh-expired-token-flow.md)

### 2. 高并发库存扣减优化
- **Redis预减库存**：下单时先在Redis扣减库存，快速响应用户请求。
- **Lua脚本原子操作**：单脚本内完成库存检查、扣减与重复下单校验，确保一致性。
- **异步消息落库**：RabbitMQ异步投递扣减消息，解耦业务逻辑与数据库操作。
- **消息可靠投递**：Confirm + Return机制保证消息不丢失，支持幂等消费防止重复扣减。
- **性能提升**：压测显示库存扣减接口TP99响应时间下降约55%，显著改善高并发下的性能。

### 3. 缓存与异步优化
- **多级缓存架构**：本地缓存 → Redis → 数据库，逐级降级减少网络开销。
- **异步用户信息加载**：Token刷新时异步加载缓存，避免同步阻塞。
- **批量缓存更新**：定时任务批量刷新缓存，确保数据一致性与时效性。
- **点赞异步处理**：Redis缓存点赞数据，定时批量写入数据库，减少实时DB压力。

### 4. 整体性能与稳定性提升
- **并发一致性保证**：通过分布式锁、Lua原子脚本等手段解决高并发数据竞争。
- **数据库压力缓解**：缓存预加载 + 异步处理大幅降低DB查询频率。
- **压测验证**：使用JMeter进行高并发测试，量化优化效果，确保生产环境稳定性。

这些优化基于实际业务场景，通过技术手段显著提升了系统的并发处理能力、安全性与用户体验。

## 技术栈

- 后端：Spring Boot、MyBatis
- 数据库：MySQL
- 缓存：Redis（Lua 脚本、Hash、分布式锁相关）
- 消息队列：RabbitMQ（可靠投递、幂等）
- 工具：Maven、Git、JMeter（压测）

## 关键文件位置

- 刷新临期token Lua 脚本：`src/main/resources/refreshToken.lua`
- 刷新过期token Lua 脚本：`src/main/resources/refreshExpiredToken.lua`
- 库存扣减 Lua 脚本：`src/main/resources/MqSeckill.lua`
- 拦截器与令牌逻辑：`src/main/java/com/hmdp/interceptor/RefreshTokenInterceptor.java`等包

## 运行与压测

1. 配置 application.yml 中的 Redis、MySQL、RabbitMQ 连接信息。
2. 启动项目：`mvn spring-boot:run` 或 IDE 直接运行。
3. 压测脚本位于项目根目录或 test 目录下的 JMeter 文件（.jmx）。

## 说明

本项目为个人学习与求职作品，代码已尽量模块化、注释清晰。欢迎审阅登录认证与库存扣减部分的实现细节，尤其是 Lua 脚本的原子操作逻辑。

如有问题或建议，欢迎 Issue 或 Pull Request。

最后更新：2026 年 3 月
