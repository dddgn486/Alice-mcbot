# DSH 维护员对改造规格第 13 节五问的答复

- 日期：2026-08-24
- 维护员：DSH 工作流维护员（鲸鱼娘）
- 依据：`dsh-agent-bus@0.1.1` 已安装源码（`/home/fb486/.dsh/profiles/alice-bus/node_modules/dsh-agent-bus/lib/`）
- 关联 plan：`dsh-workflow-reliability-plan-20260824.md`（DRAFT，未授权改 Harness）

---

## Q1. Skill manifest 与 required_skills gate 能否在 DSH 当前 task 模型中实现？

**能，但 gate 落点需适配：report 时强制审计，而非 working 前阻塞。**

- `taskRecord` 是 zod schema，加可选字段完全兼容（`ignored` 字段先例，v10→v11）。
- 现状无「claim 前加载 Skill」钩子：投递是异步 followup，bus 无法同步验证 agent 已加载 skill。
- 落地：`required_skills`/`skill_usage` 可选字段 + `report_task` 缺失记录则拒绝 + skill manifest 只读校验（draft 不可作 required）。
- 「working 前强制加载」需 agent-plane 钩子，破坏兼容面，本阶段不做。

## Q2. 证据状态机应由 DSH 通用层实现，还是项目配置定义？

**项目配置定义；通用层只记录与展示，不做转换裁决。**

- bus `status` 是 A2A 传输生命周期；`PLANNING→USER_ACCEPTED` 是业务验收状态，语义不同。
- 落地：`evidence_state` 可选字段 + 展示；转换规则放项目侧 `state-machine.yml`；禁止项由审核工具检查。

## Q3. Git/worktree 约束能否以提示、策略检查或工具封装实现？

**组合实现可行；硬阻止不可行（agent 有 bash 工具可绕过）。**

- `git_sync_check` 只读工具：检查远端配置（Windows 工作树非 push 目标）、dirty 状态、紧急标记。
- `create_task` 检测危险状态时追加警告段。
- `record_emergency_sync` 登记紧急操作（源 commit/路径/SHA-256/操作者/原因/恢复计划）。
- 禁止 reset --hard 常规回退：策略检查 + HANDOVER 审核要求 worktree/临时分支替代。

## Q4. 失败阈值「模块/契约」身份模型如何存储？

**新持久化表 `contract_failures`，键为 module+objective+contract 三元组。**

- 现有 `retries` 按 task id 计数且 settle failure 会清空，无法跨任务累积，不能满足「不得新建任务规避」。
- 新表字段：module/objective/contract/fail_count/last_failed_at/last_task_id/state/history。
- 1/2/3 阈值自动升级（BLOCKED_BY_CLIENT_EVIDENCE / INVESTIGATION_REQUIRED / ARCHITECTURE_REVIEW_REQUIRED）并通知监督员。

## Q5. 哪些 P0 项可在不破坏现有 task/flow 兼容性下先落地？

**全部四项，按依赖排序：**

| 序 | 项 | 方式 | 兼容 |
|---|---|---|---|
| 1 | Skill 审计（§3） | 可选字段 + report 审计 + manifest 校验工具 | ✅ |
| 2 | 证据状态（§4） | `evidence_state` 字段 + 项目状态机文件 + 审核工具 | ✅ |
| 3 | 结论等级（§5） | 项目 review 模板强制三字段（confidence/evidence/limitations）；bus 可选结构化展示 | ✅ |
| 4 | Git/worktree（§6） | `git_sync_check` + `record_emergency_sync` + 派发警告 | ✅ |

原则：全部为可选字段 / 新表 / 新工具；`ALLOWED_TRANSITIONS`、现有工具、ledger/flow/DAG 不动。

---

## 状态

- [ ] 监督员审核五问答复
- [ ] 审核通过 → plan 转 APPROVED_FOR_IMPLEMENTATION
- [ ] 用户批准 → 3082 lab 实施与验证 → 交接 → 3083 应用
