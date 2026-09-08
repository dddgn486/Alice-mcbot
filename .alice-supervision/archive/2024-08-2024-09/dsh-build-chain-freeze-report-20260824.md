# DSH 可靠性改造 — 构建链路冻结报告（开发源选择选项 1）

- 日期：2026-08-24
- 提交方：DSH 工作流维护员
- 状态：构建链路已建立，**发现上游 HEAD typecheck 失败，需监督员决策**
- 关联 plan：`dsh-workflow-reliability-plan-20260824.md`（DRAFT，等待用户批准）

---

## 1. 背景

监督员二审指定的开发源 `/home/fb486/.nvm/.../@deepseek-ai/dsh/` 经预检确认为 **npm 发布包**（无 .git、无构建脚本、纯编译产物），无法建立构建链路。经用户选择**选项 1**：以 `dsh-agent-bus` 独立源码仓库为开发源。本报告记录该选择的构建链路冻结结果。

## 2. 开发源

- 仓库：`https://github.com/MistyBridge/dsh-agent-bus.git`
- 本地 clone：`/home/fb486/projects/dsh-agent-bus-src`
- **checkout commit**：`5e3d2c895a85b20dee6aa74fa1c05ea4574fd6b4`（2026-08-24 01:29 +0800）
  - subject: `feat: manual archive for tasks and flows; 20-char naming caps; workspace-member rename/archive auth; fix create_member model`
  - 注：HEAD 比 npm 0.1.1 新（含 manual archive / rename-archive auth 等上游新功能），package.json version 字段仍为 0.1.0 未 bump
- 源码结构：`src/*.ts`（TS 源码）、`tests/`（vitest）、`tsdown.config.ts`、`tsconfig*.json`

## 3. 可重复构建链路（已冻结）

### 3.1 环境

- node: v22.23.2（nvm）
- pnpm: 11.21.0
- registry: `registry.npmmirror.com`（.npmrc）

### 3.2 构建命令（按序）

```bash
cd /home/fb486/projects/dsh-agent-bus-src

# 1) 依赖安装（首次需 approve esbuild 构建脚本）
pnpm install
pnpm approve-builds --all     # 写入 pnpm-workspace.yaml: allowBuilds.esbuild=true

# 2) 完整构建（fingerprint + tsc x2 + tsdown）
pnpm run build
```

- 独立 clone 需本地 `pnpm-workspace.yaml`（`packages: [.]` + esbuild allow）才能在无 monorepo `../packages` link 时安装；该文件属构建环境配置，已记录。
- 产物输出到 `lib/`（22 个文件）+ `lib/build-fingerprint.json`。

### 3.3 产物标识（本次构建）

| 项 | 值 |
|---|---|
| checkout commit | `5e3d2c895a85b20dee6aa74fa1c05ea4574fd6b4` |
| fingerprint id | `d49ea814a763733e` |
| buildTime | `2026-08-23T17:48:18.834Z`（UTC） |
| 完整产物哈希（lib/ 全部文件） | `b56e9c0d8b9d2141887e5015185bf4ec445a2a6474309f3b683a02b10439edd9` |
| lib/index.js | `2a0293c27ec8f8e02cfc8cc2f85e63d58c6c8dc2b14bbd18d641817f8be48962` |
| lib/ledger.js | `323beab5c1cce0b8c1370903a2e4d6573f8d0b4c6d6e8e44a823283653b144b0` |
| lib/spec.js | `b15cb0037f8bb463c8b9f7ed4f1d9ef510f86300ba28b657f822e4dc593d4bd3` |
| lib/tools.js | `90c47a5a6e0472d0b9cdcfc0e0289de3ff637e4d09003f960ebe683a12ddbadd` |
| lib/panel.js | `10715067670be273e289af6fd9b70fc1d1e1328de848e8d2a0e97f00971d7377` |

### 3.4 关键模块语法验证

`node --check` 全部通过：tools.js / ledger.js / spec.js / panel.js / index.js ✅

## 4. ⚠️ 阻塞点：上游 HEAD 无法通过自身 typecheck

`pnpm run build` 中 `tsc -p tsconfig.json` 阶段失败，**237 个错误全部来自上游 HEAD 代码，非本改造引入**：

| 错误类 | 数量 | 说明 |
|---|---|---|
| TS7006（implicit any） | 176 | 上游 `src/tools.ts` 新工具（archive_task 等）参数无类型标注 |
| TS2307（模块缺失） | 54 | 独立 clone 缺 peerDependencies 类型（`@deepseek-ai/dsh-session`、`cordis` 等，monorepo link 才有） |
| 其他（TS7031/2664/2345/18046） | 9 | 同上 |

- 产物 `lib/` 仍由 tsdown 生成（tsc 失败后 tsdown 继续），关键模块可加载、语法正确。
- 但「typecheck 通过」这一质量门禁**当前无法满足**。

### 可能出路（需监督员选择）

1. **接受产物 + 记录 typecheck 例外**：tsc 失败仅上游 implicit any，产物可用；把 typecheck 例外写入验证记录，改造成果交付时同时标注。推荐用于本轮（我们只改 spec/ledger/tools 的有限区域）。
2. **从 npm 0.1.1 版源码分支开发**：checkout `0.1.1` tag（若有）或对应 commit，类型错误可能更少，但会失去上游 HEAD 的新功能。
3. **补 peer 类型**：把 monorepo link 依赖装全（需整个 harness checkout），工程量大。
4. **先修上游 implicit any**：属额外改动，超出本 P0 范围，不推荐。

## 5. 下一步（等待监督员决策 + 用户批准）

- [ ] 监督员对「typecheck 例外 or 分支选择」的决策
- [ ] 用户对 plan 的批准（转 APPROVED_FOR_IMPLEMENTATION）
- [ ] 批准后：在 `dsh-agent-bus-src` 实现 P0 改动 → 构建 → 部署 3082 lab → V1–V11 验证 → 呈报 → 用户确认后 3083 受控部署

## 6. 未触碰

- 未修改任何 Harness / 3083 / profile node_modules ✅
- plan 保持 DRAFT ✅
- 3082/3081 未受影响 ✅
