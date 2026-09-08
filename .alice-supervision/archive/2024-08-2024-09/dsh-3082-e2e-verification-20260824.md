# 3082 GUI 端到端点验手册（4 个新工具）

- 测试人：`user`（浏览器实际操作）
- 环境：3082 lab（`http://127.0.0.1:3082`），独立 profile `alice-bus-lab`，已加载 P0 的 4 个新工具
- 关联：P0 二审裁决「带条件通过」处理点 3
- 目的：确认 agent 工具面完整——4 个新工具在真实 agent 会话中可见、可调用、返回正确、不改变任务生命周期

---

## 前置

1. 浏览器打开 **`http://127.0.0.1:3082`** （3082 lab GUI，与 3083 主实例隔离）
2. 确认能连上、能发起 agent 会话（若已有会话直接复用）
3. 全程在 **3082** 操作，**不要切到 3083**（3083 未部署 P0）

---

## 点验项

### 工具 1：`check_required_skills`
- **用途**：审计某任务声明的 required_skills 是否满足（对照项目 `skills-manifest.yml`）
- **调用参数**：`task_id`（目标任务 id）
- **操作**：在 agent 会话里让 agent 调用 `check_required_skills`，传一个任务 id（可先建一个声明了 required_skills 的任务，或用一个已有任务）
- **预期**：返回该任务所需技能及审计结论（满足/缺失）；若任务未声明 required_skills，返回空/提示
- **记录**：▲工具面可见 ✓ / 返回正确 ✓ / 是否改变任务生命周期（应否）

### 工具 2：`check_evidence_state`
- **用途**：校验 evidence_state 转换是否合法（对照项目 `state-machine.yml`），只报告不裁决
- **调用参数**：`task_id`、`from_state`、`to_state`
- **操作**：让 agent 调用 `check_evidence_state`，测两例：
  - **非法**：`from_state=SERVER_VERIFIED`、`to_state=USER_ACCEPTED`（client_visible 违规）→ 应**报告违规**（不转状态）
  - **合法**：`from_state=SERVER_VERIFIED`、`to_state=SERVER_VERIFIED` 或状态机允许的转换 → 应**输出允许**，不阻塞
- **预期**：非法给出违规提示；合法输出允许；两种情况都**不写字段、不自行转状态**
- **记录**：▲非法报告违规 ✓ / 合法输出允许 ✓ / 不裁决 ✓

### 工具 3：`git_sync_check`
- **用途**：只读检查远端配置、工作树 dirty、未记录手工复制标记
- **调用参数**：无（或可空）
- **操作**：让 agent 调用 `git_sync_check`
- **预期**：返回 WSL 远端配置、工作树 dirty 状态、紧急标记状态；**不改任何文件**
- **记录**：▲返回完整 ✓ / 只读（改动后无变化）✓

### 工具 4：`record_emergency_sync`
- **用途**：登记紧急操作（源 commit、目标路径、SHA-256、操作者、原因、恢复计划），写 `.alice-supervision/emergency/`
- **调用参数**：`source_commit`、`target_path`、`sha256`、`operator`、`reason`、`recovery_plan`
- **操作**：让 agent 调用 `record_emergency_sync`，传一条测试记录（如 source_commit=`105b9df`、target_path=任一路径、sha256=任一位、operator=tester、reason="3082 点验"、recovery_plan="回滚..."）
- **预期**：写入 `.alice-supervision/emergency/`（在 Alice 项目侧的 3082 视角），字段完整可读
- **记录**：▲登记成功 ✓ / 文件生成且字段完整 ✓ / 可被读取 ✓

---

## 记录格式（每项填写）

对 4 个工具各记录：

```
工具 N：<工具名>
- 工具面可见：是/否
- 调用成功：是/否（截 agent 调用返回）
- 返回内容正确：是/否（简述实际返回 vs 预期）
- 是否改变任务生命周期：否（应为否）
- 截图/日志：<可选，最新返回或截图>
```

## 完成后

回到本会话，把 4 项点验结果告诉我（是/否 + 简述）。我汇总后作为 P0 二审处理点 3 的闭合证据，然后报请你批准部署 3083。

---

## 注意

- 本次点验**只在 3082**，**不部署、不改配置**、不触碰 3083 与主 profile。
- 若工具在工具面里找不到，或调用报错，请记录该现象（这正说明验证缺口存在，需修复）。
- 点验是**只读/登记类**操作，不改变 Alice 业务代码。
