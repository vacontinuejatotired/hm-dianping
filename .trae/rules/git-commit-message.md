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

## Subject 编写原则
- 使用祈使句，现在时态
- 清晰描述"做了什么"，不解释"为什么"
- 例如："add user authentication" 而非 "added user auth"

## Body 编写规则（可选）
- 解释"为什么"和"如何解决的问题"
- 每行不超过72字符
- 与subject之间空一行
- 使用项目符号列表（- 或 *）提高可读性

## Footer 使用场景
- 破坏性变更：以 `BREAKING CHANGE:` 开头
- 关闭issue：`Closes #123, #456`

## 示例

### 简单提交