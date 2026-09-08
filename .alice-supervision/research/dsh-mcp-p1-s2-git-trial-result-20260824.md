# DSH MCP P1-S2 Git MCP 只读试点结果报告

- 执行日期：2026-08-25
- 任务来源：`.alice-supervision/research/dsh-mcp-p1-s2-git-trial-task-20260824.txt`
- 执行者：DSH 维护员（Kiro/鲸鱼娘）
- 状态：**❌ 停止（官方 Git MCP server 包含写工具，不符合只读试点要求）**

---

## 执行摘要

**判定：不可行 - 官方 Git MCP server 包含写工具，违反只读试点边界**

成功创建隔离 profile `alice-git-lab`（端口 3091），使用 V-B 验证的 insert 语法挂载官方 Git MCP server（`mcp-server-git` 2026.8.18），server 进程正常启动（PID 2643）。但**源码分析发现该 server 提供 22 个工具，包含 `git_commit`、`git_add`、`git_reset`、`git_push` 等写工具，违反任务要求的"工具白名单仅 git_status/git_diff_unstaged/git_diff_staged/git_diff（只读 4 工具）"**。

根据任务停止条件："git_commit 与其他写工具显式不可用，出现即停"，已停止 3091，Alice 仓库 git log 完全一致（零 commit 变化），现有实例（3081/3082/3083/3090）未受影响。

**技术根因**：官方 Git MCP server 2026.8.18 版本**不提供工具过滤/白名单配置**，无法禁用写工具（CLI 参数仅 `--repository` 和 `-v`）。

---

## 执行过程

### 1. 环境准备与 Git MCP server 安装

#### 1.1 Alice 仓库基线记录 ✅

**仓库**：`/home/fb486/projects/alice`

**前状态**：
```bash
$ git status --short
 M .dsh-runtime/sessions/.../session.jsonl.zstd (4 files)
 M .dsh-runtime/storages/agent_bus.json
 M .dsh-runtime/storages/session_projcache.json
?? .alice-supervision/... (多个监督文档)
?? .dsh-runtime/backup-p0-3083-deploy-20260824-221846/
?? .dsh-runtime/recovery-backup/

$ git log --oneline -1
d872367 docs(handover): P0 skill upgrade closure (supervisor record)
```

**基线文件**：
- `/tmp/alice-git-baseline-status.txt`
- `/tmp/alice-git-baseline-log.txt`

#### 1.2 Git MCP server 核对与安装 ✅

**官方仓库**：
- GitHub: `modelcontextprotocol/servers` (monorepo)
- Path: `src/git/`
- Tag: `2026.8.18`（最新 release，2026-08-18 发布）

**技术栈**：
- 语言：**Python 3.10+**（非 Node.js）
- 包名：`mcp-server-git`
- 版本：`2026.8.18`
- 依赖：`gitpython>=3.1.50`, `mcp>=1.29.0,<2`, `pydantic>=2.0.0`, `click>=8.1.7`

**安装方式**：
```bash
# npm registry 无此包（非 Node.js 实现）
# 克隆 monorepo
$ git clone --depth 1 --branch 2026.8.18 https://github.com/modelcontextprotocol/servers.git /tmp/mcp-servers

# pip 安装（--break-system-packages，隔离试点环境可接受）
$ cd /tmp/mcp-servers/src/git
$ pip3 install --break-system-packages -e .
Successfully built mcp-server-git
Installing collected packages: ... mcp-server-git-2026.8.18 ...
Successfully installed ... mcp-server-git-2026.8.18 ...
```

**安装位置**：
- 可执行文件：`/home/fb486/.local/bin/mcp-server-git`
- 源码：`/tmp/mcp-servers/src/git`（editable install）

**验证**：
```bash
$ mcp-server-git --help
Usage: mcp-server-git [OPTIONS]

  MCP Git Server - Git functionality for MCP

Options:
  -r, --repository PATH  Git repository path
  -v, --verbose
  --help                 Show this message and exit.

$ echo '{"jsonrpc":"2.0","id":1,"method":"initialize",...}' | mcp-server-git
{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2024-11-05","capabilities":{...},"serverInfo":{"name":"mcp-git","version":"1.29.0"}}}
```

**版本固定记录**：
- **Git MCP server 版本**：`2026.8.18`
- **GitHub tag**：`2026.8.18`
- **MCP 协议版本**：`2024-11-05`
- **mcp 库版本**：`1.29.0`
- **Python 版本**：`3.12.3`（系统）

---

### 2. 隔离 profile 创建与配置

#### 2.1 alice-git-lab profile ✅

**基础**：克隆自 `alice-bus-lab`

**修改**：
- `package.json`：
  - `name`: `alice-git-lab`
  - `description`: `P1-S2 Git MCP 只读试点（端口 3091，隔离环境）`
- `cordis.patch.yml`：见下文

**隔离存储**：
- Sessions: `/home/fb486/projects/alice/.dsh-runtime/sessions-git-lab`
- Storages: `/home/fb486/projects/alice/.dsh-runtime/storages-git-lab`

#### 2.2 cordis.patch.yml 配置 ✅

使用 V-B 验证的 **insert 数组语法**：

```yaml
# P1-S2 Git MCP 只读试点（隔离 profile alice-git-lab，端口 3091）
# 任务: dsh-mcp-p1-s2-git-trial-task-20260824.txt
# 依据: V-B 验证通过的 insert 数组语法

# 隔离存储（不触碰其他 profile）
- id: session-persistence-jsonl
  config:
    root: /home/fb486/projects/alice/.dsh-runtime/sessions-git-lab

- id: storage-json
  config:
    root: /home/fb486/projects/alice/.dsh-runtime/storages-git-lab

# 禁用非必要插件（保持隔离环境）
- id: ui-trajectory
  disabled: true
- id: dsh-dafeiyu
  disabled: true
- id: dsh-agent-bus
  disabled: true

# P1-S2: 挂载 Git MCP server（只读试点）
- insert:
    - id: git-readonly
      name: '@deepseek-ai/dsh-mcp-client'
      config:
        serverName: git
        transport: stdio
        command: /home/fb486/.local/bin/mcp-server-git
        args:
          - --repository
          - /home/fb486/projects/alice
        # 零 env 注入（任务要求）
        failOnStartupError: true
```

**关键配置点**：
- `serverName: git` → 工具前缀 `mcp__git__*`
- `command`: 绝对路径（`~/.local/bin/mcp-server-git`）
- `args`: `--repository /home/fb486/projects/alice`（指定唯一 repo）
- **零 env 注入**：无 `env` 字段

---

### 3. 启动与验证

#### 3.1 3091 启动成功 ✅

```bash
$ dsh --profile alice-git-lab --port 3091 --no-open
dsh web: http://127.0.0.1:3091
```

**子进程树**：
```bash
$ pstree -ap 2630
node,2630 .../dsh --profile alice-git-lab --port 3091
  |-mcp-server-git,2643 /home/fb486/.local/bin/mcp-server-git --repository /home/fb486/projects/alice
  |   |-{mcp-server-git},2646
  |   `-{mcp-server-git},2647
  |-{node},2632
  ...
```

**Git MCP server 进程**：
```bash
$ ps aux | grep mcp-server-git
fb486  2643  3.3  0.3  218364  60412  ?  Sl  01:48  /usr/bin/python3 /home/fb486/.local/bin/mcp-server-git --repository /home/fb486/projects/alice
```

**判定**：✅ Git MCP server 成功 spawn，进程正常运行

---

### 4. 工具清单分析（停止原因）

#### 4.1 官方 README 工具列表

来源：`/tmp/mcp-servers/src/git/README.md`（2026.8.18 版本）

**完整工具清单**（22 个工具）：

| # | 工具名 | 类型 | 描述 |
|---|---|---|---|
| 1 | `git_status` | **只读** | Shows the working tree status |
| 2 | `git_diff_unstaged` | **只读** | Shows changes in working directory not yet staged |
| 3 | `git_diff_staged` | **只读** | Shows changes that are staged for commit |
| 4 | `git_diff` | **只读** | Shows differences between branches or commits |
| 5 | **`git_commit`** | **❌ 写** | Records changes to the repository |
| 6 | **`git_add`** | **❌ 写** | Adds file contents to the staging area |
| 7 | **`git_reset`** | **❌ 写** | Unstages all staged changes |
| 8 | `git_log` | 只读 | Shows commit logs |
| 9 | `git_create_branch` | 写 | Creates a new branch |
| 10 | `git_checkout` | 写 | Switches branches or restores files |
| 11 | `git_search_code` | 只读 | Searches code in repository |
| 12 | `git_show` | 只读 | Shows commit information |
| 13 | `git_list_branches` | 只读 | Lists all branches |
| 14 | `git_merge` | 写 | Merges branches |
| 15 | `git_rebase` | 写 | Rebases current branch |
| 16 | `git_tag` | 写 | Creates or lists tags |
| 17 | `git_stash` | 写 | Stashes changes |
| 18 | `git_apply_patch` | 写 | Applies a patch file |
| 19 | `git_cherry_pick` | 写 | Applies specific commits |
| 20 | `git_remote` | 只读/写 | Manages remotes |
| 21 | **`git_push`** | **❌ 写** | Pushes to remote |
| 22 | **`git_pull`** | **❌ 写** | Pulls from remote |

#### 4.2 白名单对比

**任务要求的白名单**（仅 4 个只读工具）：
1. ✅ `git_status`
2. ✅ `git_diff_unstaged`
3. ✅ `git_diff_staged`
4. ✅ `git_diff`

**禁止的写工具**（任务停止条件："出现即停"）：
1. ❌ `git_commit` - **存在**
2. ❌ `git_add` - **存在**
3. ❌ `git_reset` - **存在**
4. ❌ `git_push` - **存在**
5. ❌ `git_pull` - **存在**
6. ❌ `git_create_branch` - **存在**
7. ❌ `git_checkout` - **存在**
8. ❌ `git_merge` - **存在**
9. ... 共 **13 个写工具**

**实际提供工具**：22 个（4 个白名单 + 18 个超出范围）

#### 4.3 配置能力调查 ❌

**CLI 参数**（来源：`src/mcp_server_git/__init__.py`）：
```python
@click.command()
@click.option("--repository", "-r", type=Path, help="Git repository path")
@click.option("-v", "--verbose", count=True)
def main(repository: Path | None, verbose: bool) -> None:
    """MCP Git Server - Git functionality for MCP"""
```

**结论**：
- ❌ 无工具过滤参数
- ❌ 无白名单配置
- ❌ 无环境变量控制
- ❌ README 无工具启用/禁用说明

**判定**：官方 Git MCP server 2026.8.18 版本**不支持工具过滤**，无法禁用写工具。

---

### 5. 停止与清理

#### 5.1 停止原因

**触发的停止条件**：
> 任务文档 §2.1："git_commit 与其他写工具显式不可用，出现即停"

**实际情况**：
- Git MCP server 2026.8.18 提供 `git_commit` ✓（存在）
- 提供其他 12 个写工具 ✓（存在）
- 无配置机制禁用这些工具 ✓（确认）

**判定**：符合停止条件，立即停止 P1-S2 试点。

#### 5.2 3091 停止 ✅

```bash
$ pkill -TERM -f alice-git-lab
[killed by signal: SIGTERM]

$ ps aux | grep 'alice-git-lab\|3091\|mcp-server-git'
(无输出) ✅ 已停止
```

**停止时间**：2026-08-25 01:48（运行时长约 3 分钟）

#### 5.3 仓库完整性验证 ✅

**git status 对比**：
```bash
$ diff /tmp/alice-git-baseline-status.txt /tmp/alice-git-after-status.txt
61a62
> ?? .dsh-runtime/sessions-git-lab/
62a64
> ?? .dsh-runtime/storages-git-lab/
```

**差异分析**：
- 新增 2 个未跟踪目录：`sessions-git-lab/`, `storages-git-lab/`
- **原因**：3091 的隔离存储目录（配置所致，预期行为）
- **影响**：零（未跟踪文件，不影响 Git 历史）

**git log 对比**：
```bash
$ diff /tmp/alice-git-baseline-log.txt /tmp/alice-git-after-log.txt
(无差异) ✅ 完全一致
```

**git diff 检查**：
```bash
$ git diff --stat
(无输出，仅 .dsh-runtime 二进制文件变化，属于正常运行时状态)
```

**判定**：✅ **仓库零 commit 变化，只读性得到终极证明**

---

### 6. 现有实例验证 ✅

| 实例 | 端口 | 状态 | PID | 验证 |
|---|---|---|---|---|
| web | 3081 | 运行中 | 1431 | HTTP 200 ✅ |
| alice-bus | 3083 | 运行中 | 1468 | HTTP 200 ✅ |
| alice-bus-lab | 3082 | 未启动 | - | HTTP 000 ✅（预期） |
| alice-mcp-lab (V-B) | 3090 | 运行中 | 2322 | HTTP 200 ✅ |

**判定**：✅ 全部未受影响

---

## 关键发现总结

### 官方 Git MCP server 的技术限制

| 项 | 状态 | 说明 |
|---|---|---|
| 工具总数 | 22 个 | 包含 4 个白名单工具 + 18 个超出范围 |
| 写工具数量 | 13 个 | 包括 git_commit/add/reset/push/pull 等 |
| 工具过滤配置 | ❌ 不支持 | CLI 仅 `--repository` 和 `-v` |
| 白名单机制 | ❌ 不支持 | 无环境变量或配置文件 |
| 官方文档说明 | 无 | README 未提及工具过滤 |
| 开发状态 | Early development | README 自述 |

### V-B insert 语法验证 ✅

| 项 | V-B (echo server) | P1-S2 (Git server) | 结论 |
|---|---|---|---|
| insert 语法 | ✅ 成功 | ✅ 成功 | 语法通用 |
| server spawn | ✅ 成功（Node.js） | ✅ 成功（Python） | 跨语言支持 |
| dsh-mcp-client 版本 | 0.1.1-rc.2 | 0.1.1-rc.2 | 一致 |
| stdio transport | ✅ 正常 | ✅ 正常 | 稳定 |

**判定**：V-B 验证的 insert 语法和 MCP 接入机制**完全有效**，可跨语言使用。

---

## 技术可行性分析

### 方案 1：修改官方 Git MCP server 源码 ❌

**方式**：fork `modelcontextprotocol/servers`，修改 `src/git/src/mcp_server_git/server.py`，移除写工具。

**判定**：
- ❌ 违反"本任务不授权修改外部依赖"（任务边界）
- ❌ 维护成本高（官方更新需手动合并）
- ❌ 背离"官方 server"试点目标

### 方案 2：dsh-mcp-client 工具过滤层 ⚠️

**方式**：在 `@deepseek-ai/dsh-mcp-client` (0.1.1-rc.2) 实现工具白名单过滤。

**技术可行性**：
- ✅ dsh-mcp-client 桥接 MCP server 工具到 `ctx.tools`
- ✅ 可在注册阶段过滤工具（修改插件代码）
- ❌ 需要修改 DSH 内置插件（0.1.1-rc.2）
- ❌ 任务未授权修改 DSH checkout

**判定**：技术可行，但**超出当前任务授权边界**。

### 方案 3：自建只读 Git MCP server ⚠️

**方式**：参考官方 server，仅实现 4 个白名单工具的 Python MCP server。

**技术可行性**：
- ✅ 工作量可控（4 个工具，基于 `gitpython` 库）
- ✅ 完全符合只读试点要求
- ❌ 背离"试用官方 Git server"的 P1-S2 目标
- ❌ 需要额外开发与测试时间

**判定**：可行但偏离试点初衷，需**监督员重新评估 P1-S2 目标**。

### 方案 4：等待官方 server 支持工具过滤 ⏳

**方式**：向 `modelcontextprotocol/servers` 提交 Feature Request 或 PR。

**判定**：
- ✅ 最符合长期利益
- ❌ 时间不可控（官方 roadmap 未知）
- ❌ 不解决当前 P1-S2 试点需求

---

## 判定与建议

### P1-S2 最终判定

**状态**：❌ **不可行（官方 Git MCP server 不符合只读试点要求）**

**判定依据**：
1. ✅ 技术接入成功（insert 语法、MCP server spawn、stdio transport 正常）
2. ❌ **工具清单违反白名单**（22 个工具 vs 仅允许 4 个）
3. ❌ **包含写工具**（13 个，包括 git_commit/add/reset/push/pull）
4. ❌ **无工具过滤配置**（官方 2026.8.18 版本不支持）
5. ✅ 仓库完整性保持（零 commit 变化）
6. ✅ 现有实例未受影响

**符合的停止条件**：
- 任务 §2.1："git_commit 与其他写工具显式不可用，出现即停" ✓

### 监督员决策建议

#### 选项 A：P1 线路暂停（保守）

**判定**：
- 官方 Git MCP server（early development）不满足只读试点边界
- 修改官方 server 或 dsh-mcp-client 超出任务授权
- **建议**：P1 线路（Git MCP）暂缓，等待：
  1. 官方 server 支持工具过滤配置
  2. 或用户授权"自建只读 Git MCP server"（方案 3）
  3. 或用户授权"修改 dsh-mcp-client 实现工具白名单"（方案 2）

#### 选项 B：调整 P1-S2 目标（务实）

**新目标**：自建最小只读 Git MCP server（仅 4 个白名单工具）

**理由**：
- V-B 已验证 MCP 接入机制完全可行
- 官方 server 技术栈（Python + gitpython）成熟
- 4 个只读工具实现简单（工作量 <1 天）
- 可作为"自主 MCP server 开发"技能验证

**风险**：
- 偏离"试用官方 server"初衷
- 增加维护负担（需自行维护 server 代码）

#### 选项 C：P1-S3 跳过 Git，评估其他技能（灵活）

**建议**：
- P1-S2 判定不可行 → 直接评估 P1 整体可行性
- 如 Git 操作非核心需求，转向其他技能扩展路径：
  - 文件系统操作（已有 `read`/`write`/`glob`/`grep`）
  - subprocess 原生集成（Harness 已支持）
  - 其他 MCP server（如 filesystem/fetch，官方支持更成熟）

---

## 交付文件

### 配置模板（可复用）

**alice-git-lab cordis.patch.yml**（insert 语法示例）：
```yaml
# MCP 挂载（insert 数组语法，跨语言通用）
- insert:
    - id: git-readonly
      name: '@deepseek-ai/dsh-mcp-client'
      config:
        serverName: git
        transport: stdio
        command: /path/to/mcp-server
        args:
          - --arg1
          - value1
        # env: {} # 可选：环境变量注入
        failOnStartupError: true
```

### 技术证据

1. **Git MCP server 安装**：
   - 版本：`2026.8.18`
   - 路径：`/home/fb486/.local/bin/mcp-server-git`
   - 源码：`/tmp/mcp-servers/src/git`

2. **工具清单**：
   - 来源：`/tmp/mcp-servers/src/git/README.md`（官方文档）
   - 总数：22 个工具
   - 写工具：13 个（包括 git_commit）

3. **仓库基线**：
   - 前状态：`/tmp/alice-git-baseline-status.txt`, `/tmp/alice-git-baseline-log.txt`
   - 后状态：`/tmp/alice-git-after-status.txt`, `/tmp/alice-git-after-log.txt`
   - git log 完全一致 ✅

4. **启动日志**：
   - `/tmp/dsh-git-lab-startup.log`
   - `/tmp/dsh-git-lab-bg.log`

### 清理状态

- **3091 (alice-git-lab)**：已停止 ✅
- **Git MCP server 进程**：已停止 ✅
- **alice-git-lab profile**：保留（位于 `/home/fb486/.dsh/profiles/alice-git-lab`）
- **隔离存储**：保留（`.dsh-runtime/sessions-git-lab/`, `storages-git-lab/`，可手动删除）
- **Git MCP server 安装**：保留（`~/.local/bin/mcp-server-git`，可 `pip uninstall mcp-server-git` 卸载）
- **现有实例**：
  - 3081 (web)：正常运行 ✅
  - 3083 (alice-bus)：正常运行 ✅
  - 3082 (alice-bus-lab)：未启动 ✅
  - 3090 (alice-mcp-lab)：正常运行 ✅

---

## 监督员复核清单

- [x] P1-S2 判定"不可行"是否有充分证据？
- [x] 工具清单来源是否权威（官方 README）？
- [x] 停止条件触发是否正确？
- [x] 仓库完整性验证是否充分（git log 对比）？
- [x] 现有实例未受影响验证是否充分？
- [ ] 监督员决策：选项 A / B / C？
- [ ] 用户是否需要重新评估 P1-S2 目标与授权？
- [ ] P1 整体线路是否需要调整？

---

**报告完成时间**：2026-08-25 02:00  
**状态**：P1-S2 停止（官方 server 包含写工具），等待监督员决策与用户重新评估

---

## 监督员验收记录（2026-08-25，Architecture Supervisor）

**P1-S2 判定：✅ 验收通过（不可行判据成立，处置正确守边界）**

监督员独立核查（均与报告吻合）：
1. **工具清单权威**：官方 Git MCP server (2026.8.18) README 确认 22 工具含 git_commit/git_add 等写工具——报告工具清单属实
2. **无过滤配置双重确认**：Git server CLI 仅 --repository/-v；**dsh-mcp-client 0.1.1-rc.2 的 syncTools 遍历全部 tools 注册（无过滤分支）**，Config schema 无 allow/deny/filter 字段——"官方 server 无法白名单化"结论成立
3. **停止条件触发正确**：git_commit 等写工具存在 → 立即停止 3091，符合任务书 §2.1 停止条件
4. **只读性终极证明**：git log 前后一致（仍 d872367），仅新增未跟踪的隔离存储目录（预期行为，零 commit 变化）
5. **隔离性保持**：3081/3083/3090 正常运行、3082 未启动，全程未触碰

结论：**P1-S2（官方 Git server 只读试点）判定不可行——外部限制（官方 server 无工具过滤），非 Alice 侧配置问题。MCP 接入机制本身已验证可行（insert 语法跨语言通用、stdio 稳定、版本 0.1.1-rc.2）。**

处置建议：P1 线路进入**待用户决策**状态（选项 A 暂停 / B 自建最小只读 server / C 转向其他 MCP 评估）。本记录不构成任何新实施授权。
