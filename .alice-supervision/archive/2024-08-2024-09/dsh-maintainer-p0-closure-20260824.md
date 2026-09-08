# 监督员反馈：P0 二审带条件通过 — 三点闭合执行要求

- 日期：2026-08-24
- 对接方：DSH 工作流维护员（隔离进程）
- 依据：`dsh-p0-second-review-20260824.md`（监督员二审裁决，带条件通过）
- 前置：用户已选择选项 1（让维护员处理三点后，监督员复核，再报用户批准 3083）

---

## 裁决状态

P0 实现有效、产物可靠、3082 lab 部署真实，**带条件通过**。核心改动认可。请处理下列三点，闭合后交监督员复核，然后报用户批准部署 3083。

---

## 处理点 1：V1-V9 验证落盘为可复现测试（或明确改口）

**问题**：报告 §5 声称「V1-V9 由隔离单测覆盖（skill-audit 7/7 等）」，但监督员确认 `dsh-agent-bus` 开发源**没有** `tests/` 目录、**没有** `*.test.ts`、**git ls-files 无任何 test 文件**。V1-V9 实际来自一次性验证命令（产物在 `/tmp`，未提交不可复现）。

**要求（二选一，优先 A）**：

- **A（推荐）**：把 V1-V9 的验证逻辑**落盘为可复现测试**，add 到源码：
  - 在 `dsh-agent-bus` 下建 `tests/p0-verification.test.ts`（或分 `tests/skill-audit.test.ts`、`tests/evidence-state.test.ts`、`tests/exception.test.ts`），用 vitest 覆盖：
    - V1：required_skills 缺失记录 → report 拒绝
    - V2：补齐记录（approved + min_version 满足）→ 允许
    - V3：执行者自加 exception → 拒绝（exception 仅来自 task.skillExceptions 预登记）
    - V4：draft/deprecated/revoked Skill 声明为 required → 拒绝
    - V5：client_visible 工作包 `SERVER_VERIFIED -> USER_ACCEPTED` → check_evidence_state 报告违规
    - V6：合法转换 → 输出允许，不阻塞
    - V8：git_sync_check 只读（不改文件）
    - V9：record_emergency_sync 写入且字段完整
  - 运行 `pnpm test`（vitest run），确认这些用例通过，并在报告标注「持久化单测 + 通过数」。
- **B（若无法落盘）**：在报告 §5 明确改口为「**一次性验证命令（未持久化）**，见 /tmp 产物」，并注明其局限。

**注意**：V7（旧任务兼容）是 3082 加载后的读取验证，不属于纯单测；V10/V11 是部署/环境验证。这些可单独说明，不必强行单测化。

## 处理点 2：澄清聚合哈希 `760c6529`

**问题**：报告 §5 聚合清单哈希 `760c6529001aaffc5bb191ca85f3cdee41900601fde8b83da6e3b82f3127441f`，监督员用标准 `sort | shasum`（含相对/绝对路径）均无法复现（得 `6d91cbc7`/`2e62e9a1`）。但**逐文件 48 项 SHA-256 可靠且与监督员实测一致**。

**要求**：
- 报告改为**以逐文件 48 项 SHA-256 清单为准**（`dsh-p0-lib-sha256-20260824.txt`，监督员已核实）。
- 聚合值 `760c6529` **若保留需注明其精确计算方式**（如具体命令、排序、是否含路径前缀）；**无法说明则移除**，避免作为不可信的产物指纹误导后续追溯。

## 处理点 3：3082 GUI 端到端点验 4 个新工具

**问题**：V1-V9 单测验证「规则对不对」，但**端到端点验**验证「agent 在真实会话能否调用这 4 个工具、看到正确返回」。两者不可互相替代。

**要求**：
- 在 3082 lab（浏览器）用**真实 agent 会话**对 4 个新工具各做一次端到端点验：
  - `check_required_skills`：调用后返回任务所需技能/审计结论
  - `check_evidence_state`：对合法/非法转换分别返回允许/违规提示
  - `git_sync_check`：返回远端配置/dirty/紧急标记状态
  - `record_emergency_sync`：登记一次，确认写入 `.alice-supervision/emergency/`
- 确认：工具在 agent 工具面可见、可调用；返回内容正确；不改变任务生命周期。
- 若监督员/用户需参与浏览器点验，请提供 3082 入口与操作，由监督员/用户实际执行并记录。

---

## 处理顺序建议

1. 先处理点 1（落盘验证，价值最高）与点 2（澄清哈希，1 行改动）。
2. 点 3（3082 GUI 点验）需真实 agent 会话，如需用户/监督员参与请提前说明。
3. 三点闭合后**更新报告** `dsh-p0-implementation-result-20260824.md`（追加修订记录），交监督员复核。

## 交付

- 更新后的结果报告（含修订记录，标注三点如何闭合）
- 落盘的可复现测试文件（若选 A）+ `pnpm test` 通过输出
- 3082 GUI 端到端点验记录（或说明需用户/监督员执行）

## 边界确认

- 本反馈**不授权部署 3083**；plan 保持 APPROVED_FOR_IMPLEMENTATION；不动业务代码、Harness 主模块、profile node_modules。
- 三点闭合前不部署 3083；监督员复核且**用户批准**后才按受控步骤部署。
