# 监督员决策：采纳基线验证报告选项 1（最小类型修复）→ 解冻构建链路

- 日期：2026-08-24
- 决策方：Alice 项目架构监督员
- 交接对象：DSH 工作流维护员（隔离进程）
- 关联文档：`dsh-baseline-verify-report-20260824.md`（基线验证报告，维护员 02:02 提交，待选）
- 关联 plan：`dsh-workflow-reliability-plan-20260824.md`（DRAFT，保持，未获 P0 实施授权）

---

## 决策结论

**采纳选项 1：在隔离开发源实施最小客户端类型修复（12 处 `JSX.Element` → React 19 兼容类型）。**

这是解冻 P0「构建链路冻结」（plan 步骤 3）所需的**前置修复**，**不是 P0 实施授权**。修复后基线 `105b9df` 才能在完整 workspace 下产出**可重复构建、可追溯**的产物，P0 才能在其上启动。

## 决策依据（监督员二审）

1. **根因非本改造引入**：12 处 TS2503 `Cannot find namespace 'JSX'` 全部来自上游 `src/client/*.tsx` 使用裸 `JSX.Element`，因 `@types/react@19.2.18` **移除了全局 JSX namespace**（React 18 → 19 的官方破坏性变更）。非 P0、非维护员改造、非 Alice 业务引入。

2. **纯类型标注、非行为**：12 处全部是函数返回类型标注 `): JSX.Element`，**编译期擦除**，不产生任何运行逻辑或 JS 字面量痕迹。修复后产物与 npm 0.1.1 的**语义严格对齐**，不改变任何工具/任务/flow 行为。

3. **隔离实施、不碰已部署组件**：修复在**隔离开发源** `/home/fb486/projects/dsh-agent-bus`（分支 `baseline-npm-0.1.1`，commit `105b9df`，npm 0.1.1 严格对应）上进行。**不动**：已安装 DSH checkout（`/home/fb486/.nvm/.../@deepseek-ai/dsh/`）、profile node_modules、3083、Harness 主模块。不部署任何环境（3082/3083 均不动）。

4. **构建链路冻结的前置**：基线验证报告 §2.2 已确认服务端 `tsc -p tsconfig.json` 通过（exit 0），仅客户端 12 错误。修复后完整 build 即可产出 `lib/` + `build-fingerprint.json`，满足 plan 步骤 3「可重复构建/可部署路径」门槛。

## 要求（维护员执行时遵守）

1. **只改 3 个 .tsx 的 12 处返回类型标注**：
   - `src/client/AgentBusToolRow.tsx:51`
   - `src/client/TaskPanel.tsx:305,423,428,449,502,649`
   - `src/client/DagView.tsx:55,60,64,183,288`
   改为 `): React.JSX.Element`（或顶部 `import type { JSX } from 'react'` 后保留 `JSX.Element`，二选一，整文件统一）。**不改函数体逻辑、不改非类型标注、不改服务端 `src/*.ts`、不改库 API/签名。**

2. **生成补丁**：`git diff > /home/fb486/projects/alice/.alice-supervision/research/dsh-baseline-jsx-fix-20260824.patch`（供监督员核对只改类型标注）。

3. **完整构建**：`cd /home/fb486/projects/dsh-agent-bus && pnpm install && pnpm run build`，确认服务端与客户端 tsc 均 exit 0（12 错误消除），记录 `lib/` 全量文件 SHA-256。

4. **产出结果报告**：写 `/home/fb486/projects/alice/.alice-supervision/dsh-baseline-jsx-fix-result-20260824.md`，含补丁、构建证据、lib/ 全量 SHA-256。

## 决策更正 1：peer 依赖环境采用 registry 版本（用户批准）

监督员已独立核实并**确认维护员反馈属实，且它更正了基线验证报告的一处事实错误**。

- **事实**：`dsh-agent-bus` 的 `devDependencies` 用 `link:../deepseek-harness-master/...`（16 项），但 harness 源码**未构建**（无 `lib/` 目录，而 `package.json` 的 `types` 指向 `lib/types/index.d.ts`）→ link 环境下服务端 `tsc -p tsconfig.json` 报 **162 错误**（116 implicit any + 39 module missing + 6 other）。监督员实测与维护员报告精确一致。
- **基线报告 §2.2（第 41 行）声称"服务端 tsc 通过（exit 0）/237 错误全部消失"与事实不符**——该结论是在 registry 版本隔离实验中得出，而非 link-harness。此点应记录为基线报告的**更正**。
- **决策（用户批准）**：peer 依赖环境**采用 registry 版本**，改用 `^0.1.1-rc.2`（含已发布 `lib/types`），以 `pnpm install && pnpm run build` 使服务端 + 客户端 tsc 均 exit 0。这是「完整 peer 依赖」的**已构建可用形式**。
- **不做方案 B**（先构建 harness 源码再 link）：较重、需额外构建 harness、当前 install 失败，且更偏离最小路径。

## 决策更正 2：fingerprint 只记 SHA-256，不记 id/buildTime（用户批准）

- **事实**：baseline `105b9df` **无 fingerprint 机制**——无 `src/fingerprint.ts`、build 脚本（`tsc && tsc && tsdown`）不生成 fingerprint、无 `lib/build-fingerprint.json`。fingerprint 只存在于 **HEAD `5e3d2c8`**（该 commit 才引入 `src/fingerprint.ts`）。
- **决策（用户批准）**：产物**以实际 build 输出的 `lib/` 全部文件 SHA-256 为准**，**不记录 id/buildTime、不凭空发明 fingerprint**。维护员在结果报告中**不得**编造 fingerprint 标识，也不得给 baseline 打 fingerprint 机制补丁（那会超出"纯类型修复"范围、污染基线对应关系）。

## peer 依赖路线落实（维护员执行）

采用 registry 版本时，涉及 `package.json` 的 `devDependencies` 中 `link:` 项。请确认：改用 registry 版本应在**隔离开发源**的 `package.json`/`pnpm-lock` 上做临时调整（如 `pnpm install` 时用 registry 覆盖，或临时把 dev 依赖的 link 替换为 registry 版本），保持对**已安装 DSH checkout / profile node_modules / 3083** 的不触碰。构建命令以能产出通过 tsc 的 `lib/` 为准，最终记录实际采用的依赖方案与 SHA-256。若此临时依赖调整需要改动 `package.json`，请在结果报告中**明确标注该改动（来源与 diff）**，供监督员核对它不影响「基线对应关系」（npm 0.1.1 ↔ 105b9df 语义）。

## 明确不做（本轮边界）

- **不实施任何 P0 功能改造**：`spec.js/ledger.js/tools.js/panel.js/index.js` 的 P0 可选字段（`required_skills`/`skill_usage`/`evidence_state`/`client_visible`）、`report_task` 审计、新工具（`check_required_skills`/`check_evidence_state`/`git_sync_check`/`record_emergency_sync`）、`skills-manifest.yml`、`state-machine.yml`、`emergency/` 目录等，**全都不做**。
- 这些属于 plan 转 `APPROVED_FOR_IMPLEMENTATION` **且用户批准**之后的 P0 实施步骤。
- 不在 `/home/fb486/projects/dsh-agent-bus-src`（已不存在）操作；不部署任何环境。

## 到下一个审批点的路径

1. 维护员完成如下 → 产出补丁 + 结果报告：
   - 应用 12 处类型修复（补丁已产出，仅 3 个 import 的 `type JSX`，监督员已核实通过）
   - peer 依赖采用 **registry 版本**（`^0.1.1-rc.2`），`pnpm install && pnpm run build` 使服务端+客户端 tsc 均 exit 0
   - 记录 `lib/` 全量 SHA-256（**不记录 fingerprint id/buildTime**），结果写入 `.alice-supervision/dsh-baseline-jsx-fix-result-20260824.md`
   - 如临时调整 `package.json` 依赖来源，在结果报告中标注改动与 diff
2. 监督员核对：补丁只改类型标注、构建通过、SHA-256 记录、peer 依赖方案合规。
3. 监督员确认基线可重复构建后，**报请用户**：是否批准 plan 转 `APPROVED_FOR_IMPLEMENTATION` 并开始 P0 实施。
4. **用户批准前，plan 保持 DRAFT；不修改 Harness / 3083 / profile node_modules。**

---

**本决策文档不构成 P0 实施授权。它只授权「基线构建链路冻结」所需的 12 处类型标注修复 + 一次完整构建（registry 版本 peer 依赖 + 只记 SHA-256，两项均经用户批准）。**
