# P1-S1 只读核查：DSH 外部 MCP 挂载能力（V-A 文档核对）

- **任务**：`fafed6a3-5638-4c04-a964-da0da637535c`（P1-S1；对应用户补充调查的 P1 Git MCP 试点第一闸门）
- **依据规划**：`.alice-supervision/research/skill-upgrade-p1-git-mcp-line-20260824.md` §3.1（V-A 方法 + 判定标准 + 停止条件）
- **Alice 基线**：`ab510fd`
- **日期**：2026-08-24
- **执行边界**：只读；未安装/下载/连接任何 MCP server 或 endpoint；未修改任何文件（含 DSH checkout、profile、业务代码）；未触碰 3082/3083 运行实例。
- **对象**：`/home/fb486/.nvm/versions/node/v22.23.2/lib/node_modules/@deepseek-ai/dsh/`（DSH 安装）及其 `node_modules/@deepseek-ai/dsh-mcp-client`；`/home/fb486/.dsh/profiles/alice-bus/`（3083 profile）。

## 1. 结论摘要

**判定：接线点（wiring）存在且文档证据充分，但"挂载后行为"全部未实测 → 结论为「需维护员 dry-run 验证（V-B），且试点必须限定隔离 profile」；V-A 文档层面同时给出倾向性证据：仅隔离 profile 可行（部分可行），3083 内直接挂载不具备充分依据。**

具体：

- **已确认（文档/源码层面）**：dsh-mcp-client 插件已随 DSH 安装（`@deepseek-ai/dsh-mcp-client@0.1.1-rc.2`，MIT），可从 profile 目录经 `$DSH_HOME/profiles/node_modules` flat fallback 符号链接解析（实测 `require.resolve` 成功）；插件配置 schema（stdio/streamable-http、serverName、command/args/env/cwd、toolCallTimeoutMs、failOnStartupError、reconnect）、`ctx.tools` 注册命名 `mcp__<serverName>__<rawName>`、HMR 热交换（编辑 cordis.patch.yml 触发 disconnect/reconnect）均有 README 与 lib/index.js 源码行级证据。
- **待实测（必须 dry-run）**：实际挂载一个最小 stdio server 后，工具是否以 `mcp__<serverName>__<rawName>` 出现在会话工具清单；加载/卸载是否可回滚、是否对 3083 会话产生短暂扰动；`failOnStartupError=false` 的静默降级行为；stdio 子进程 spawn 与 env scrubbing 的实际表现；loader 解析走 `ctx.loader.internal` 还是 Node import 的行为差异（boot 未传 `bareModuleBaseUrl`）。
- **3083 现状**：`alice-bus` profile 无任何 MCP 配置（cordis.yml 空根、patch 仅 4 条、bundles 无 mcp）；web(3081)/alice-bus-lab(3082)/alice-bus(3083) 三个 profile 均无 MCP 先例；alice-bus-lab 是"隔离 lab profile + 临时禁用条目"的既有实验模式，可作为 V-B 隔离试点模板。

## 2. 核查内容（对应任务的 4 点）

### 2.1 dsh-mcp-client 的配置方式、注册命名、transport 支持 ✅ 已确认

**证据（`node_modules/@deepseek-ai/dsh-mcp-client/`）**：

| 证据项 | 内容 | 位置 |
|---|---|---|
| 版本/许可 | `@deepseek-ai/dsh-mcp-client@0.1.1-rc.2`，MIT，Ecosystem `main: lib/index.js` | `package.json` |
| 配置方式 | README §Usage："One plugin instance per MCP server in `cordis.yml`"；示例 `- id: mcp-github / name: '@deepseek-ai/dsh-mcp-client' / config: {serverName, transport: stdio, command, args, env}` 与 streamable-http 形式 | `README.md` 第 9-30 行 |
| Config schema | `Config = z.union([stdio 对象, streamable-http 对象])`；stdio 必填 `transport/command`，可选 `args/env/cwd/toolCallTimeoutMs/failOnStartupError/reconnect`；http 必填 `transport/url`；`serverName` 必填且 `[A-Za-z0-9_-]{1,32}` | `lib/index.js` 尾部 `Config` 定义 |
| 注册命名 | README："`ctx.tools` 注册为 `mcp__<serverName>__<rawName>`"（README 第 5/32/55 行）；`lib/index.js` `publicToolName()` 第 119-123 行：`mcp__${serverName}__${rawName}`，超长/非法字符时追加确定性 12-hex SHA-256 哈希 | `lib/index.js` |
| transport 支持 | `createTransport()` 第 39-49 行：`stdio` → `StdioClientTransport`（`buildChildEnv` 先对父环境做 credential scrubbing，README §Known Limitations 与 `buildChildEnv` 源码第 28-31 行）；`streamable-http` → `StreamableHTTPClientTransport` | `lib/index.js` 第 39-49 行 |
| HMR 热交换 | README 第 32 行："editing the entry triggers disconnect + reconnect without process restart；unchanged `serverName` reproduces identical tool names" | `README.md` 第 32 行 |
| 失败降级 | `failOnStartupError` 默认 `false`：初始连接/工具同步失败仅记日志、激活时无工具（不拒绝激活） | `README.md` 第 47 行 + `lib/index.js` apply() |
| 插件导出 | `export { Config, apply, inject, name }` —— 标准 Cordis 插件形态 | `lib/index.js` 末尾 |

### 2.2 DSH 插件框架中 MCP 插件如何被 cordis 加载 ✅ 已确认（接线点）

**证据（`DSH/lib/profile-boot-DG5t9aNs.js` + `@deepseek-ai/dsh-app-boot` + `@deepseek-ai/cordis-plugin-loader`）**：

1. **patch 合成**：`composeProfile()`（profile-boot 第 145-196 行）把 patch 栈按序合成：`bundlePatches → profile.patches（cordis.patch.yml）→ homePatches（$DSH_HOME/cordis.patch.yml）→ overlays（--patch）→ telemetry`；`allPatches()` 第 124-132 行。
2. **boot 挂载**：`runProfile()`（第 230-283 行）调用 `boot(NAME, rootConfig, allPatches, prepare)`；`boot`（`dsh-app-boot/lib/index.js` 第 1167-1176 行）创建 root Context 并 `mountRootInclude`；**未传第 5 参 `bareModuleBaseUrl`**（profile-boot 第 240 行 `const ctx = await boot(NAME, rootConfig, ...)` 只传 4 参）→ loader internal 解析缺省，裸 specifier 走 Node 默认解析（dsh-app-boot README 第 32-33 行："Bare plugin specifiers … resolve through the Cordis Loader's internal module loader. They resolve from the config directory by default"）。
3. **HMR 热加载**：`runProfile()` 第 264-270 行：`watchUserPatches(ctx, { filename: cordis.patch.yml, compose })` —— 编辑 patch 文件即热重载条目树（`dsh-app-boot` `watchUserPatches` 第 752-790 行：`hmr.registerConfig(filename, ...)` → `entry.update(...)`）。
4. **模块解析可达性（实测）**：
   - `node -e require.resolve('@deepseek-ai/dsh-mcp-client', {paths:['/home/fb486/.dsh/profiles/alice-bus']})` → **成功**解析到 DSH 安装内的 `lib/index.js`；
   - 原因：`$DSH_HOME/profiles/node_modules/@deepseek-ai/dsh-mcp-client` 是 `dsh-app-boot` `healProfilesModuleFallback()`（第 391-407 行）建立的 flat fallback 符号链接，指向 DSH 安装 node_modules（实测 `readlink` 确认）。
5. **入口树导入**：`@deepseek-ai/cordis-plugin-loader` `EntryTree.import()`（`lib/index.js` 第 259-269 行）：`ctx.loader.internal` 存在则 `internal.import(name, baseUrl)`，否则裸名/相对路径经 Node `import()`。
6. **先例核查**：web / alice-bus-lab / alice-bus 三个 profile 的 `cordis.patch.yml` 均**无任何 mcp 条目**（grep 无命中）；`dsh-base`/`dsh-web-app`/`dsh-headless` 的 bundle patch 也无 mcp 行（grep 无命中）→ **当前部署没有任何 MCP profile 实例先例**；但 `alice-bus-lab` 演示了"隔离 lab profile + 临时禁用条目（disabled: true）+ 独立 storage"的**隔离实验模式**（其 patch 注释"lab-only; 3081/3083 production … untouched"），是 V-B 的现成模板。

### 2.3 当前 3083 profile（alice-bus）的 MCP 相关配置 ✅ 已确认（无）

| 文件 | 内容 | MCP 相关 |
|---|---|---|
| `cordis.yml`（profile 根） | 空列表 `[]` + 注释"The tree is composed as patches… Edit cordis.patch.yml, not this file" | 无 |
| `cordis.patch.yml` | 4 个条目：`session-persistence-jsonl`（root=/home/fb486/projects/alice/.dsh-runtime/sessions）、`storage-json`、`agent-bus`（taskTimeoutMs=7200000 等）、`dsh-dafeiyu`（webuiUrl http://127.0.0.1:3083/） | **无 MCP 条目** |
| `package.json` | `dsh.profile.bundles`: `@deepseek-ai/dsh-base`、`@deepseek-ai/dsh-web-app`、`dsh-agent-bus`、`dsh-dafeiyu`；dependencies 仅 dsh-agent-bus/dsh-dafeiyu | **bundles 无 mcp**；dsh-mcp-client 不在该 profile 的依赖清单（仅在 DSH 安装级依赖列表） |

**结论**：3083 当前未挂载任何 MCP；若在 `cordis.patch.yml` 新增 `- id: mcp-git / name: '@deepseek-ai/dsh-mcp-client'` 条目，唯一需要的"安装级"前置是**插件模块可解析**（已实测可行，经 flat fallback），**不需要**修改 DSH checkout 或 profile node_modules —— 这是 V-A 文档层面最关键的有利证据。

### 2.4 判定（按规划 §3.1 判定标准）

| 判定选项 | 依据 | 结论 |
|---|---|---|
| 可行（3083 内直接挂载） | 文档显示接线点存在，但"注册/调用/名称合格/隔离可回滚/3083 无回归"全部未实测；且任一 patch 编辑都会触发 `watchUserPatches` HMR 重载，3083 是活跃会话 profile | **不判定可行** |
| 部分可行（仅隔离 profile） | 隔离 profile 模式有先例（alice-bus-lab）；patch 层 + `--patch overlays` 可完全隔离；插件解析不依赖修改 DSH 安装 | **文档层面倾向成立，但行为仍需 dry-run 证实** |
| 不可行 / 需停止 | 无证据显示必须改 DSH checkout/profile node_modules/端口；停止条件均未触发 | **不判定不可行** |
| **需维护员 dry-run 验证（V-B）** | V-A 已确认接线点存在但行为未实测；规划 §3.1 判定标准明确"任何通过即记录「待验证→可行」的等级" | **本阶段结论：需 V-B** |

**V-B 建议（给维护员/监督员，本任务不执行）**：新建独立 lab profile（克隆 alice-bus-lab 模板或新建 `alice-mcp-lab`），在**其自身** cordis.patch.yml 中挂载官方 `@modelcontextprotocol/server-everything`（只读部分）或 echo 型 stdio server，`serverName: gitlab`（示例），观察：
1. 工具是否以 `mcp__gitlab__<rawName>` 出现在该 profile 会话工具清单；
2. 卸载条目（`disabled: true` 或删除）后工具清单是否恢复、无残留后台进程/localhost 监听；
3. 全程不触碰 alice-bus(3083)/alice-bus-lab(3082)/web(3081) 的配置；
4. 验证后按规划 §3.1 判定标准给出最终等级（可行-隔离 profile）。

**按规划 §3.1 停止条件核验（本任务内均未触发）**：
- ❌ 未触碰已安装 DSH checkout 或 profile node_modules（只读）；
- ❌ 未重启/变更 3083 环境或部署端口；
- ❌ 无证据显示挂载会读取项目外敏感路径（Git MCP 的 repo_path 是 P1-S2 白名单问题，P1-S1 不涉及）；但注意 mcp-client `env` 配置会覆盖父环境（scrubbed），绝不在配置中注入 API key/token（README 示例用了 env token，属反例，P1-S3 白名单时必须排除）。

## 3. 已确认 vs 待实测清单

| 项 | 状态 | 证据位置 |
|---|---|---|
| 插件已安装、版本、许可（MIT） | 已确认 | dsh-mcp-client/package.json |
| 每 server 一插件实例的 cordis.yml 配置语法 | 已确认（文档） | README.md 第 9-30 行 |
| `ctx.tools` 命名 `mcp__<serverName>__<rawName>`（含规范化/哈希规则） | 已确认（源码） | lib/index.js publicToolName() |
| transport stdio/streamable-http + env scrubbing | 已确认（源码） | lib/index.js createTransport/buildChildEnv |
| HMR：编辑 patch 触发 disconnect/reconnect | 已确认（文档 + 框架源码） | README 第 32 行；profile-boot watchUserPatches |
| 插件从 profile 目录可解析（flat fallback） | 已确认（实测） | require.resolve + readlink |
| 当前 3083 无 MCP 配置 | 已确认 | alice-bus/cordis.patch.yml、package.json |
| 无已部署 MCP profile 先例；隔离 lab 模式有先例 | 已确认 | 三 profile patch grep；alice-bus-lab patch 注释 |
| 实际挂载后工具出现在会话工具清单 | **待实测（V-B）** | — |
| 加载/卸载可回滚、无残留进程 | **待实测（V-B）** | — |
| 对活跃 3083 会话的扰动（HMR 重载时序） | **待实测（V-B，限定隔离 profile 规避）** | — |
| loader internal vs Node import 的实际解析路径 | **待实测（V-B）**（boot 未传 bareModuleBaseUrl） | dsh-app-boot README 第 32-33 行 |
| failOnStartupError=false 静默降级对会话的影响 | **待实测（V-B）** | README 第 47 行 |
| 官方 Git MCP server（P1-S2 对象）在 stdio 下的 spawn/只读性 | **待实测（P1-S2，本任务不涉及）** | supplement 报告 |

## 4. 限制与风险（V-A 层面）

1. **"插件存在 ≠ 可安全挂载"**：本报告只确认接线点与解析可达性；工具注册、会话工具清单可见性、回滚性均需 dry-run。
2. **活跃 3083 的 HMR 扰动**：`watchUserPatches` 对 `cordis.patch.yml` 的任何编辑都会触发热重载——在 3083 直接挂载可能短暂改变工具清单/会话状态，故试点必须限定隔离 profile（规划 §3.1 判定"部分可行"语义）。
3. **env 注入面**：stdio 配置的 `env` 字段覆盖父环境（scrubbed 之后合并），若按 README 反例注入 token 会扩大敏感面；Git MCP 试点（P1-S2/S3）必须零 env 注入、repo_path 白名单仅 Alice 仓库。
4. **loader 解析细节**：boot 未传 `bareModuleBaseUrl`，裸 specifier 解析走 config 目录（profile 目录）→ 依赖 flat fallback 符号链接存在（已确认存在）；若未来 DSH 安装方式变化（如 pnpm 全局重装）需复测。
5. **early development**：官方 Git MCP server（P1-S2 对象，`modelcontextprotocol/servers` src/git）README 自述 "currently in early development"，工具列表可变化（补充调查已注明）。

## 5. 结论

1. **接线点存在**：dsh-mcp-client 插件设计完整（配置 schema、命名约定、stdio/http transport、HMR、reconnect、fail-on-startup 策略），且**从 profile patch 即可引用，无需修改 DSH 安装或 profile node_modules**——这是 V-A 文档核对的核心积极证据。
2. **当前部署零 MCP 先例**：web/alice-bus-lab/alice-bus 均无 MCP 条目；隔离 lab profile 实验模式有先例（alice-bus-lab）。
3. **判定：需维护员 dry-run 验证（V-B），试点限定隔离 profile**；V-A 层面倾向"部分可行（仅隔离 profile）"，不判定 3083 内直接可行，也不判定不可行。
4. 若 V-B 通过（工具注册/回滚/无残留、隔离 profile 内行为符合 README），则 P1-Git-MCP 试点可进入 P1-S2（只读工具白名单 + repo_path 白名单）；若 V-B 显示必须改 DSH checkout 或 3083 配置 → 按规划 §3.1 停止，判定不可行，P1 终止。

**本报告不构成实施授权**；未修改任何文件、未安装/连接任何工具或 endpoint。V-B dry-run 及其后任何试点均需监督员审核 + 用户批准的独立计划。