---
name: alice-session-memory-and-direction
description: 在会话切换和上下文压缩后恢复 Alice 当前方向、决策、验证状态与开发习惯。
---
# Alice Session Memory and Direction

## 何时使用

每次新 AI 会话开始、上下文压缩后继续、用户说“继续之前工作”、或当前文档之间出现冲突时使用。

## 核心原则

会话记忆不是项目事实。先恢复当前状态，再开始讨论或修改代码。历史文档不能自动覆盖用户本轮决定、当前代码和实际客户端证据。

## 启动时只检查这些内容

1. 确认仓库路径和当前 Git 状态。
2. 优先读取 `docs/AI_PROJECT_STATE.md`、`docs/AI_DECISIONS.md`、`docs/AI_TEST_MATRIX.md`；文件不存在时明确告知用户，不要假装存在。
3. 查看相关专项设计和最近变更记录。
4. 根据任务领域读取对应 Forge skill。
5. 用一句话向用户复述当前目标、禁止改变的边界和成功条件。

## 状态标签

必须区分：

```text
IMPLEMENTED       已写入代码
COMPILES          已编译
SERVER_TESTED     服务端/fixture 已验证
CLIENT_TESTED     Windows 客户端已观察
USER_ACCEPTED     用户确认通过
```

任何较低等级都不能自动宣称较高等级。没有日志、截图或用户描述时，不要猜测客户端结果。

## 方向保护

当前稳定边界包括：

- LLM 只做目标级决策；确定性执行器负责动作和事实验收。
- 服务端是世界、任务、权限和库存的真相。
- 普通挖矿与拾取保持 `HARD_PATH`。
- `SOFT_SURFACE` 不得悄悄接入正式任务。
- `SEARCH_LIMIT` 不等于 `UNREACHABLE`，不能自动授权挖隧道。

如果需求需要推翻其中一项，先和用户讨论架构变化，不要把它藏在修复补丁里。

## 交接记录

每次重要改动结束，在当前状态或变更日志中写：

```text
本轮目标：
实际改动：
已验证等级：
客户端证据：
仍未验证：
失败/排除的假设：
下一步：
```

这是一份记忆恢复 skill，不是审批流程；不要求每个小改动创建工作包或执行旧监督脚本。
