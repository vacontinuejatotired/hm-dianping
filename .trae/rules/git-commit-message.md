---
alwaysApply: true
scene: git_message
---

# Git 提交信息生成规则

## 格式要求
- 使用 `<type>(<scope>): <subject>` 格式
- subject 首字母小写，末尾不加句号
- 主体内容每行不超过72字符
- type 和 scope 后紧跟冒号，冒号后有一个空格

## Type 类型定义
- **feat**: 新增功能
- **fix**: 修复bug
- **docs**: 仅文档变更
- **style**: 代码格式调整（不影响功能）
- **refactor**: 重构（不是新功能也不是修复bug）
- **perf**: 性能优化
- **test**: 测试相关
- **chore**: 构建过程或辅助工具变更
- **ci**: CI配置或脚本变更
- **revert**: 回滚提交

## Scope 定义（可选）
Scope 表示本次提交影响的范围，建议使用以下值之一：

| Scope | 说明 |
|-------|------|
| `interceptor` | 拦截器相关（如 RefreshTokenInterceptor） |
| `service` | 业务服务层 |
| `controller` | 控制器层 |
| `mapper` | 数据访问层 |
| `entity` | 实体/模型类 |
| `utils` | 工具类 |
| `config` | 配置类 |
| `cache` | 缓存相关（Caffeine、Redis） |
| `lua` | Lua 脚本 |
| `security` | 安全/认证相关（JWT、Token） |
| `dto` | 数据传输对象 |
| `common` | 公共模块 |

## Subject 编写原则
- 使用祈使句，现在时态
- 清晰描述"做了什么"，不解释"为什么"
- 例如："add user authentication" 而非 "added user auth"
- 长度建议不超过50字符，总长度（含 type 和 scope）不超过72字符
- 避免使用标点符号结尾

## Body 编写规则（可选）
- 解释"为什么"和"如何解决的问题"
- 每行不超过72字符
- 与subject之间空一行
- 使用项目符号列表（- 或 *）提高可读性
- 当提交涉及多个方面时，建议使用 body 进行分点说明

## Footer 使用场景
- 破坏性变更：以 `BREAKING CHANGE:` 开头，后跟变更说明
- 关闭issue：`Closes #123, #456`
- 关联issue：`Refs #123`

## 提交频率与粒度规范
- **单一职责**：每次提交只做一件事，避免将多个无关改动混入一次提交
- **最小粒度**：
  - 新增功能：一个功能点一次提交
  - Bug 修复：一个 bug 一次提交（关联 issue 时在 footer 注明）
  - 重构：按模块拆分提交，不混入功能变更
  - 代码格式化：单独提交，不与逻辑变更混合
- **避免"大杂烩"提交**：如同时包含 feat + fix + refactor，应拆分为多次提交
- **WIP 提交**：允许在工作进行中创建临时提交，但合并前需清理为有意义的提交信息

## Breaking Change 标记规范
- 在 subject 的 type 后添加 `!` 标记，如 `feat!(api): remove deprecated endpoints`
- 在 footer 中以 `BREAKING CHANGE:` 开头详细说明破坏性变更的内容、原因和迁移指南
- 示例：
  ```
  refactor!(cache): replace Caffeine with Redis-only cache

  BREAKING CHANGE: 移除 Caffeine 本地缓存，所有缓存操作直接走 Redis。
  迁移：删除 caffeine 相关依赖和配置，确保 Redis 可用。
  ```

## Revert 特殊格式
回滚提交使用 `revert` 类型，格式如下：
```
revert: <被回滚提交的 subject>

This reverts commit <commit-hash>.
```
示例：
```
revert: feat(interceptor): add local version cache

This reverts commit a1b2c3d4e5f6g7h8i9j0k1l2m3n4o5p6q7r8s9t.
```

## 提交信息校验与自动化工具推荐

### 手动校验清单（提交前自查）
- [ ] type 是否正确使用了预定义类型
- [ ] scope（如有）是否准确反映了影响范围
- [ ] subject 是否使用祈使句、现在时态
- [ ] subject 是否不超过50字符
- [ ] 是否有不必要的标点符号结尾
- [ ] body（如有）是否每行不超过72字符
- [ ] footer（如有）格式是否正确

### 推荐工具
- **[commitlint](https://commitlint.js.org/)**：校验提交信息是否符合 Conventional Commits 规范
  - 推荐配置：`@commitlint/config-conventional`
- **[husky](https://typicode.github.io/husky/)**：Git hooks 工具，可在 commit-msg hook 中集成 commitlint
- **[commitizen](https://github.com/commitizen/cz-cli)**：交互式提交工具，引导填写符合规范的提交信息
- 集成示例（package.json）：
  ```json
  {
    "husky": {
      "hooks": {
        "commit-msg": "commitlint -E HUSKY_GIT_PARAMS"
      }
    }
  }
  ```

## 示例

### 简单提交