# 传输模块彻查（2026-09-13，用户要求"从架构与实现查有没有屎山，并定要不要二次重构"）

> 方法：读遍 `transfer/` 全部 13 个文件 + `task/TransferTask` + 2 个物品类 + 选择器事件 + 3 条命令 + 4 个夹具；
> 行数与引用关系统计；对"死码/只写状态/授权维度/测试侵入"逐项 grep 取证。**本文件只记录事实与建议，未改代码。**

## 1. 架构现状（一张图）

```
玩家侧入口                     生产执行路径                          持久化
────────────                  ─────────────                        ─────────
transfer_endpoint_selector ─┐
  （右键记 source/dest）     │
/commands transfer-test …   ─┤
  transfer-status           │
  transfer-selection status  │
                             ▼
              TransferSelectionData（临时草稿：内存 Map<server, Map<uuid, draft>>，ServerStopping 清）
                             │ TransferSelectionSubmission（提交校验）
                             ▼
                      TransferRequest（不可变：actor/bot/source/dest/item/count）
                             │ admit()（重复拒绝、端点校验、容量预检 CapacityPreflight）
                             ▼
   TransferTask（144 行，4 相位状态机）
     TO_SOURCE ──走位──▶ SOURCE_WRITE ──走位──▶ DESTINATION_WRITE
         │                    │                        │
         │        ChestBotTransferPrimitive（267 行，唯一写入原语）
         │          simulate → 真实写入 → prove(三处增量一致)
         ▼                    ▼                        ▼
   TransferLedgerData（380 行 SavedData：请求 + 状态迁移 + G5 物品移动记录）
                             │
                    blocksBot(uuid)（IN_TRANSIT_BOT/SUSPENDED ⇒ 禁止替换任务）
                    suspendUnfinished(SERVER_RESTART)（开服时把在途传输挂起）
```

## 2. 结论（一句话）

**不是屎山，但确有三处真问题**：① **生产与测试错位**（1/3 代码量是夹具，且测试钩子伸进生产原语）；
② **死码与只写状态**；③ **容器写入没有授权/预算维度**（只有 G5 审计）。
核心部分（写入原语的两段式 + 账本的挂起/阻塞语义 + 重启处理）**设计是扎实的，不建议推倒重写**。

## 3. 发现清单（每条带证据）

### F1 生产与测试错位（**最大的一处**）
| 证据 | 内容 |
|---|---|
| `transfer/TransferFixture.java`（327 行）、`TransferSelectionFixture.java`（88）、`TransferTestHooks.java`（30）、`item/TransferEndpointSelectorEventsFixture.java`（38）、`command/TransferSelectionCommandParseFixture.java`（50） | 合计 **533 行**夹具/测试代码放在**生产源码树**，约占模块足迹的 **1/3** |
| `ChestBotTransferPrimitive.java:35,42,59,68` | 生产写入原语里 **4 处**调用测试钩子 `TransferTestHooks.fireBeforeFresh()/fireAfterActual()` |
| `TransferTask.java:31,35,75-76` | 生产状态机里有 `static FixtureMovementOutcome fixtureMovementOutcome` + 分支（夹具专用短路） |
| 全仓库 **47 个文件**含 "Fixture" | 这是**项目级惯例**（夹具与生产同树），传输模块只是代价最刺眼的一处（钩子进了原语） |

**影响**：生产代码的正确性依赖测试类；读者无法判断"这段分支是生产逻辑还是测试"；删测试会连生产一起红。
**修法**：夹具迁出到独立包（如 `com.dddgn.alice.fixture.transfer`）；生产侧只保留**一个显式、文档化的接缝**
（或彻底去掉：用"注入端点/物品"的方式驱动夹具，而不是在内部埋钩子）。

### F2 模块**没有直观入口**，唯一入口是被弃用的 legacy selftest
| 证据 | 内容 |
|---|---|
| `BotSelftest.java:346-349` | 传输的 4 个夹具**只**由 `/alice selftest` 调用 |
| `BotSelftest` → `InterfaceScanner.capture` → `scanMek` | 该 harness 因**硬引用 Mekanism** 在无 Mek 客户端**必崩**（2026-09-13 崩溃报告） |
| `item/TransferEndpointSelector` + 3 条命令 | 是"手工逐步操作"的入口，不是"一键跑完出 SUMMARY"的判据入口 |

**影响**：用户"不太直观"感受的来源 —— 传输是**唯一没有零参数一键自检**的模块。
**修法**：新入口 `alice:transfer_check`（零参数右键，跑现有 4 个夹具，输出 `[Transfer] SUMMARY key=VALUE`），
与其它模块的自检物品同规格。

### F3 死码 / 只写状态（K-5 同族）—— **已处置（R3，2026-09-13）**
| 证据 | 内容 |
|---|---|
| `TransferCodes` | **3 个常量零引用**：`SURVIVAL_SUFFOCATING`、`ACTOR_DISCONNECT`、`BOT_MISSING` |
| `TransferLedgerData.State` | `SOURCE_LEG_PRE` / `DESTINATION_LEG_PRE` **各只有 1 处引用**（写入点），**无读者** ⇒ 只写状态 |

**对照**：`SUSPENDED`（23 处）、`IN_TRANSIT_BOT`、`ABORTED` **确有读者**（`TransferTask:54-63`、`BotManager:1373`）
⇒ 状态机**不是**装饰。

**处置结论（R3）**：
- 3 个零引用错误码（`SURVIVAL_SUFFOCATING`/`ACTOR_DISCONNECT`/`BOT_MISSING`）**已删除**；
- `SOURCE_LEG_PRE`/`DESTINATION_LEG_PRE` **保留但重新定性为"审计哨兵"**：账本的本职是**证据链**
  （类文档写明 "stores evidence"），这两个状态标记的是"某段写入之前"这一刻，供事后复盘读时间线；
  **不是**决策用的状态 ⇒ 不按死码删除（删掉等于删证据）。已在 `TransferLedgerData` 的枚举上注明。

### F4 容器写入**没有授权/预算维度** —— **已修复（R3，2026-09-13）**
| 证据 | 内容 |
|---|---|
| `grep WriteBudget\|WriteGrant\|WriteReason` 于 `transfer/*` 与 `TransferTask` | **零命中** |
| 已有 | G5（2026-09-13 补）只记录了"物品移动"用于审计；`admit()` 有重复/端点/容量校验 |

**影响**：方块破坏/放置有预算与授权（D-076/D-106），**容器写入没有**。
"往别人箱子里放东西"是不是"改世界"、要不要授权/预算、要不要进 `WORLD_WRITE_AUTHORIZATION.md`
的 A 表 —— **当前没有答案**（这正是"未知语义不许猜"的反面：不是猜了，而是**没定义**）。
**用户裁定（2026-09-13）**：**容器写入算世界改动** ⇒ 与方块写入同规格。

**已实现（R3）**：
- `WriteReason.CONTAINER_TRANSFER`（`Policy.EXPLICIT_TARGET`）；
- `WriteBudget` 新增**第三维度**：`Caps.maxContainerWrites`（默认 32）+ `consumeContainerWrite(...)`
  （超限即 `REFUSED`）+ `remainingContainerWrites(...)`，并进作用域 SUMMARY
  （`containers=N/M refusedContainers=N`）；
- `TransferTask` 两段写入前各消费一次；被拒 ⇒ `container_budget_exhausted`（如实失败）；
- **审计带授权**：G5 的物品移动记录新增 `requester`/`reason` ⇒ 能回答"谁按什么理由动的这箱东西"；
- 登记表补 **A11 容器写入（传输）**。

### F5 与 legacy 内核耦合（**本会话已修**）
`TransferTask.move()` 原用 legacy `SurfacePathfinder` + `PathExecutor`（2026-09-13 D-159 已迁移到 `PathRetryRunner`）
⇒ 生产路径已收敛到单内核，并白拿 K-1（预算耗尽先走前缀再重规划）。

### F6 值得保留的好设计（避免重构误伤）
- **两段式写入 + 三重增量证明**：`simulate → 实际写入 → prove(source/bot/destination 三处增量一致)`，
  不一致时报 `*_delta_mismatch`/`unknown_discrepancy` ⇒ 这是"**宁可承认状态不明，也不假装成功**"的正确姿态；
- **在途阻塞**：`blocksBot()` 让"传输中/已挂起"的 bot 拒绝被替换任务（`BotManager:1356`）；
- **重启语义**：开服时 `suspendUnfinished(SERVER_RESTART)` 把在途传输挂起（`BotManager:1231,1252`）——
  比决策层此前"重启即丢"的处理更完整；
- 数据模型是不可变 record + 稳定错误码表 ✓。

## 4. 二次重构建议：**不做推倒重写，做三步定向重构**

| 步骤 | 内容 | 代价 | 风险 | 验证 |
|---|---|---|---|---|
| **R1 生产/测试分离** | 4 个夹具 + `TransferTestHooks` 迁到独立包；原语里的 4 处钩子调用改为"可注入接缝"或移除 | 中（纯搬移 + 少量改签名） | 低（行为不变） | 编译 + `alice:transfer_check` 全绿 |
| **R2 新增一键入口** | `alice:transfer_check`：零参数右键跑完 4 个夹具，输出 `[Transfer] SUMMARY` | 小 | 低 | 客户端跑一次 |
| **R3 清死码 + 定授权** | 删 3 个死常量、处理 2 个只写状态（删或开始读）；**用户裁定后**补容器写入的授权登记（A 表） | 小~中 | 低 | 自检 + 文档 |
| （可选 R4） | 把 `BotSelftest` 退役（见 §5） | 小 | 低 | 电池 22 项全绿 |

**为什么不做推倒重写**：核心（原语/账本/阻塞/重启）已被真实夹具验证过，重写的收益是"更干净"，
代价是**重新引入未知缺陷**（本项目最贵的错误类型）；而 F1–F4 都能在不碰核心语义的前提下修掉。

## 5. selftest 的去留（用户："重新做，或者干脆不用"）

**建议：退役 `/alice selftest`**，理由（均为事实）：
1. 它**必崩**于无 Mekanism 的客户端（`InterfaceScanner.scanMek` 硬引用）—— 2026-09-13 崩溃报告；
2. 它是 **13 个 legacy 测试 + legacy 内核回归**的集合，与现在的 in-game 电池（22 项）**重复**且判据陈旧；
3. 与项目现行规矩冲突：AGENTS.md 要求"**游戏内零参数入口 + 一次跑完出 SUMMARY**"，
   而 selftest 是"命令触发 + 只见日志"。

**退役时要保住的能力**（不要连价值一起删）：
- 传输 4 夹具 ⇒ 由 **R2 的 `alice:transfer_check`** 承接；
- `InterfaceScanner`（**功能**而非测试：未知模组能力只读探查）⇒ **保留**，但必须**absence-safe**
  （把 `mekanism.*` 代码搬进只在装了 Mek 时才加载的类 + `ModList.isLoaded("mekanism")` 守卫）——
  这是**必须先修的崩溃源**，与 selftest 去留无关；
- legacy `PathingRegression`（只被 selftest 调用）⇒ 随 selftest 一起退役（新内核有 `alice:pathing_regression` 覆盖）。

## 6. 待用户裁定

1. **R1–R3 做不做**（我建议做）；
2. **容器写入算不算"世界改动"**（要不要授权/预算 + 进 A 表登记）；
3. **`/alice selftest` 退役**（我建议退役），以及是否**立即**修 `InterfaceScanner` 的 Mekanism 硬引用
   （我建议立即修，它现在是"任何无 Mek 客户端一跑就崩"的活雷）。
