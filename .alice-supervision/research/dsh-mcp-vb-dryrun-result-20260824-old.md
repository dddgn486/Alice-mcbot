# DSH MCP V-B Dry-Run 结果报告

- 执行日期：2026-08-25
- 任务来源：`.alice-supervision/research/dsh-mcp-vb-dryrun-task-20260824.txt`
- 执行者：DSH 维护员（Kiro/鲸鱼娘）
- 状态：**阻塞 - 需技术澄清**

---

## 执行摘要

**判定：不可行（当前配置方法无效）**

在独立 lab profile (`alice-mcp-lab`, 端口 3090) 中尝试挂载最小 stdio MCP server，但 **dsh-mcp-client 插件未能被 Cordis 加载**，无 MCP server 子进程 spawn，无工具注册。尝试了 3 种配置方法均无效。

任务要求的 4 个验证点**均未通过**：
1. ❌ 工具未以 `mcp__<serverName>__<rawName>` 出现
2. ❌ 无法测试卸载（因未成功挂载）
3. ✅ 未触碰现有 3081/3082/3083
4. ❌ 无法给出"可行"判定

**阻塞原因**：不清楚如何让 Cordis 从 `cordis.patch.yml` 动态加载不在 `dsh.profile.bundles` 里的插件。

---

## 执行过程

### 1. 环境准备

#### 1.1 独立 lab profile 创建 ✅
- **profile 名**：`alice-mcp-lab`
- **端口**：3090（仅 127.0.0.1）
- **基础**：克隆自 `alice-bus-lab`
- **配置**：`/home/fb486/.dsh/profiles/alice-mcp-lab/cordis.patch.yml`
- **禁用插件**：ui-trajectory, dsh-dafeiyu, dsh-agent-bus（避免干扰）

#### 1.2 最小 MCP server 准备 ✅
- **文件**：`/tmp/mcp-test-server/server.js`
- **类型**：echo 型 stdio server（Node.js，符合 MCP 协议 2024-11-05）
- **工具**：`echo`（接受 message 参数）、`ping`（返回 pong）
- **独立测试**：通过（`echo '{"method":"tools/list"}' | node server.js` 返回 2 个工具）

```bash
$ node /tmp/mcp-test-server/server.js <<< '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
[mcp-test-server] started
{"jsonrpc":"2.0","id":1,"result":{"tools":[{"name":"echo",...},{"name":"ping",...}]}}
```

#### 1.3 现有实例状态（未触碰）✅
- 3081: dsh web（PID 1431）
- 3083: alice-bus 生产（PID 1468，已部署 P0）
- 3082: 未启动（按需）
- 验证方式：重启前后端口/进程一致

---

### 2. 配置尝试与结果

#### 尝试 1：标准 name 引用（参考 README）

**配置**：
```yaml
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

**结果**：
- 3090 启动成功（HTTP 200）
- 日志：仅 1 行 `dsh web: http://127.0.0.1:3090`
- 子进程：无 `mcp-test-server` 进程
- 工具清单：API 无响应或空
- **判定**：插件未加载

#### 尝试 2：添加到 bundles（误解 bundle 机制）

**操作**：将 `@deepseek-ai/dsh-mcp-client` 加入 `package.json` 的 `dsh.profile.bundles`

**结果**：
```
Error: dsh: profile bundle "@deepseek-ai/dsh-mcp-client" declares no dsh.bundle in its package.json
```

**判定**：dsh-mcp-client 不是 bundle（插件包集合），是单独 Cordis 插件，不能放 bundles

#### 尝试 3：绝对路径引用

**配置**：
```yaml
- id: mcp-test
  name: /home/fb486/.nvm/versions/node/v22.23.2/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-mcp-client
  config: ...
```

**结果**：
- 3090 启动成功（HTTP 200）
- 日志：仅 1 行，无错误、无 MCP 信息
- 子进程：无 `mcp-test-server`
- **判定**：插件未加载（静默忽略？）

---

### 3. 诊断发现

#### 3.1 dsh-mcp-client 可解析 ✅
```bash
$ cd /home/fb486/.dsh/profiles/alice-mcp-lab
$ node -e "console.log(require.resolve('@deepseek-ai/dsh-mcp-client'))"
/home/fb486/.nvm/.../dsh/node_modules/@deepseek-ai/dsh-mcp-client/lib/index.js
```

#### 3.2 profile bundles 机制
- `alice-bus` 的 `package.json` bundles：
  - `@deepseek-ai/dsh-base`
  - `@deepseek-ai/dsh-web-app`
  - `dsh-agent-bus` ← 在 bundles 里，cordis.patch 可用 `id: agent-bus` 直接引用
  - `dsh-dafeiyu`
- `dsh-mcp-client` **不在任何 profile 的 bundles 里**

#### 3.3 插件加载迹象
- 所有尝试均无：
  - MCP 相关日志（stdio 连接、工具同步）
  - 子进程 spawn（`ps`/`pstree` 无 mcp-test-server）
  - `failOnStartupError: true` 触发的错误

#### 3.4 对比成功案例
- `dsh-agent-bus` 在 bundles 里 → cordis.patch 用 `id: agent-bus`（无 `name`）成功加载
- `dsh-mcp-client` 不在 bundles → cordis.patch 用 `name: '@deepseek-ai/dsh-mcp-client'` 无效

---

## 验证点结果

| 验证点 | 要求 | 结果 | 证据 |
|---|---|---|---|
| 1. 工具注册 | `mcp__mcp-test__echo` / `mcp__mcp-test__ping` 出现在工具清单 | ❌ 失败 | 无子进程，API 无响应，日志无 MCP |
| 2. 调用测试 | 调用 1 个只读工具并记录返回 | ❌ 未执行 | 因工具未注册 |
| 3. 卸载回滚 | `disabled: true` 后无残留 | ❌ 未测试 | 因未成功挂载 |
| 4. 隔离性 | 不触碰 3081/3082/3083 | ✅ 通过 | 3081/3083 运行正常，3082 未启动 |

---

## 边界确认

### 已遵守的禁止事项 ✅
- ✅ 未修改 DSH checkout（`/home/fb486/.nvm/.../dsh/`）
- ✅ 未修改 `alice-bus`(3083)、`alice-bus-lab`(3082)、`web`(3081) 配置
- ✅ 未挂载真实 Git MCP server（仅用 echo 型测试 server）
- ✅ 零 env 注入（stdio 配置无 API key/token）
- ✅ 未下载额外工具（仅自建最小 server.js）

### 触发的停止条件
- ❌ 配置方法在当前 DSH 版本下无效（不属于"修改 checkout/现有 profile"的硬停止，但属于技术不可行）

---

## 阻塞分析

### 根本问题
**Cordis 如何从 `cordis.patch.yml` 加载不在 `dsh.profile.bundles` 里的插件？**

### 可能的原因
1. **Cordis 机制限制**：patch 只能引用 bundles 里已加载的插件，无法动态 require 外部包
2. **配置格式错误**：`name` 字段的正确用法可能不是包名或路径
3. **缺失的注册步骤**：dsh-mcp-client 可能需要通过 CLI 参数、环境变量或其他方式启用
4. **文档/实现不匹配**：dsh-mcp-client README 的配置示例可能假设某个前置条件（如已在 bundles 或全局安装）

### P1-S1 "接线点存在"的证据缺口
P1-S1 报告称：
> "dsh-mcp-client 插件已随 DSH 安装，从 profile patch 即可引用（flat fallback 符号链接），require.resolve 已实测成功"

但实测发现：
- `require.resolve` 确实成功 ✅
- `cordis.patch.yml` 引用后**插件未被 Cordis 加载** ❌
- 没有"从 patch 成功挂载 MCP server"的实际证据

---

## 清理状态

### 临时文件
- `/tmp/mcp-test-server/server.js`（可保留，无副作用）
- `/tmp/dsh-mcp-lab-3090.log`（日志）

### profile
- `/home/fb486/.dsh/profiles/alice-mcp-lab/`（可保留或删除）
  - `cordis.patch.yml`（3 次配置尝试的最终版本）
  - `package.json`（已恢复原状，无 dsh-mcp-client）

### 进程/端口
- 3090 已停止 ✅
- 3081/3082/3083 未受影响 ✅

---

## 建议下一步

### 选项 A：技术澄清（推荐）
联系 DSH 维护者或查阅完整 Cordis 文档，确认：
1. 如何从 `cordis.patch.yml` 加载不在 bundles 里的插件？
2. dsh-mcp-client 是否有特殊的启用方式（CLI 参数、环境变量、全局配置）？
3. README 示例是否假设某个前置条件？

### 选项 B：修改 bundles（可能违反边界）
将 dsh-mcp-client 加入某个 profile 的 bundles，但：
- 需要修改 `package.json` 的 `dsh.profile.bundles`
- 可能触发 DSH 重新构建 profile 依赖树
- 是否属于"修改现有 profile"边界？需监督员判定

### 选项 C：降级判定（务实）
判定"V-B 不可行（配置方法不明）"，建议：
- P1-S2（Git MCP server 试点）暂缓，直到配置方法澄清
- 或跳过 MCP 方案，评估其他技能扩展路径

---

## 附录：配置文件样本

### alice-mcp-lab cordis.patch.yml（最终版本）

```yaml
# V-B MCP dry-run 隔离 profile
- id: ui-trajectory
  disabled: true
- id: dsh-dafeiyu
  disabled: true
- id: dsh-agent-bus
  disabled: true

# V-B: 尝试绝对路径引用 dsh-mcp-client（无效）
- id: mcp-test
  name: /home/fb486/.nvm/versions/node/v22.23.2/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-mcp-client
  config:
    serverName: mcp-test
    transport: stdio
    command: node
    args:
      - /tmp/mcp-test-server/server.js
    failOnStartupError: true
```

### MCP test server (可复用)

```javascript
#!/usr/bin/env node
// 最小 MCP stdio server for testing

const readline = require('readline');
const rl = readline.createInterface({ input: process.stdin, output: process.stdout, terminal: false });

const tools = [
  { name: 'echo', description: 'Echo the input message', inputSchema: { type: 'object', properties: { message: { type: 'string' } }, required: ['message'] } },
  { name: 'ping', description: 'Return pong', inputSchema: { type: 'object', properties: {} } }
];

rl.on('line', (line) => {
  try {
    const req = JSON.parse(line);
    let response;
    if (req.method === 'initialize') {
      response = { jsonrpc: '2.0', id: req.id, result: { protocolVersion: '2024-11-05', serverInfo: { name: 'mcp-test-echo', version: '1.0.0' }, capabilities: { tools: {} } } };
    } else if (req.method === 'tools/list') {
      response = { jsonrpc: '2.0', id: req.id, result: { tools } };
    } else if (req.method === 'tools/call') {
      const { name, arguments: args } = req.params;
      let content = name === 'echo' ? [{ type: 'text', text: `Echo: ${args?.message || '(empty)'}` }] : name === 'ping' ? [{ type: 'text', text: 'pong' }] : null;
      if (!content) throw new Error(`Unknown tool: ${name}`);
      response = { jsonrpc: '2.0', id: req.id, result: { content } };
    } else {
      response = { jsonrpc: '2.0', id: req.id, error: { code: -32601, message: 'Method not found' } };
    }
    console.log(JSON.stringify(response));
  } catch (err) {
    console.log(JSON.stringify({ jsonrpc: '2.0', id: null, error: { code: -32700, message: 'Parse error', data: err.message } }));
  }
});

process.stderr.write('[mcp-test-server] started\n');
```

---

## 监督员复核清单

- [ ] V-B 判定"不可行"是否合理？
- [ ] 是否需要升级为"技术支持请求"（联系 DSH 维护者）？
- [ ] 选项 B（修改 bundles）是否在允许边界内？
- [ ] P1-S2 是否暂缓，还是尝试其他配置路径？
- [ ] 本报告是否满足任务要求的证据完整性？

---

**报告结束时间**：2026-08-25 00:30  
**下一步待监督员指示**

---

## V-B 重试记录（监督员指示）

**执行时间**：2026-08-25 00:45-00:50  
**目标**：用 README 标准写法 + 记录实际解析的插件版本

### 重试方法

#### 重试 1：标准包名引用（README 写法）
**配置**：
```yaml
- id: mcp-test
  name: '@deepseek-ai/dsh-mcp-client'
  config:
    serverName: mcp-test
    transport: stdio
    command: node
    args: [/tmp/mcp-test-server/server.js]
    failOnStartupError: true
```

**结果**：
- 3090 启动成功（HTTP 200）
- 日志：仅 1 行 `dsh web: http://127.0.0.1:3090`
- 子进程：无 mcp-test-server
- **判定**：插件未加载

#### 重试 2：安装 npm registry 版本
**操作**：
1. `npm install @deepseek-ai/dsh-mcp-client` → 安装 `0.0.1-rc.1`
2. 添加到 `package.json` dependencies
3. 重启 3090

**关键发现**：
- **npm registry 版本**：`0.0.1-rc.1`
- **DSH 内置版本**：`0.1.1-rc.2`
- registry 版本有 **peerDependencies**：
  ```json
  {
    "@deepseek-ai/dsh-invariants": "^0.0.1-rc.1",
    "@deepseek-ai/dsh-llm": "^0.0.1-rc.1",
    "@deepseek-ai/dsh-subprocess": "^0.0.1-rc.1",
    "@deepseek-ai/dsh-tools": "^0.0.1-rc.1",
    "@deepseek-ai/cordis": "^4.0.1-rc.1"
  }
  ```
- profile `node_modules` **缺失所有 peer 依赖** ❌
- DSH 内置有这些包，但 profile 无符号链接

**结果**：仍无 MCP server 子进程

#### 重试 3：移除 registry 版本，使用 DSH 内置
**操作**：
1. 删除 profile 的 `node_modules/@deepseek-ai/dsh-mcp-client`
2. 从 `package.json` dependencies 移除（让 Cordis 解析到 DSH 内置 0.1.1-rc.2）
3. 重启 3090

**理论**：DSH 内置版本 (0.1.1-rc.2) 应该与 DSH 内置的其他包版本匹配

**结果**：
- 3090 启动成功
- 日志：仅 1 行
- 子进程：无 mcp-test-server ❌
- **判定**：内置版本也无法加载

### 最终判定

**V-B 不可行**：所有配置方法（标准包名、绝对路径、registry 版本、DSH 内置版本、dependencies 声明）均无法让 dsh-mcp-client 在 `cordis.patch.yml` 中被 Cordis 加载。

### 版本记录（完整）

| 项 | 版本/状态 |
|---|---|
| dsh-mcp-client (npm registry) | 0.0.1-rc.1 |
| dsh-mcp-client (DSH 内置) | 0.1.1-rc.2 |
| @deepseek-ai/dsh | 0.1.1-rc.2 |
| 实际解析到的插件版本 | **无法确定（插件未被加载）** |
| registry 版本 peerDependencies | ❌ 缺失（profile 无 dsh-invariants/llm/subprocess/tools） |
| 内置版本 peerDependencies | 理论上满足（DSH 内置有全部依赖），但 Cordis 未加载 |

### 证据汇总

| 验证点 | 方法 1 | 方法 2 (registry) | 方法 3 (内置) |
|---|---|---|---|
| MCP server 子进程 | ❌ 无 | ❌ 无 | ❌ 无 |
| 日志中 MCP 信息 | ❌ 无 | ❌ 无 | ❌ 无 |
| failOnStartupError 触发 | ❌ 无 | ❌ 无 | ❌ 无 |
| 工具注册 `mcp__*` | ❌ 未验证 | ❌ 未验证 | ❌ 未验证 |

### 阻塞根因

**Cordis 插件加载机制问题**：
1. 不在 `dsh.profile.bundles` 里的插件无法通过 `cordis.patch.yml` 的 `name` 字段加载
2. 即使插件在 profile `node_modules` 或 DSH 内置中存在，Cordis 也不会尝试加载
3. `failOnStartupError: true` 未触发，说明 Cordis 根本**没有尝试激活这个插件条目**

**对比成功案例**：
- `dsh-agent-bus` 在 `bundles` 里 → cordis.patch 用 `id: agent-bus` 成功
- `dsh-mcp-client` 不在 `bundles` → cordis.patch 任何方式都无效

### 清理状态

- 3090 已停止 ✅
- 3081/3083 未受影响 ✅
- alice-mcp-lab profile 保留（含最终配置）
- /tmp/mcp-test-server/server.js 保留

---

## 最终建议（更新）

**V-B 判定：不可行（隔离 profile 无法挂载 MCP）**

### 选项 A：调查员技术调查（推荐）
向调查员申请技术调查任务：
- 调查问题：如何让 Cordis 从 `cordis.patch.yml` 加载不在 `dsh.profile.bundles` 里的插件？
- 具体案例：dsh-mcp-client 配置尝试 4 种方法均失败
- 证据：本报告完整记录

### 选项 B：修改 bundles（可能违反边界）
将 dsh-mcp-client 加入 profile 的 `dsh.profile.bundles`，但：
- 需要确认这是否属于"修改现有 profile"（alice-mcp-lab 是新建的，可能允许）
- 需要解决 peerDependencies 问题（registry 版本缺失依赖）

### 选项 C：P1-S2 暂缓
判定 MCP 方案不可行（至少在当前 DSH 版本下），评估其他技能扩展路径（如直接集成 Git 操作库、使用 Harness 原生 subprocess）。

---

**报告更新时间**：2026-08-25 00:52  
**状态**：V-B 重试完成，判定不可行，等待监督员决策
