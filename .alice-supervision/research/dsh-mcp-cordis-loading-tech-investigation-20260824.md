# 深度技术调查：Cordis 如何加载非 bundle 插件（dsh-mcp-client 正确启用方式）

- 任务：7d8a6adb-7f10-41c1-b634-5c1adea374e1；Alice 基线 ab510fd；日期 2026-08-24
- 依据：dsh-mcp-cordis-loading-tech-task-20260824.txt、dsh-mcp-vb-dryrun-result-20260824.md（三种配置尝试）、dsh-mcp-mount-feasibility-20260824.md（P1-S1）、skill-upgrade-p1-git-mcp-line-20260824.md（§3.1）
- 证据来源：DSH 安装内源码行号（片段由监督员只读提取）；未改任何文件、未运行改状态命令、未连接 MCP endpoint、未触碰 3081/3082/3083
- 落盘说明：调查员会话无文件写入工具，本报告由监督员代为落盘。真实性标注：task 7d8a6adb 提交的 result 中部（§1-§5）被系统裁剪，§6-§7 为调查员原文可见部分；§1-§5 由监督员依据其亲自读取的源码证据（cordis-plugin-include/lib/index.js 57-106、dsh-app-boot/lib/index.js 530-568、dsh-agent-bus/cordis.patch.yml 18-40）技术重建——技术结论与调查员提交及源码事实一致，但措辞由监督员补写，非调查员逐字原文。引用本报告时以技术结论为准，措辞差异不影响判定。

## 1. 执行摘要

**判定 A（有可复现启用步骤）**：正确路径是 cordis.patch.yml 中用顶层 `- insert:` 包裹条目（参照 dsh-agent-bus 先例），而非维护员尝试 1 的非 insert `- id:/name:` 覆盖写法。但"insert 条目中 name → loader import → 插件加载"未实测，需维护员隔离 profile dry-run 复验；若实测失败且必须改 DSH/bundles → 降级 B。

根因一句话：**patch 的 name 字段不参与插件加载**——它只是与目标条目已有 name 的一致性校验（cordis-plugin-include/lib/index.js 57-106 行）；非 insert 的 patch 必须有 id 且该 id 必须已存在于条目树（否则 warn skip、条目从未加入）。维护员尝试 1 的 `- id: mcp-test / name: '@deepseek-ai/dsh-mcp-client'` 因 id: mcp-test 不存在而被静默跳过——插件从未被 import，故无 MCP 日志、无子进程、failOnStartupError: true 也无从触发。

## 2. 调查证据（源码行号）

### 2.1 patch 条目解析语义（核心证据）

`cordis-plugin-include/lib/index.js` `applyEntryPatches`（57-106 行）关键逻辑：
- patch 解构 `{ id, insert, name, ...overrides }`；
- `if (insert)`：带 id 且目标存在且是 group → 推入该 group.config；不带 id → `data.push(...insert)` 顶层插入；然后 `buildMap(insert)` 把新条目加入 entryMap；
- 非 insert：`if (!id) warn("patch: id is required for non-insert patches")`；`target = entryMap.get(id)` 不存在 → `warn("patch: entry %C not found")` 并 continue（静默跳过）；`if (name && name !== target.name) warn("patch: name mismatch ... skipping")`；其余字段作 overrides 覆盖目标。

**语义结论**：
1. patch 的 name 只是一致性校验，从不触发加载/import/创建条目；
2. 非 insert patch 必须有 id 且 id 必须已存在于 entryMap（顶层条目 + 各 group.config 递归构建）；id 不存在 → warn skip，条目从未加入；
3. 新增条目唯一路径是 insert；
4. overrides 仅覆盖已存在目标。

### 2.2 为什么 `id: agent-bus` 成功而 `name: '@deepseek-ai/dsh-mcp-client'` 失败

- `id: agent-bus` 成功：agent-bus 条目由 bundle 层（dsh-agent-bus 的 cordis.patch.yml 顶层 `- insert:` 创建，18-40 行）先注入条目树，entryMap 已存在 id: agent-bus；profile 层（alice-bus/cordis.patch.yml）的 `- id: agent-bus / config: {...}` 是覆盖 patch——匹配已存在条目、应用 config 覆盖 → 成功。alice-bus 的 agent-bus 条目未写 name，故不触发 name mismatch。
- `name: '@deepseek-ai/dsh-mcp-client'` 失败（尝试 1）：`- id: mcp-test / name: ... / config: {...}` 是非 insert patch；id: mcp-test 不存在（无任何 bundle 层或 insert 创建过）→ warn("patch: entry not found") 跳过 → 条目从未进入 loader 条目树 → EntryTree.import() 从未调用 → 无 MCP 日志、无子进程、failOnStartupError 无从触发。这解释 V-B 全部失败现象（3090 正常、日志仅 1 行、工具清单空）。
- 尝试 3（绝对路径 name）同理失败：name 不参与加载，id 不存在照样 skip。

### 2.3 依赖解析链：insert 条目如何被 loader import

`@deepseek-ai/cordis-plugin-loader/lib/index.js` `EntryTree.import()`（259-269 行，P1-S1 已记录）：`ctx.loader.internal` 存在 → `internal.import(name, baseUrl)`；否则裸名/相对路径经 Node import()。
- 裸 specifier 解析锚点：dsh-app-boot README（32-33 行）"Bare plugin specifiers … resolve from the config directory by default"；boot() 未传 bareModuleBaseUrl（P1-S1 确认 profile-boot 仅 4 参）→ 从 config 目录 = profile 目录解析；
- 可达性（P1-S1 实测）：require.resolve('@deepseek-ai/dsh-mcp-client', {paths:[alice-bus]}) 成功——经 $DSH_HOME/profiles/node_modules flat fallback 符号链接（dsh-app-boot healProfilesModuleFallback 391-407 行，指向 DSH 安装 0.1.1-rc.2）；
- 插入后：insert 条目 → loader 激活 → import(name) → 加载插件模块 → ctx.tools.register('mcp__<serverName>__<rawName>')（dsh-mcp-client publicToolName 119-123 行）。
- 注意：internal.import 与 Node import 的实际分支取决于 ctx.loader.internal 是否被装配（boot 未传 bareModuleBaseUrl 时 internal 缺省）——待实测项 1。

### 2.4 bundle 机制与 dsh plugin 命令（判定 B 边界）

`dsh-app-boot/lib/index.js` `loadProfile`（530-568 行）：bundles 层每个包必须声明 `dsh.bundle.patch`，否则第 549 行抛 "declares no dsh.bundle"。dsh-mcp-client 无声明 → 不能进 bundles（尝试 2 报错即此）。

- dsh plugin add：pnpm-install 包到 profile 并 reconcile 进 dsh.profile.bundles（仅当包声明 dsh.bundle）；dsh-mcp-client 无声明 → 只进 dependencies，bundles 不变（alice-mcp-lab 现状：已 add registry 0.0.1-rc.1 + 91 包到 dependencies、bundles 未变）。
- 结论：DSH 官方登记路径只服务 bundle 形态插件；对非 bundle 插件没有专门入口——但 patch 的 insert 机制提供通用加载路径（不依赖 dsh.bundle 声明）。

dsh-agent-bus 成功先例：
- package.json：`"dsh": { "bundle": { "patch": "./cordis.patch.yml" }, "client": { "platform": "web", "inject": ["slots","sessions"] } }`
- cordis.patch.yml 头部注释："Install: dsh plugin --profile <name> add <this package> … reconciles it into the profile's dsh.profile.bundles layer list"；"insert appends rows and does not merge by id, so restating the rows a profile already carries … mounts each plugin twice"；实际条目（18-40 行）：`- insert: [ { id: agent-bus, name: 'dsh-agent-bus', config: { maxContentLength: 16000, maxPendingPerAgent: 20, maxSendsPerMinute: 10, taskTimeoutMs: 7200000, ... } } ]`
- 成功范式：顶层 `- insert:` 包裹条目（id + name: '包名' + config），name 即包名/插件名——条目由 insert 创建、name 被 loader import。

### 2.5 dsh-mcp-client README 假设 vs DSH patch 架构

- README 配置示例（第 9-30 行）：`- id: mcp-github / name: '@deepseek-ai/dsh-mcp-client' / config: {...}`——该写法假设条目已存在（Cordis 原生 config 或 bundle 层），非 insert 覆盖语义；
- DSH 的 cordis.yml 是空根（alice-bus/cordis.yml = []），patch 栈合成；README 示例的裸 `- id:/name:` 在 DSH 下不会被 create——DSH 中创建条目的唯一 patch 路径是 insert。**结论：README 示例在 DSH patch 架构下不适用/易误用，正确 DSH 写法是 `- insert:` 包裹 README 条目。**

## 3. patch 条目解析链（完整链路）

```
cordis.patch.yml → applyEntryPatches(data, patches) [cordis-plugin-include 57-106]
  ├─ insert: data.push(...insert) → 创建条目（顶层）【非 bundle 插件唯一路径】
  │     └─ 条目 {id,name,config,disabled,group,inject}
  │           └─ loader 激活 → EntryTree.import(name) [cordis-plugin-loader 259-269]
  │                 ├─ internal.import(name, baseUrl) [internal 存在时]
  │                 └─ import(name) [Node 裸名，从 profile 目录解析]
  │                       └─ profile/node_modules → $DSH_HOME/profiles/node_modules
  │                             (flat fallback → DSH 安装 dsh-mcp-client@0.1.1-rc.2)
  │                                   └─ apply(ctx, config) → ctx.tools.register('mcp__<serverName>__<rawName>')
  └─ 非 insert（- id:/name:/config:）→ 仅覆盖已存在条目
        ├─ id 不存在 → warn skip（尝试 1/3 失败点）
        └─ name 仅一致性校验，不参与解析

bundles 层（并行，dsh-app-boot loadProfile 530-568）：dsh.profile.bundles 每包必须有 dsh.bundle.patch 声明 → 其 cordis.patch.yml 作为 bundle patch 层合成；dsh-mcp-client 无声明 → 只能走 insert 路径，不能进 bundles。
```

## 4. 判定 A：可复现的最小启用步骤（供维护员 dry-run 复验）

在隔离 profile（建议新建 alice-mcp-lab 或复用现有 lab profile）的 cordis.patch.yml 中追加：

```yaml
- insert:
    - id: mcp-test
      name: '@deepseek-ai/dsh-mcp-client'
      config:
        serverName: mcp-test
        transport: stdio
        command: node
        args:
          - /tmp/mcp-test-server/server.js
        failOnStartupError: true
```

（P1-S2 若接 Git MCP：name 不变，command/args 改 uvx mcp-server-git 或 npx——本步骤只验证插件加载。）

验证点：
1. 启动隔离 profile 后日志出现 mcp-client 相关（连接/工具发现）或 failOnStartupError 报错（至少其一，证明插件被 import）；
2. 会话工具清单出现 mcp__mcp-test__<rawName>；
3. 卸载：删除 insert 块（或 disabled: true）→ 工具清单恢复、无残留子进程/localhost 监听；
4. 全程不触碰 alice-bus(3083)/alice-bus-lab(3082)/web(3081)。

版本注意：
- DSH 内置 dsh-mcp-client@0.1.1-rc.2（flat fallback 符号链接指向它，P1-S1 实测可解析）；
- alice-mcp-lab 已 dsh plugin add 装 registry 0.0.1-rc.1（+91 包到该 profile dependencies）——Node 解析优先级：profile/node_modules（registry 0.0.1-rc.1，若已装）→ flat fallback（0.1.1-rc.2）；两版本 Config schema 可能不同（0.1.1-rc.2 含 reconnect/failOnStartupError 等，README 按 0.1.1-rc.2 撰写）。dry-run 前应确认 profile/node_modules 实际版本并记录。

待实测项（维护员 dry-run 才能确认）：
1. insert 条目 name → EntryTree.import() 实际走 internal.import 还是 Node import，以及从 profile 目录解析的最终版本（0.1.1-rc.2 vs 0.0.1-rc.1）；
2. 插件激活后 mcp__mcp-test__* 是否出现在会话工具清单；
3. 卸载/禁用后无残留（子进程、localhost 端口）；
4. 对 3083 工作流零影响（隔离 profile 验证）。

## 5. 判定 B/C 边界（何时降级）

- insert 实测失败（internal.import 解析不到、或版本行为差异致配置不兼容）：先对齐实际加载版本 Config schema（--dump-config 或读该版本 lib/index.js Config）→ 仍失败则降级；
- 不修改 DSH 安装、不给 dsh-mcp-client 打 dsh.bundle 补丁（超出 P1 边界）；降级判定 B：需改 DSH 安装或打 bundle 补丁才能启用 → 超出边界，P1 Git MCP 试点终止（按规划 §3.1 停止条件）。
- C（当前 DSH 不支持非 bundle 插件挂载）仅在 insert 路径在源码层面被证明不可能——但 §2.3/§2.4 显示 insert 条目与 bundle 层条目在 loader 端是同一条目树，无 bundle-only 校验证据，故 C 不成立，除非 dry-run 出现反例。

## 6. 限制与风险

1. 本报告基于源码静态分析 + 监督员提取片段；插件实际加载行为（import 分支、版本解析、工具注册）未实测——待实测项必须由维护员隔离 profile dry-run 完成，不能以本报告替代。
2. 版本不一致风险：内置 0.1.1-rc.2 vs registry 0.0.1-rc.1。
3. env 注入面：stdio env 覆盖 scrubbed 父环境；P1-S2/S3 必须零 env 注入、repo_path 白名单仅 Alice 仓库。
4. HMR 扰动：编辑隔离 profile 的 cordis.patch.yml 触发该 profile 热重载；3083 patch 不被触碰则无影响。
5. early development：官方 Git MCP server 工具列表可变化；试点前核对固定版本。
6. 已确认 vs 待实测：
   - 已确认（源码）：patch 的 name 不参与加载（仅一致性校验）；非 insert 必须 id 存在否则 warn skip；insert 是创建条目的唯一 patch 路径；bundles 要求 dsh.bundle 声明；dsh-mcp-client 无声明不能进 bundles；dsh plugin add 只 reconcile 有声明的包；dsh-agent-bus 先例证明 insert+name 写法成功。
   - 待实测（dry-run）：insert 条目 name→import 的实际分支与解析版本；工具注册可见性；卸载无残留；版本兼容。

## 7. 结论

- 判定 A：非 bundle 插件（dsh-mcp-client）存在可复现的启用方式——cordis.patch.yml 顶层 `- insert:` 包裹 {id, name: '@deepseek-ai/dsh-mcp-client', config}（参照 dsh-agent-bus 先例），不需要 dsh.bundle 声明、不需要改 DSH 安装或 bundles。
- 维护员三种尝试失败的直接根因：尝试 1/3 是非 insert 覆盖写法（id 不存在 → 静默 skip）；尝试 2 违背 bundle 机制（无 dsh.bundle 声明不能进 bundles）。
- 下一步：维护员在隔离 profile 按 §4 步骤 dry-run 复验（待实测项 1-4）；通过后 P1 进入 S2（Git MCP 只读工具白名单试点）；失败且必须改 DSH/bundles → 降级 B、按规划停止 P1。

**本报告不构成实施授权**；未修改任何文件、未运行改状态命令、未连接任何 MCP endpoint、未触碰 3081/3082/3083。