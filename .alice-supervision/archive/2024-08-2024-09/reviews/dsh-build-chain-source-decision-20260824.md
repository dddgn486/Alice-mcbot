# DSH 构建链路二审：开发源与 TypeScript 门禁决策

- 审核日期：2026-08-24
- 审核对象：`.alice-supervision/dsh-build-chain-freeze-report-20260824.md`
- 关联计划：`20260824-dsh-workflow-reliability-v1`（仍为 `DRAFT`）
- 结论：**需要用户决策；不接受当前上游 HEAD 的全局 typecheck 例外。**

## 已核对事实

1. 原先指定的 DSH npm 安装目录是发布产物，不是可重复构建的插件开发源；这一发现成立。
2. 维护员已找到独立源码仓库 `https://github.com/MistyBridge/dsh-agent-bus.git`，本地 clone 为 `/home/fb486/projects/dsh-agent-bus-src`，当前候选 commit 为 `5e3d2c895a85b20dee6aa74fa1c05ea4574fd6b4`。
3. 该候选 commit 比部署的 npm `dsh-agent-bus@0.1.1` 更新，且 package version 仍为 `0.1.0`；因此不是可直接认定为 3083 当前运行版本的同源基线。
4. `pnpm run build` 在候选 HEAD 的 TypeScript 阶段报 237 个错误，其中 176 个为 `src/tools.ts` 的 implicit any，54 个为独立 clone 缺失 monorepo peer 类型；`tsdown` 仍产出 JavaScript，`node --check` 通过。

## 审核判断

### 不接受“上游 HEAD typecheck 全局例外”

P0 将修改 `spec`、`ledger`、尤其是 `tools`。当前 typecheck 错误大量集中于 `src/tools.ts`，恰好与 P0 改动面重叠。即使这些错误早于本轮，也无法可靠区分“上游既有错误”与“P0 新引入错误”。

因此以下交付标准不可接受：

```text
JavaScript 产物可生成 + node --check 通过 = P0 可部署
```

它不足以满足本轮“可靠性改造”的质量目标，也不能作为 3083 部署依据。

### 推荐开发源策略：先匹配已部署的 npm 0.1.1 源码

优先选择与 3082/3083 部署版本严格对应的 `dsh-agent-bus@0.1.1` tag 或 release commit，而不是使用最新上游 HEAD。

采用的前提门禁：

1. 必须证明 tag/release commit 与 npm 0.1.1 tarball 的源码和版本对应关系；不能仅凭名字相近。
2. 在该基线上完成独立 clone 安装，并执行 `pnpm run build`。
3. 若 typecheck 可通过，才可作为 P0 的唯一开发基线。
4. 若 typecheck 仍因独立 clone 缺 monorepo peer types 失败，必须建立与上游一致的完整 workspace/peer 依赖开发环境；不能以手工 `any`、跳过 tsc 或直接复制产物解决。
5. 若无法得到与已部署版本匹配、可重复且 typecheck 通过的源构建链，P0 停止在 `DRAFT`，要求维护员重新规划；不部署 3082/3083。

## 对当前 DRAFT plan 的必要修订

在用户选择并完成上述“版本匹配 + typecheck”门禁前：

- 不得将 `/home/fb486/projects/dsh-agent-bus-src` 的最新 HEAD 写为 P0 已冻结基线。
- 不得接受当前 `5e3d2c8` 的 237 个 typecheck 错误为常规例外。
- 将 plan 的开发源描述从“DSH npm checkout”修正为“经版本匹配验证的 `dsh-agent-bus` 源码仓库 checkout”；发布包目录仍只作部署目标/只读比对。
- 3082 V10 应增加：`pnpm run build` 的 tsc 与 bundler 均成功，且源码版本、包版本、tarball/release 证据和产物 SHA-256 完整记录。

## 用户决策请求

建议用户批准以下保守路径：

> 不接受当前上游 HEAD 的 typecheck 例外。维护员先定位并验证与部署的 npm `dsh-agent-bus@0.1.1` 严格匹配的 tag/release commit，在可重复且 `pnpm run build` typecheck 通过的基线上实施 P0。若不能建立该构建链，停止 P0 并重新规划，不部署 3082 或 3083。

这不是 Alice 产品功能变更；它仅决定 DSH P0 的开发源和质量门禁。用户批准后，维护员仍只可进行“基线定位/构建验证”，而非立即实现 P0；P0 的实际实现仍需计划转为 `APPROVED_FOR_IMPLEMENTATION`。
