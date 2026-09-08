# 监督员会话「最后的记忆」交接留档

- 日期：2026-08-24
- 来源：监督员会话 `session-302a9da6-184c-4338-9567-56e853b501ed` 尾部记忆恢复
- 背景：本次电脑重启导致该会话日志 seq 中间空洞（seq 498182→501529，缺约 3347 事件），已完成重建恢复（保留约 93% 历史）。seq 501529 之后的内容不在 3083 会话历史中显示，但已从原始损坏文件完整导出。
- 原始导出文件（recovery 目录）：
  - 宏观对话：`.dsh-runtime/recovery-backup/supervisor-tail-macro.txt`
  - 全部事件：`.dsh-runtime/recovery-backup/supervisor-tail-memory.txt`
  - 原损坏文件备份：`.dsh-runtime/recovery-backup/session-302a9da6-184c-4338-9567-56e853b501ed/session.jsonl.zstd`

---

## 决策脉络（seq 501529 之后，时间顺序）

1. **暂停 P1 物理问题**（seq 501617）：确认稳定复现，先暂停。
2. **引入 Skill 契机**（seq 501853）：评估 Forge modding skill 接入可行性（Hermes 参考）。
3. **决定接入 Skill**（seq 504186）：方案 1（通用包优先）；监督员创建 3 个 skill 初稿、DSH 维护员审核；追加两项调查——MCP 服务器 + 全项目重构。
4. **开发工具调查**（任务 `5dc78db7`，验收 success，seq 512938-513112）：产出 `research/dev-tools-mcp-survey-20260824.md`，推荐 P0=Forge GameTest 分层 + `adhi-jp/minecraft-modding-mcp` + JDWP/correlation-id 双端日志。**用户已「只批 P0」。**
5. **重构调查**（任务 `fb6b909e`，验收 success，seq 526276-527757）：产出 `research/project-refactor-survey-20260824.md`，结论 0 完全替代 / 8 部分借鉴 / 14 保持自制，**不接入 Baritone/Carpet/Mineflayer/TLM**。
6. **P0 可证伪闭环路线**（任务 `160ad0f9`，验收 success，seq 523647-524670）：产出 `research/p0-debug-evidence-closure-plan-20260824.md`。
7. **DSH 维护员改造**（seq 524799→535709）：已走完「五问答复 → 四项修订 → 构建链路冻结 → 基线验证」，当前停在 **阻塞点待监督员决策**。

---

## 待决策阻塞点（监督员）

`dsh-baseline-verify-report-20260824.md`：基线 `105b9df`（npm 0.1.1 严格对应）在完整 workspace 下**服务端 tsc 通过**，但**客户端 12 个 typecheck 错误全部来自上游代码**（`src/client/*.tsx` 用裸 `JSX.Element`，而 `@types/react@19.2.18` 已移除全局 JSX namespace）。

推荐**选项 1**：把 12 处改为 `React.JSX.Element`（或 `import type { JSX } from 'react'`）——纯类型标注、非行为、不混入 P0。

**待决策**：接受选项 1（最小类型修复）还是停止 P0 重新规划。批准后维护员才在开发源实施类型修复 → 完整构建 → 记 SHA-256 → 部署 3082 验证。

当前 plan 保持 DRAFT，未改 Harness / 3083 / profile node_modules。

---

## 环境约束更新（2026-08-24，监督员记录）

- **所有工作组成员（监督员/规划员/主开发员/深度调查员/DSH 维护员）当前均使用 `deepseek-v4-flash`**，**无识图功能**。
- 任何需要识图的工作（如 Windows 客户端截图验证、GUI 视觉验收、图片证据审核）必须**停下并告知用户**，不得用文本描述替代。
- P0 skill 升级与 P1-S1 MCP 挂载核查均为纯文本/代码核查，不依赖识图，可正常进行。

## P1 Git MCP 试点暂停（2026-08-25，监督员记录）

- 用户决策：选项 A（P1 线路暂停，保守）
- 验证成果：MCP 接入机制已验证可行（insert 数组语法 / dsh-mcp-client 0.1.1-rc.2 / 隔离 profile 机制 / stdio 跨语言），官方 Git MCP server 2026.8.18 含 13 写工具且无过滤配置（Git server CLI 与 mcp-client 均无 allow/deny），不满足只读白名单 → P1-S2 不可行（外部限制）
- 重启条件：官方支持过滤 / 用户授权自建 4 工具只读 server / 转向其他 MCP
- 文档：HANDOVER「P1 Git MCP 试点：暂停」章节 + 4 份验证报告（S1/深调研/VB/S2 均在 research/ 落盘）

## 最小唤醒机制：验证策略调整（2026-08-25，监督员记录）

- 机制状态：✅ 已部署 3083（commit 99a638c，request_input 后 notifySession 通知 dispatcher，净改 8 行，验收通过+核准部署）
- 验证策略：**不做人工测试**，等待真实场景触发（用户决定）
- 真实触发识别标准：
  - 未来某 worker 调用 request_input 时，dispatcher（监督员）应自动收到 `<dsh-agent-bus ... tool="request_input" sender="...">` 头消息
  - 若任务卡在 input-required 而监督员未收到通知 → 机制失效信号，立即记录并报用户（届时按监督流程处理，不擅自改代码）
- 当前可用 peer：深度调查员(session-f26bd205) / 主开发员(session-30b693e7)；test-worker 会话不存在，如需人工验证须新开

## 工具缺失请求处理规范（2026-08-25，用户确认）

收到成员报告「无法使用 read/bash 等工具」时，**不直接代读/代做**，按以下流程：
1. 先请该成员**列出当前可用工具清单**（或复测 read 具体报错），区分「真缺失（运行时装配问题）」与「瞬态/误报（重启后已恢复）」
2. 若复测可用 → 让其自行读取，不代劳
3. 若确认真缺失 → 记录为配置/运行时问题（可复现性证据），再决定代读或修复会话配置
4. 背景：之前调查员报告 read/bash unknown tool 与 preset 配置无差异，且它 P1-S1 时能产出源码行号证据——倾向瞬态/误报，但当时未验证根因
