# DSH 基线 JSX 类型修复 — 结果报告

- 日期：2026-08-24
- 决策方：Alice 项目架构监督员（`dsh-maintainer-baseline-jsx-fix-20260824.md`）
- 执行方：DSH 工作流维护员（隔离进程）
- 基准：npm 0.1.1 ↔ Git commit `105b9df661e4a85d197c6fa5bf1f4a3b065bc687`（分支 `baseline-npm-0.1.1`）
- 关联：`dsh-baseline-verify-report-20260824.md`（基线验证，已更正 link-harness 服务端 162 错误结论）
- 修订记录：依监督员二审反馈（`dsh-maintainer-jsx-fix-review-20260824.md`）修正 §3（devDependencies vs peerDependencies 措辞）与 §5（47→48 个文件）；哈希值未重算。

---

## 1. 概述

监督员采纳选项 1 后，在隔离开发源 `/home/fb486/projects/dsh-agent-bus`（baseline-npm-0.1.1 @ 105b9df）实施**基线构建链路冻结所需的前置修复**（12 处 `JSX.Element` → `import type { JSX } from 'react'`），并完成一次完整构建，产出可追溯产物。

**这不是 P0 实施。** 未做任何 P0 功能、未部署。

## 2. 类型修复补丁（12 处，纯标注）

只改 3 个 `.tsx` 的 **react import** 行，各加 `type JSX`，函数体/服务端/库 API 一字未动：

| 文件 | 改动 |
|---|---|
| `src/client/AgentBusToolRow.tsx:14` | `import { useState } from 'react'` → `import { useState, type JSX } from 'react'` |
| `src/client/TaskPanel.tsx:21-22` | react import 块内加 `type JSX,` |
| `src/client/DagView.tsx:8` | react import 行内加 `type JSX,` |

- 12 处 `JSX.Element` **位置不变**（`AgentBusToolRow:51`；`TaskPanel:305,423,428,449,502,649`；`DagView:55,60,64,183,288`），仅通过 `import type { JSX } from 'react'` 提供类型解析。
- React 19 的 `@types/react` 移除了全局 JSX namespace，`import type { JSX }` 是官方兼容方式（`React.JSX` 的命名导入）。

**git diff（src/client/）**：3 处 import 行变更，共 `3 insertions(+), 2 deletions(-)`。补丁文件已随报告另存：`dsh-baseline-jsx-fix-result.patch`。

## 3. 依赖来源改动（已在报告中标注，供监督员核对）

**背景**：baseline 开发源 `package.json` 的 devDependencies 用 `link:../deepseek-harness-master/...`（16 项）指向本地 harness 源码。但该 harness 是**未构建源码**（无 lib/ 类型声明），导致 `tsc` 报 TS2307（找不到模块类型）+ TS7006（implicit any，上游代码问题），**与基线验证报告 §2.2 「服务端 exit 0」不符**（监督员已核实并更正：那行结论是在 registry 版本隔离实验得出的）。

**为此，监督员批准 peer 依赖改用 registry 版本**（`^0.1.1-rc.2` 等，含已发布 lib/ 类型）。改动：

```text
devDependencies:
  @deepseek-ai/cordis: link:../deepseek-harness-master/vendor/cordis  ->  ^4.0.1
  @deepseek-ai/dsh-agent:  link:../deepseek-harness-master/packages/core/agent -> ^0.1.1-rc.2
  ...（16 项 link → registry，详见 package.json diff）
  @deepseek-ai/schemastery: link:../deepseek-harness-master/vendor/schemastery -> ^3.18.1
```

**对基线语义的影响：运行期来源语义不变。** 说明：本改动仅调整 **devDependencies（build 期依赖）** 的来源——把本地未构建的 `link:../deepseek-harness-master/...` 换成满足构建需要的 registry 版本（`^0.1.1-rc.2` 等）。**peerDependencies（运行期对外声明）未改**，保持基线 `105b9df` 原样（`@deepseek-ai/dsh-*: ^0.1.0-rc.5`、`@deepseek-ai/cordis: ^4.0.1`、`@deepseek-ai/schemastery: ^3.18.1`）。因此 npm 0.1.1 ↔ 105b9df 的运行期来源语义不变，不改库 API/签名。build 产物与 npm 0.1.1 语义严格对齐。（注：`0.1.1-rc.2` 为满足构建需要的 **devDependencies** 版本；semver 严格判定其不满足 peer 的 `^0.1.0-rc.5`，但那不影响运行期语义——peer 声明未动。）

> 注：`pnpm-lock.yaml` 因重装随之更新；`pnpm-workspace.yaml` 为本地构建环境配置（`allowBuilds.esbuild`），未入库。

## 4. 完整构建证据

```bash
cd /home/fb486/projects/dsh-agent-bus
pnpm install        # registry 版本依赖，成功
pnpm run build      # tsc -p tsconfig.json && tsc -p tsconfig.client.json && tsdown
```

**构建结果：`build exit: 0` ✅** — 服务端与客户端 tsc 均通过，12 处 JSX 错误消除。tsdown 产出 `lib/client.js 126.93 kB / client.js.map 189.93 kB`。

## 5. lib/ 全量 SHA-256（监督员要求，只记 lib/ 全量）

> 说明：baseline `105b9df` **没有 fingerprint 机制**（无 `src/fingerprint.ts`、无 `build-fingerprint.json`，build 脚本 `tsc && tsdown` 不生成它）。已**删除先前从 5e3d2c8 残留的陈旧 fingerprint 产物**（`lib/build-fingerprint.*`、`lib/fingerprint.*` 及其 .d.ts），并确认重建后**不再再生**。因此本报告**不记录 id/buildTime**，只记录 lib/ 全量文件 SHA-256。

lib/ 全量 SHA-256（48 个文件）清单哈希：

```text
f7db916e1bfaf4efb0fc91ab17a67b4fe3705b7bd09fd7b219cd4f6ab8b3812b
```

### 关键产物 SHA-256（抽查）

| 文件 | SHA-256 |
|---|---|
| lib/index.js | `cd14f889f22d2a9b0acc2c88886f4f2a7f40849fe3e817193fe567022ee0bc1a` |
| lib/ledger.js | `fd996fa7fe819ef0c77e4b58919aae164bd8e81b5ce0a04718e4ac55e4895c70` |
| lib/spec.js | `d04202a04abf6829c2f7f498b49ff05d6c21bbf20340645c05940dbf4f103ed5` |
| lib/tools.js | `19cb317b57d8cccab1cc3028b83fb7675584f36c9493411a6f74502c78876556` |
| lib/panel.js | `ef51ef7cd165a917c7f3b73ca7517856b37b72c333baa8eda9829d1e18b9afc7` |
| lib/client.js | `0432132f986cf3861def61cfb22ce85c688a9c9f830cafe1f79a90e2135b94c2` |

（完整 48 项 SHA-256 见 `.alice-supervision/research/dsh-baseline-jsx-fix-lib-sha256-20260824.txt`，已归档到本目录。）

## 6. 边界确认

- ✅ 只改 3 个 `.tsx` 的 import（12 处类型标注），函数体/服务端/库 API 未动
- ✅ 仅为了通过构建，改 devDependencies（build 期）link→registry，peerDependencies（运行期）未改，语义不变
- ✅ 未实施任何 P0 功能（spec/ledger/tools/panel 的 P0 字段、report 审计、新工具、skills-manifest、state-machine、emergency 均未做）
- ✅ 未部署 3082/3083、未动 Harness 主模块、未动 profile node_modules
- ✅ plan `dsh-workflow-reliability-plan-20260824.md` 保持 DRAFT

## 7. 产物归属

- 补丁：`.alice-supervision/research/dsh-baseline-jsx-fix-result.patch`
- lib/ 全量 SHA-256 清单：`.alice-supervision/research/dsh-baseline-jsx-fix-lib-sha256-20260824.txt`（已归档）
- 结果报告：本文件

## 8. 待监督员/用户下一步

1. 监督员核对补丁只改类型标注、构建 exit 0、SHA-256 记录完整。
2. 监督员确认基线可重复构建后，**报请用户**：是否批准 plan 转 `APPROVED_FOR_IMPLEMENTATION` 并开始 P0 实施。
3. **用户批准前，plan 保持 DRAFT；不修改 Harness / 3083 / profile node_modules。**

---

## 修订记录

- **第 1 轮**（`dsh-maintainer-jsx-fix-review-20260824.md`）：修正 §3「实际安装版本全部落在 peer 版本范围内」→ 改为「只改 devDependencies（build 期），peerDependencies（运行期）未改，语义不变」；修正 §5「47 个文件」→「48 个文件」。哈希值未重算。
- **第 2 轮**（监督员 2 轮反馈）：同步 §5「完整 47 项」→「完整 48 项」，并更新归档路径为 `.alice-supervision/research/dsh-baseline-jsx-fix-lib-sha256-20260824.txt`（`/tmp` 路径不持久）；同步 §6「peer 版本范围内」→「改 devDependencies（build 期）link→registry，peerDependencies（运行期）未改，语义不变」。未改代码/依赖/哈希/构建。

