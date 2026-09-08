# DSH 可靠性改造 — 基线验证报告（npm 0.1.1 ↔ 源码 commit）

- 日期：2026-08-24
- 提交方：DSH 工作流维护员
- 状态：**基线定位完成；typecheck 无法通过，需监督员决策**
- 关联 plan：`dsh-workflow-reliability-plan-20260824.md`（DRAFT，保持）

---

## 1. 已确认的基线对应关系

| 项 | 值 |
|---|---|
| npm 包 | `dsh-agent-bus@0.1.1`（2026-08-20 07:10 UTC 发布） |
| npm `gitHead` | `105b9df661e4a85d197c6fa5bf1f4a3b065bc687` |
| tarball SHA-1（npm shasum） | `68b129c750ac1261c4e0a7dc21dcdfbab0b518cf` ✅ 与 npm 声明一致 |
| 本地源码分支 | `baseline-npm-0.1.1` @ `105b9df`（2026-08-20 10:57 +0800，`docs: add a task-log section to the README`） |
| 源码 ↔ tarball 对应 | tarball `lib/` 的 15 个模块全部能在 105b9df 的 `src/*.ts` 找到 ✅ |

**结论：npm 0.1.1 ↔ git commit `105b9df` 的对应关系已严格验证。**

## 2. 构建链路（完整 workspace 环境）

### 2.1 依赖环境（监督员要求「完整 workspace/peer 依赖环境」）

- `dsh-agent-bus` 源码 devDependencies 硬编码 `link:../deepseek-harness-master/...`（16 项），
  指向开发者本地的 harness checkout（peer 类型来源）。
- 已建立：
  - `/home/fb486/projects/deepseek-harness-master/`（harness monorepo 完整 checkout，sparse 全量拉取 vendor/packages/apps）
  - `/home/fb486/projects/dsh-agent-bus/`（bus 源码，baseline-npm-0.1.1 分支）
- 16 个 link 路径全部解析 ✅（vendor/cordis、packages/core/session 等）

### 2.2 构建结果

```bash
cd /home/fb486/projects/dsh-agent-bus
pnpm install          # ✅ 通过，link 依赖解析
pnpm run build        # ❌ 失败：tsc -p tsconfig.client.json
```

- **服务端 `tsc -p tsconfig.json`：✅ 通过（exit 0）**——之前的 237 个错误（176 implicit any + 54 模块缺失）在完整 workspace 环境下全部消失
- **客户端 `tsc -p tsconfig.client.json`：❌ 12 个 TS2503 `Cannot find namespace 'JSX'`**

## 3. ⚠️ 核心阻塞：客户端 JSX 类型错误（上游代码本身）

- bus 源码 `src/client/*.tsx` 使用裸 `JSX.Element`（12 处）
- 上游 devDependencies 声明 `@types/react ^19.2.18`，lockfile 固定 `19.2.18`
- **@types/react 19 的 `namespace JSX` 不再 `declare global`**（React 18 → 19 的破坏性变更），裸 `JSX.Element` 无法解析
- 验证：装 `types: ["react","react-dom"]`、`@types/react 19.2.18` 后仍报同样的 12 个错误
- npm 0.1.1 tarball 的 `lib/client.js` 无 build-fingerprint，且编译产物无 JSX 痕迹——发布时环境与当前源码 build 流程不完全一致

**结论：`105b9df` 基线在当前完整 workspace 环境下无法通过客户端 typecheck，错误全部来自上游 client 代码的 React 19 类型不兼容，非本改造引入。**

## 4. 监督员决策选项

1. **小修上游 client 类型**（12 处 `JSX.Element` → `React.JSX.Element` 或 `import type { JSX } from 'react'`）：
   改动仅在 `src/client/` 类型标注，不触及运行逻辑、不触及服务端与 P0 范围外的任何东西。
   这是让基线 typecheck 通过的最小、非行为改动。**推荐。**
2. 换更老 harness 版本（配 React 18 类型）：但上游 lockfile 已固定 19.2.18，且 0.1.1 发布时间对应的 harness 已是 React 19 时代，不可行。
3. 跳过客户端 typecheck：违反监督员「不得跳过 tsc」要求，否决。
4. 停止 P0 重新规划：若监督员认为连 12 行类型标注都不可接受。

## 5. 现状保证

- plan 保持 DRAFT ✅
- 未实施 P0、未部署 3082、未修改 3083、未编辑 profile node_modules ✅
- 隔离实验在 `/tmp/bus-build-test` 完成，未污染开发源（开发源在 `/home/fb486/projects/dsh-agent-bus`，其 package.json/lockfile 保持基线原样）✅

## 6. 若批准选项 1 后的构建标识（待最终构建后更新）

- checkout commit：`105b9df661e4a85d197c6fa5bf1f4a3b065bc687`（baseline-npm-0.1.1）
- 包版本：0.1.0（源码 version 字段，npm 发布时 bump 为 0.1.1）
- 构建命令：`pnpm install && pnpm run build`
- 产物路径：`lib/`（22 文件）+ `lib/build-fingerprint.json`
- SHA-256：**待选项 1 构建通过后计算**
