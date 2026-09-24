# 客户端测试数据通道：**本地 agent + 文件信箱**（2026-09-24 用户裁定方向）

> 用户原话要点：「做成 agent 的提示词或者干脆做成能导入的 preset 预设，因为新设备客户端要重新定位，
> 并且云端还会做一些客户端调整，**脚本完成不了**。在新设备布置一个新的 windows agent（dsh 或其它），
> 准备一个通道让云端和本地 agent 通信；可以全自动，也可以我手动触发通信（靠文件通信，我唤醒 agent 读取）。
> 上传**不需要全自动**，我可以手动唤醒 agent 并手动调整上传的内容。」

## 一、架构（两台机器、两个 agent、一条信箱）

```
   ┌──────────────────────────── codespace（云端）────────────────────────────┐
   │  云端 agent：开发 / 编译 / 无头电池 / 决定要改客户端什么                  │
   │  信箱目录（就在它的文件系统里）：~/bus/、~/client-info/、~/outbox/        │
   └───────────────────────────────┬──────────────────────────────────────────┘
                                   │  gh CLI（PAT）—— 只有这一条网络通道
   ┌───────────────────────────────┴───────── 玩家 Windows 机器 ──────────────┐
   │  客户端管家 agent（DSH headless 单发）：查日志/截图、按请求上传、执行调整 │
   │  唤醒 = 用户双击 client-agent.cmd（或带一句任务）                          │
   └──────────────────────────────────────────────────────────────────────────┘
```

**为什么不直连**：DSH 自带的 `dsh-agent-bus` 是**同一台机器、同一个 workspace 内**的多 agent 编排（实测：
存储在本机 `~/.dsh/agent-bus/`）⇒ 不能当跨机通道。跨机只有一条可靠链路：`gh`（Windows 侧已有 gh + PAT，
见 `tools/codespace-tunnel.ps1`）。所以**把云端的文件系统当信箱**。

## 二、信箱协议（约定即接口）

| 路径（都在云端） | 谁写 | 谁读 | 内容 |
|---|---|---|---|
| `~/bus/to-win/<yyyymmdd-HHmmss>-<主题>.md` | 云端 agent | 管家 agent（用户唤醒时） | 请求：要什么数据 / 要改客户端什么 |
| `~/bus/to-cloud/<yyyymmdd-HHmmss>-<主题>.md` | 管家 agent | 云端 agent | 回执（四段，见下） |
| `~/bus/to-win/.done` | 管家 agent | — | 已处理文件名清单（一行一个），避免重复劳动 |
| `~/client-info/<tag>/…` | 管家 agent | 云端 agent | **大附件**：`logs/latest.log`、`screenshots/*.png`、`crash-reports/*`、`manifest.json` |
| `~/outbox/…` | 云端 agent | 管家 agent（`-PullOutbox`） | 反向产物：构建好的 jar、要放进客户端的文件 |

**回执四段（缺一不可）**：① 做了什么 ② 上传了什么（**本地路径 → 远端路径 + 字节数**）③ 没做什么与原因
④ 需要用户操作的（重启客户端？换 mod？）。

**大附件不进消息**：消息里只写路径（实测：单张截图 5–7 M、69 张共 252 M ⇒ 塞进消息毫无意义）。

## 三、唤醒方式

```bat
:: Windows（管家 agent）—— 单发：跑一次、打印结果、退出
client-agent.cmd                                  :: 默认任务 = 读信箱并照做
client-agent.cmd "上传最近一次测试的 latest.log 和之后的截图"
```
依赖 `dsh --profile headless "<任务>"`（CLI 帮助原文实测：「answer one task, print the result, and exit」）。

云端侧同构：web UI（人看）或 `dsh --profile headless "<任务>"`（脚本化）。

## 四、Windows 一次性安装（`client-agent.cmd -Install` 做三件事）

1. `npm i -g @deepseek-ai/dsh@0.1.5-rc.3` —— ⭐ 实测这台机器**已有 node v24.19.0 + npm 11.17.0**（nvm4w）⇒ 前提已满足；
2. 把 `presets/alice-client-master/` 拷进 `%USERPROFILE%\.dsh\.agent-presets\`（preset 就是**目录**：`preset.yml` + `agent.cordis.yml`）；
3. 检查 `%USERPROFILE%\.dsh\.credentials.yaml`（含 API key；**从本机拷或在 DSH 里配一次，绝不进 git**）。

> `settings.yaml` 的 `agent-presets.default: alice-client-master` 决定默认用哪个人设（也可在 UI 里选）。

## 五、已实测 / 未实测（诚实边界）

| 项 | 状态 |
|---|---|
| preset 格式 = 目录（`preset.yml` + `agent.cordis.yml`，人设在 `persona.config.prefix`） | ✅ 从本机可用 preset 导出并核对（18 个顶层行一致、persona 段之外**逐字相同**） |
| `dsh --profile headless "<任务>"` 单发模式存在 | ✅ CLI 帮助实测 |
| Windows 有 node/npm（装 DSH 的前提） | ✅ 实测 `v24.19.0` / `11.17.0` |
| 信箱用的 gh 操作（`ssh -- 'cat >…'`、`cp -e <本地> remote:/绝对路径`、stdin 追加日志） | ✅ 今天全部实测过（见 `CLOUD_MIGRATION.md` §9/§14） |
| **Windows 上装 DSH 并跑通 headless + preset** | ❌ **未做**（需要用户点头；`client-agent.cmd -Install` 已写好但没在 Windows 上执行过） |
| 每文件传输开销 8–60 s ⇒ 别按文件发（批量尽量塞进同一次 ssh） | ✅ 今天 bench 过（这就是"上传不必全自动"的另一个理由） |

## 六、与已有脚本的关系（都保留，但降级为"工具"）

- `codespace-tunnel.cmd`：看云端 web UI（回环入口）；
- `client-info-watch.cmd`：**可选**的自动 watcher（用户不需要它，但管家 agent 可以调用它做"增量推日志"）；
- `cloud-rollback.sh`：只在本机 WSL 用（回迁准备）。
⇒ **主路径 = agent + 信箱**；脚本只是 agent 手边的工具。

## 七、纪律

1. **单写者**：Alice 源码只许云端 agent 写；Windows 管家 agent **不许改源码**。
2. **上传必须显式点名**（禁止整目录）；传前列出路径 + 大小。
3. **每台机器先确认客户端路径**（不许照抄另一台的 `D:\JAVA_projects\...`）。
4. 改客户端配置**先备份**（`.bak-<时间戳>`）并说清是否要重启客户端。
5. **不碰 `saves/新的世界`**（世界母本的来源）。

## 八、试跑记录：本机 WSL DSH 真跑了三次（2026-09-24）

**做法**：把 preset 导进 `~/.dsh/.agent-presets/`，临时把 `settings.yaml → agent-presets.default` 切到 `alice-client-master`
（⚠️ headless profile **没有** `--preset` 参数 ⇒ 只能靠这个默认值切），然后
`dsh --profile headless "读信箱并按请求行动：只写回执到云端，不上传任何日志/截图"`。

| 轮次 | 结果 | 暴露的缺陷 ⇒ 修法 |
|---|---|---|
| 1 | ❌ 它把回执写到了**本机** `~/bus/to-cloud/`，云端永远收不到；客户端路径也猜成了原版 `.minecraft` | ① 人设必须写死「**信箱在云端、本机不许建 `~/bus`**」+ 给出 `gh codespace ssh/cp` 的具体命令；② **机器相关事实不许写在提示词里** ⇒ 全部从配置文件 `~/.alice-client.json` 读（由 `client-agent.cmd -Install` 在本机发现后写入） |
| 2 | ❌ 它拒绝行动：`gh auth status` 说没登录（PAT 只在环境变量里，agent 的 shell 拿不到） | `gh auth login --with-token` **也不行**：实测本 PAT scope = `codespace, repo`，而 gh 登录流程**额外要求 `read:org`** ⇒ 报 `missing required scope 'read:org'`。正解 = **把 token 写进 gh 自己的凭据文件**（Windows `%APPDATA%\GitHub CLI\hosts.yml`，WSL `~/.config/gh/hosts.yml`）⇒ 之后不需要任何环境变量 |
| 3 | ✅ **成功** | 它读云端信箱 → 从配置确认客户端路径 `/mnt/d/JAVA_projects/worldedit-test/versions/1.20.1-Forge_47.4.10`（正确）→ 回执写到**云端** `~/bus/to-cloud/20260924-040000-selfcheck.md` → 把请求记入云端 `.done` → **没上传任何附件** |

**第三轮回执原文（四段齐全，就是协议要求的形状）**：

```
# 回执：selfcheck
## 做了什么           已读取请求 20260924-035539-selfcheck.md。从本机配置确认客户端路径：/mnt/d/JAVA_projects/...
## 上传了什么         仅本回执：/tmp/…-receipt.md → /home/vscode/bus/to-cloud/20260924-040000-selfcheck.md。未上传日志、截图
## 没做什么与原因     未读取或上传任何日志/截图，遵循本次指示
## 需要用户操作的     无需操作
```

**三条可复用结论**（已写进人设，避免下次重犯）：
1. **机器相关的事实（路径、codespace 名）必须落在配置文件**，提示词只写"去读配置文件"；
2. **跨机资源一律给"哪台机器 + 具体命令"**，`~/…` 这种写法必须点名是谁的 `~`（本机 `~/bus` 就是踩坑点）；
3. **凭据给"可执行的落地方式"**，不是给一个文件名（`gh auth login` 的 scope 陷阱已记档）。

**本轮验收等级**：`BUILT` + 本机 WSL `SMOKE_TESTED` + ⭐ **真 Windows 全链路 `WINDOWS_CLIENT` 级验证通过**（13/13 自检、点名上传、缩图、sha256 一致、云端 `read_image` 能看图；详见 `CLIENT_AGENT_NEW_DEVICE_TEST.md` §10）；
**未做**：Windows 新设备上装 DSH / 跑 headless / 导入 preset 并试跑（要用户点头）。
