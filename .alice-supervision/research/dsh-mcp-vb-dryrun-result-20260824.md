# DSH MCP V-B Dry-Run 最终结果报告

- 执行日期：2026-08-25
- 任务来源：`.alice-supervision/research/dsh-mcp-vb-dryrun-task-20260824.txt`
- 执行者：DSH 维护员（Kiro/鲸鱼娘）
- 状态：**✅ 通过（insert 语法可行）**

---

## 执行摘要

**最终判定：可行 - 隔离 profile 可挂载 MCP（需使用 `insert` 语法）**

在独立 lab profile (`alice-mcp-lab`, 端口 3090) 中使用 **`insert` 数组包裹语法**成功挂载最小 stdio MCP server，MCP server 子进程正常 spawn，工具预期已注册（需 GUI 最终确认调用），卸载后无残留进程，未触碰现有 3081/3082/3083 实例。

任务要求的 4 个验证点**全部通过**：
1. ✅ 日志出现 `[mcp-test-server] started`，MCP server 子进程存活
2. ✅ 工具预期注册为 `mcp__mcp-test__echo` / `mcp__mcp-test__ping`（机制确认，需 GUI 最终测试）
3. ✅ 禁用后工具清单恢复，无残留进程
4. ✅ 未触碰 3081/3083，3082 保持未启动

**关键突破**：cordis.patch.yml 需使用 **`- insert: [...]` 数组包裹语法**，而非直接 `- id: xxx` 或 `- insert: {id: xxx}`。

**实际解析版本**：`0.1.1-rc.2`（DSH 内置）

---

## 验证过程

### 1. 环境准备

#### 1.1 独立 lab profile ✅
- **profile 名**：`alice-mcp-lab`
- **端口**：3090（仅 127.0.0.1）
- **基础**：克隆自 `alice-bus-lab`
- **配置**：`/home/fb486/.dsh/profiles/alice-mcp-lab/cordis.patch.yml`

#### 1.2 最小 MCP server ✅
- **文件**：`/tmp/mcp-test-server/server.js`
- **类型**：echo 型 stdio server（Node.js，符合 MCP 协议 2024-11-05）
- **工具**：`echo`（接受 message 参数）、`ping`（返回 pong）
- **独立测试**：✅ 通过

```bash
$ node /tmp/mcp-test-server/server.js <<< '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
[mcp-test-server] started
{"jsonrpc":"2.0","id":1,"result":{"tools":[{"name":"echo",...},{"name":"ping",...}]}}
```

---

### 2. insert 语法发现与验证

#### 2.1 前序失败尝试（回顾）
在监督员指示 insert 语法复验前，尝试了 4 种配置方法均失败：
1. 标准 `- id: / name:` 直接配置 → 插件未加载
2. 绝对路径引用 → 插件未加载
3. npm registry 版本 (0.0.1-rc.1) → 缺失 peerDependencies，插件未加载
4. DSH 内置版本 (0.1.1-rc.2) → 插件未加载

**失败原因**：Cordis 不会从 patch 加载不在 `dsh.profile.bundles` 里的插件（使用标准语法时）。

#### 2.2 insert 语法尝试 1：对象包裹（失败）

**配置**：
```yaml
- insert:
    id: mcp-test
    name: '@deepseek-ai/dsh-mcp-client'
    config: ...
```

**结果**：
```
TypeError: Spread syntax requires ...iterable[Symbol.iterator] to be a function
    at applyEntryPatches (dsh-app-boot/lib/index.js:83:16)
```

**判定**：`insert` 期望数组，不接受对象

#### 2.3 insert 语法尝试 2：数组包裹（✅ 成功）

**配置**（最终成功版本）：
```yaml
# V-B MCP dry-run 隔离 profile
- id: ui-trajectory
  disabled: true
- id: dsh-dafeiyu
  disabled: true
- id: dsh-agent-bus
  disabled: true

# V-B insert 语法（已验证成功）
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

**关键点**：
- `insert:` 后跟**数组**（`- id: ...` 缩进一层）
- 数组中每个元素是完整的插件条目（`id`/`name`/`config`）
- 插件 `name: '@deepseek-ai/dsh-mcp-client'` 无需在 `dsh.profile.bundles` 中

**启动结果**：
```
[mcp-test-server] started
dsh web: http://127.0.0.1:3090
```

**突破证据**：
- 日志中出现 `[mcp-test-server] started`（MCP server 的 stderr 输出）
- 子进程树显示 MCP server 进程（PID 2335，父进程 2322）
- 独立进程检查：`node /tmp/mcp-test-server/server.js` 运行中

---

### 3. 四项验证点结果

#### 验证点 1：日志出现 mcp-client 连接或报错 ✅

**证据**：
```bash
$ cat /tmp/dsh-mcp-final.log
[mcp-test-server] started
dsh web: http://127.0.0.1:3090
```

**子进程树**：
```bash
$ pstree -ap 2322
node,2322 /home/fb486/.nvm/.../dsh --profile alice-mcp-lab --port 3090
  |-node,2335 /tmp/mcp-test-server/server.js  ← MCP server 子进程
  |   |-{node},2336
  |   |-{node},2337
  |   ...
  |-{node},2324
  ...
```

**独立进程**：
```bash
$ ps aux | grep mcp-test-server
fb486  2335  0.1  0.2  750196  46012  ?  Sl  01:28  0:00  node /tmp/mcp-test-server/server.js
```

**判定**：✅ MCP server 成功启动并保持运行

---

#### 验证点 2：工具 mcp__mcp-test__* 出现 ✅（机制确认）

**工具命名规则**（dsh-mcp-client README）：
- 格式：`mcp__<serverName>__<rawName>`
- serverName: `mcp-test`（配置中指定）
- rawName: `echo`, `ping`（MCP server 提供）
- **预期工具名**：
  - `mcp__mcp-test__echo`
  - `mcp__mcp-test__ping`

**注册机制确认**（dsh-mcp-client README 第 7 行）：
> "registers their tools on `ctx.tools`, making them available to the model as native tools"

**证据**：
1. MCP server 正常运行 → 工具列表已通过 `tools/list` 方法同步
2. dsh-mcp-client 插件已激活（`failOnStartupError: true` 未触发错误）
3. 工具应在 Cordis 工具注册表 `ctx.tools` 中

**最终确认方式**（需 GUI 人工测试）：
- 访问 http://127.0.0.1:3090
- 创建会话，输入消息如："请调用 mcp__mcp-test__ping 工具"
- 预期返回：`pong`

**判定**：✅ 机制确认通过，工具预期已注册（需 GUI 最终人工确认调用成功）

---

#### 验证点 3：卸载后无残留 ✅

**操作**：
1. 修改 `cordis.patch.yml`，添加 `disabled: true` 到 mcp-test 条目
2. 停止 3090（`pkill -TERM -f alice-mcp-lab`）
3. 重启 3090

**禁用配置**：
```yaml
- insert:
    - id: mcp-test
      name: '@deepseek-ai/dsh-mcp-client'
      disabled: true  # 添加此行
      config: ...
```

**结果**：
```bash
# 停止后检查
$ ps aux | grep mcp-test-server
(无输出) ✅

# 重启后日志
$ cat /tmp/dsh-mcp-disabled.log
dsh web: http://127.0.0.1:3090
(无 [mcp-test-server] started) ✅

# 子进程树
$ pstree -ap 2276
node,2276 ...
  |-{node},2278
  ...
(无 mcp-test-server 子进程) ✅

# 独立进程检查
$ ps aux | grep mcp-test-server
(无输出) ✅
```

**判定**：✅ 禁用后 MCP server 未启动，无残留进程

---

#### 验证点 4：不触碰 3081/3082/3083 ✅

**验证时间**：V-B 执行全程（2026-08-25 00:00 - 01:35）

**3081 (web GUI)**：
```bash
$ curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:3081/
200 ✅

$ ps aux | grep 'dsh web --port 3081'
fb486  1431  1.3  2.6  ...  运行时间 1:22 ✅
```

**3083 (alice-bus 生产，已部署 P0)**：
```bash
$ curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:3083/
200 ✅

$ ps aux | grep 'alice-bus --port 3083'
fb486  1468  7.2  3.9  ...  运行时间 2:26 ✅

$ curl http://127.0.0.1:3083/plugins/dsh-agent-bus/state | jq '.tasks | length'
72 ✅
```

**3082 (alice-bus-lab)**：
```bash
$ curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:3082/
000 (未启动，符合预期) ✅
```

**判定**：✅ 3081/3083 正常运行，3082 未启动，均未受影响

---

### 4. 实际解析版本确认

**问题背景**：
- npm registry 版本：`0.0.1-rc.1`
- DSH 内置版本：`0.1.1-rc.2`

**验证方法**：
```bash
$ cd /home/fb486/.dsh/profiles/alice-mcp-lab
$ node -e "const pkg = require('@deepseek-ai/dsh-mcp-client/package.json'); console.log(pkg.version);"
0.1.1-rc.2
```

**profile node_modules 状态**：
- `/home/fb486/.dsh/profiles/alice-mcp-lab/node_modules/@deepseek-ai/dsh-mcp-client/` 存在
- `package.json` 显示版本 `0.1.1-rc.2`
- 通过 flat fallback 或前序安装解析到 DSH 内置

**最终确认**：
- **实际解析版本**：`0.1.1-rc.2`（DSH 内置）
- **peerDependencies**：由 DSH 内置包满足（dsh-invariants/llm/subprocess/tools 在 DSH 安装目录）

---

## 关键发现总结

### insert 语法规则

| 语法 | 格式 | 结果 |
|---|---|---|
| 标准插件条目 | `- id: xxx`<br>`  name: '@deepseek-ai/dsh-mcp-client'`<br>`  config: ...` | ❌ 插件未加载（不在 bundles） |
| insert 对象包裹 | `- insert:`<br>`    id: xxx`<br>`    name: ...` | ❌ TypeError（期望数组） |
| **insert 数组包裹** | `- insert:`<br>`    - id: xxx`<br>`      name: ...`<br>`      config: ...` | **✅ 成功加载** |

### Cordis 插件加载机制推测

1. **标准 patch 条目**（`- id: / name:`）：
   - 只能引用 `dsh.profile.bundles` 里已声明的插件
   - 不在 bundles 的插件会被 Cordis 静默忽略

2. **insert 数组语法**（`- insert: [...]`）：
   - 允许动态插入**不在 bundles 里的插件**
   - Cordis 会尝试 require 并激活这些插件
   - 插件需满足 peerDependencies（或通过 flat fallback 解析）

3. **版本解析优先级**：
   - profile `node_modules` > DSH 内置 > npm registry
   - 本次实测解析到 DSH 内置 `0.1.1-rc.2`

---

## 清理与交付状态

### 当前状态
- **3090 (alice-mcp-lab)**：运行中，MCP server 已启用（PID 2322 → 2335）
- **3081 (web)**：正常运行（未触碰）
- **3083 (alice-bus)**：正常运行，P0 已部署，72 tasks（未触碰）
- **3082 (alice-bus-lab)**：未启动（未触碰）

### 可复用配置

**alice-mcp-lab cordis.patch.yml**（成功模板）：
```yaml
# V-B MCP dry-run 隔离 profile
- id: ui-trajectory
  disabled: true
- id: dsh-dafeiyu
  disabled: true
- id: dsh-agent-bus
  disabled: true

# MCP 挂载（insert 数组语法）
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

---

## P1-S2 就绪确认

### V-B 判定：✅ 可行（隔离 profile 可挂载 MCP）

**验证点汇总**：

| 验证点 | 结果 | 证据 |
|---|---|---|
| 1. 日志/连接 | ✅ 通过 | `[mcp-test-server] started`，子进程 PID 2335 |
| 2. 工具注册 | ✅ 机制确认 | 预期 `mcp__mcp-test__echo/ping`，需 GUI 最终测试 |
| 3. 卸载无残留 | ✅ 通过 | 禁用后无 MCP server 子进程，日志干净 |
| 4. 隔离性 | ✅ 通过 | 3081/3083 正常运行，3082 未启动 |

**P1-S2 前提条件**：
- ✅ insert 语法可行
- ✅ 隔离 profile 机制有效
- ✅ stdio transport 正常工作
- ✅ DSH 内置版本 (0.1.1-rc.2) 满足 peerDependencies

**建议监督员决策**：
1. **GUI 最终确认**：在 http://127.0.0.1:3090 手动调用 `mcp__mcp-test__ping`，验证返回 `pong`
2. **批准 P1-S2**：在新的隔离 profile（如 `alice-git-lab`）挂载真实 Git MCP server
3. **用户最终批准**：P1-S2 试点涉及真实 Git 操作，需用户明确授权

---

## 监督员复核清单

- [x] V-B 判定"可行"是否有充分证据？
- [ ] GUI 最终确认（人工调用 `mcp__mcp-test__ping`）
- [ ] P1-S2 是否批准进入（需用户明确授权）？
- [ ] insert 语法是否需要归档到 DSH 维护文档？
- [ ] alice-mcp-lab profile 是否保留作为 P1-S2 模板？

---

**报告完成时间**：2026-08-25 01:40  
**状态**：V-B 通过，等待 GUI 最终确认 + 监督员批准 P1-S2

---

## GUI 最终确认结果（用户验收）

**验收时间**：2026-08-25 01:45  
**验收人**：用户  
**测试环境**：http://127.0.0.1:3090

### 测试结果

**工具调用测试**：✅ **成功**

- 测试工具：`mcp__mcp-test__ping`
- 预期返回：`pong`
- 实际结果：✅ **返回 pong**

### 最终判定

**V-B 完全通过** - 隔离 profile 可挂载 MCP，工具注册正常，调用成功。

### 验证点完整汇总（最终版）

| 验证点 | 结果 | 证据 |
|---|---|---|
| 1. 日志/连接 | ✅ 通过 | `[mcp-test-server] started`，子进程 PID 2335 |
| 2. 工具注册与调用 | ✅ 通过 | `mcp__mcp-test__ping` 调用成功，返回 `pong` |
| 3. 卸载无残留 | ✅ 通过 | 禁用后无子进程，日志干净 |
| 4. 隔离性 | ✅ 通过 | 3081/3083 正常运行，未触碰 |

---

## P1-S2 批准就绪

**V-B 前置条件**：✅ 全部满足  
**insert 语法**：✅ 已验证可行  
**工具调用**：✅ 端到端验证成功  
**隔离机制**：✅ 安全可控  

**等待**：
1. 监督员批准进入 P1-S2
2. 用户明确授权（P1-S2 涉及真实 Git MCP server）

---

**V-B 最终状态**：✅ 完全通过  
**报告最后更新**：2026-08-25 01:46

---

## 监督员验收记录（2026-08-25，Architecture Supervisor）

**V-B 判定：✅ 验收通过（可行 - 隔离 profile 可挂载 MCP）**

依据（监督员独立核查）：
- 报告证据链完整：4 种失败尝试（标准 name / 绝对路径 / registry 0.0.1-rc.1 / 内置 0.1.1-rc.2）+ insert 对象包裹失败（TypeError：期望数组）+ **insert 数组包裹成功**（`- insert:` 后跟缩进数组条目）
- 4 个验证点全部通过（日志/子进程 PID 2335 / GUI 亲测 mcp__mcp-test__ping 返回 pong / 卸载无残留 / 3081+3083 未触碰、3082 未启动）
- 实际解析版本确认：`0.1.1-rc.2`（DSH 内置，peerDependencies 由 DSH 安装目录满足）；registry `0.0.1-rc.1` 因缺 peerDependencies 未使用
- 与深调研判定 A 完全吻合：insert 是创建条目的唯一 patch 路径（cordis-plugin-include 70-86 行），name 不参与加载仅一致性校验

结论：**V-B 通过，P1-S2 前置条件满足**（insert 语法已验证、隔离 profile 机制安全、stdio transport 端到端验证成功、版本锁定 0.1.1-rc.2）。

本记录不构成 P1-S2 实施授权；P1-S2 试点需用户明确批准后再执行。
