# Alice 项目 Skills 索引（**生成物，禁手改**）

> ⚠️ **本文件由脚本生成** —— 手改会被门禁判红。
> 重新生成：`python3 tools/skills-index.py --write`　·　校验：`python3 tools/skills-index.py --check`
> 门禁：`tools/check-skills-index.sh`（挂 `tools/check-all.sh`）。

## ⛔ 先说清楚：skills **不是规则**

⭐ **技能 = 可复用的做法**，服务于**跨会话 / 跨项目**。
⛔ **规则**（能机械判、违反会失败的）住 `AGENTS.md` 与 `tools/check-*`，⛔ **不住这里**。
⚠️ 两层今天**还在拆**（草案 `§D′-5` / `§D′-6`）⇒ 这是**现状登记**，⛔ 不是最终形态。

## 读数（**只从磁盘复算**）

| 量 | 值 |
|---|---|
| skill 份数（磁盘） | **21** |
| ⭐ 带完整 frontmatter（`name` ＋ `description`） | **21 / 21** |
| 在 `skills-manifest.yml` 里登记了 `scope` | **21 / 21** |

## 表（**门禁逐字节比对的就是这一段**）

| skill | 领域 | 一句话用途（来自 frontmatter `description`） | 行数 |
|---|---|---|---:|
| **— 🧭 方法论 / 流程类 —** | | | |
| `alice-baritone-kernel-alignment` | `methodology` | Alice 是 Baritone 兼容内核——非 Alice 目标差异部分一律先对齐 Baritone，禁止自制替代内核与补丁堆叠。 | 68 |
| `alice-client-artifact-acceptance` | `methodology` | 确认 Windows 客户端实际运行的是本轮构建工件，并区分代码、日志和真人观察证据。 | 60 |
| `alice-discussion-before-repair` | `methodology` | 在客户端反馈或重复失败后先对齐事实和根因假设，再决定是否实施修复。 | 62 |
| `alice-inventory-transaction-conservation` | `methodology` | 让 Alice 物品搬运以服务端守恒、阶段记录和可恢复失败为核心。 | 31 |
| `alice-path-planning-execution-contract` | `methodology` | 防止 Alice 寻路结果与真实移动执行混淆，固定脚位、支撑、路径状态和完成条件。 | 36 |
| `alice-scene-based-testing` | `methodology` | 用"一键场景 + 一键自检"把真人测试压缩到两次操作，并保证证据可判读、失败不被掩盖。 | 281 |
| `alice-session-memory-and-direction` | `methodology` | 在会话切换和上下文压缩后恢复 Alice 当前方向、决策、验证状态与开发习惯。 | 63 |
| `alice-task-lifecycle-and-recovery` | `methodology` | 保持 Alice 任务状态、取消、失败码、超时和 bot 可回收性的统一契约。 | 33 |
| `alice-windows-client-collaboration` | `methodology` | 规范 Alice 在 Windows 客户端上的真实测试、日志证据收集和用户反馈闭环。 | 153 |
| `debugging-root-cause-analysis` | `methodology` | 在多次失败、结论与矛盾或盲试时，用可证伪假设与对照验证找根本原因，而不是修症状。 | 212 |
| `failure-pattern-recognition` | `methodology` | 识别反复出现的失败模式，从多次失败中提取共性根因，避免治标不治本。 | 417 |
| `large-refactor-survey-and-verify` | `methodology` | 大范围重构/改革类任务的勘测与验证方法 —— 把"开发者的问题"当一等输入、把"独立对抗性复核"当默认动作，并在开发者不在场时只做可逆与可机械判定的事。 | 201 |
| `minimal-implementation-planning` | `methodology` | 将复杂功能拆成小而完整、可观察、可回滚的增量，避免一次修改多个系统和连续补丁。 | 152 |
| **— ⚠️ 未登记 `scope`（在 `skills-manifest.yml` 里补） —** | | | |
| `forge-blockpos-mutability` | `—` | Forge 的 BlockPos 有可变/不可变两种；部分 API（如 betweenClosed）复用同一个 MutableBlockPos ⇒ 直接存引用会让所有坐标都变成最后一个值。识别与规避这类陷阱。 | 313 |
| `forge-capability-adapter-boundary` | `—` | 区分 Forge capability 的事实读取与模组业务语义，防止未知机器被错误写入。 | 28 |
| `forge-container-menu-protocol` | `—` | 理解 Container/Menu 系统的客户端-服务端协议、槽位同步、网络包与常见 GUI 问题。 | 550 |
| `forge-entity-physics-collision` | `—` | 理解 Minecraft 实体物理系统、碰撞检测、移动计算与 travel() 方法。 | 572 |
| `forge-entity-sync-broadcast` | `—` | 理解 Minecraft 实体同步机制、网络包广播、追踪范围与客户端更新时机。 | 450 |
| `forge-event-priority-cancel` | `—` | 理解 Forge 事件系统的优先级、取消机制、总线类型与事件传播顺序。 | 592 |
| `forge-fakeplayer-lifecycle` | `—` | 理解 FakePlayer/ServerPlayer 的生命周期、注册方式、网络连接与 tick 流程。 | 347 |
| `minecraft-client-server-sync` | `—` | 理解 Minecraft 的客户端-服务端分离架构、同步机制与常见混淆点。 | 448 |

## 症状速查（人写的，⭐ 住在生成器里）

| 症状 | 查哪个 skill |
|---|---|
| 实体在客户端看不到 | `forge-entity-sync-broadcast` |
| GUI 打不开 / 数据不对 | `forge-container-menu-protocol` |
| 实体移动有问题 | `forge-entity-physics-collision` |
| 事件监听器不生效 | `forge-event-priority-cancel` |
| FakePlayer 相关问题 | `forge-fakeplayer-lifecycle` |
| 对象的值「神奇地」变了 | `forge-blockpos-mutability` |
| 单人正常、多人出错 | `minecraft-client-server-sync` |
| 不知道为什么失败 / 同一问题失败 2+ 次 | `debugging-root-cause-analysis` |
| 多次失败但症状不同 | `failure-pattern-recognition` |
| 需要真人跑客户端验证 | `alice-scene-based-testing` |
| 怀疑客户端跑的不是本轮工件 | `alice-client-artifact-acceptance` |
| 跨多包/多文档一次改不完的大改造 | `large-refactor-survey-and-verify` |

## 怎么用

1. **开工前**：按上表 `领域` 挑 1–3 份，`read` 它的正文（⛔ 别全读）；
2. **遇到问题**：先查「症状速查」，再读对应 skill 的「何时使用」节；
3. **⚠️ 别把 skill 当依据**：它给的是**做法**，⛔ 不是**边界**；边界要引 `D-###` 或门禁。

## ⛔ 本表**假装不了**的（诚实边界）

1. ⛔ **它判不了 skill 写得好不好** —— 只判「磁盘上有几份、frontmatter 齐不齐」。
2. ⛔ **它判不了「该不该用某个 skill」** —— 那要判断，⛔ 不在门禁能力内。
3. ⚠️ **DSH 今天还发现不了这些 skill**（实测：`.alice-supervision/skills/` 不在任何被扫描根下，
   且文件名 `*.skill.md` ≠ DSH 要求的 `*.md` / `<name>/SKILL.md`）⇒ 登记在台账 `O149`。

