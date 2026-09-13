# 修复补丁审查（2026-09-13 下半场）

> 审查者：Alice Forge Assistant；被审范围：`cbd177a..de84164`（6 个提交、86 文件、**+1979 / −3701**）。
> 依据：客户端 `latest.log` / `crash-reports/` / 归档日志、源码、文档一致性、以及项目既有规矩
> （`AI_DEVELOPMENT_PLAYBOOK.md`、`.alice-supervision/skills/`）。
> 结论口径：**保留 / 需清理 / 未闭环 / 单独立项** —— 不允许"看起来修好了"。

---

## 0. 本轮客户端证据基线（本轮测试）

```
[Regression] SUMMARY … pathing=PASS K4=OK(goal_not_standable=0 final_segment_not_standable=0 写入类例外=83)
             (23/23) ticks=3323 → PASS
MineRegression exec_* … idempotent=true （5 处）
[Bot] entity_tick_missing  出现 0 次
[R4 Session] segment_stall 出现 0 次
模型加载错误 0 条；无崩溃
```
⇒ 本轮：电池全绿、崩溃修复后**未再崩**、冻结**未复现**、幂等断言为真。

---

## 1. 变更清单与逐项结论

| # | 决策 | 变更内容 | 性质 | 客户端/静态证据 | 结论 |
|---|---|---|---|---|---|
| 1 | **D-166 / D-169** | K-3 `safeToCancel` 承诺点（8 执行器）+ `stopTask` 安全点延后 + L2 空中门；`alice:k3_stop_check`（右键 DEFER / Shift+右键 FORCED）；**移出电池**（25→23 项）+ `fixture_not_top_level` 前提断言 | 新增能力 + 夹具纠错 | 两种模式均实测（`deferred=1` / `forcedUnsafe=1`；terminal `cancelled:k3_defer:safe_point` / `…forced_unsafe`） | **保留**（已闭环）。`fixture_not_top_level` 本身从未被触发 ⇒ 见 §2-6 |
| 2 | **D-167 + 附注一** | "可站"谓词 6 处复制 → 唯一定义 `MovementHelper.canStandCentered`（8 调用点，纯重构）；目标准入**先测量不硬拒**；收口：真异常 0 ⇒ 删临时告警、留计数 + 电池 `K4=` 自断言 | 纯重构 + 测量 + 收口 | 完整电池 `K4=OK(真异常 0 / 写入类例外 88)` | **保留**（已闭环）。收口义务已履行（`[K4]` 代码 0 处） |
| 3 | **D-168** | `mine_regression` 的 `dropsLeft` 改**基线 UUID 增量**；三个挖掘场景函数补 `kill @e[type=item,…]` | 夹具测量纠错 | `mine_regression` 11/11 PASS（多轮） | **保留**。⚠ "残留不计入"分支**从未被实战触发**（本轮世界无残留）⇒ 见 §2-6 |
| 4 | **D-170** | 3 个 0 字节模型补齐 + 死贴图引用修正；新增 `tools/check-item-models.sh`（空文件/坏 JSON/死贴图，自测过）并写入构建前清单 | 资源缺陷修复 + 自检 | 客户端 `Failed to load model alice` **0 条**；脚本 `checked=66 … PASS` | **保留** |
| 5 | **D-171 / D-173** | K-2 批 3+4：legacy 双内核**整批删除 33 文件**（`pathing/*` 19 + `pathing/movement/*` 14）+ 残留 `.backup`；保留 `MovementHelper`/`FootCellRuleCheck`/`risk/RiskSwitches` | 删除（净减重） | 删后 `build` PASS；按路径分析**外部真引用 = 0** | **保留**。结构遗留见 §2-5 |
| 6 | **D-172** | K-5：`POSTCONDITION_FAILED` 不删值、改**给它生产者**（纯函数 `PathSessionStatus.classify`，唯一定义）；新增自检 `session_status_no_dead_value`（分类表 + 覆盖率） | 语义接线 + 自检 | 电池内 `session_status_no_dead_value=PASS` | **保留** |
| 7 | **D-174 + 附注一/二** | 段卡死诊断 `segment_stall`（**失败终态**输出：输入串/坐标/速度/onGround/三格方块/段耗时 + `entityTicksInSegment`/`travelCallsInSegment`）；`BotPlayer` 两个单调计数 | 诊断（永久但仅失败时打） | **真实抓取 2 次**，读数一致 ⇒ 直接定性 | **保留**（诊断价值已被证明；冻结未闭环前不可删） |
| 8 | **D-175** | 崩溃修复：`MineTask` **终态闩锁** + `tickRestore` 空守卫 + 夹具不再 tick 终态内层任务 + **幂等断言**（`idempotent=`）；`RegressionBatteryTask` **单步异常隔离**（FAIL + 完整栈，不毁整轮） | 崩溃修复 + 契约 | 崩溃前有完整现场（crash-report + 日志序列）；修后本轮 `idempotent=true` ×5、无崩溃 | **保留**。同类隐患推广见 §2-2 |
| 9 | **D-176** | "bot 物理冻结"实锤（`entityTicksInSegment=0`）+ `[Bot] entity_tick_missing` 看门狗（removed/区块/玩家表/连接/task） | 诊断（只报不改） | 本轮未复现（0 次） | **未闭环**（病因已定性、未修）；见 §2-1 |

---

## 2. 发现的问题（按严重度）

### 2-1【P0 · 未闭环】bot 实体冻结：只有诊断，没有修复
- **事实**：`segmentTicks=121 / entityTicksInSegment=0 / travelCallsInSegment=0`，输入 `forward=1.00`、
  `onGround=true`、`delta=0`、脚下空气/头顶空气/支撑石头 ⇒ **假人实体整段没被 tick**。
- **影响**：同一病因连带 `exec_floating` 超时、`exec_chain collected=0/9`、`restore FAILED remaining=1`、
  `transfer end_to_end` 间歇一格不动。
- **现状**：仅加了看门狗（只报不改）。**本轮未复现**（0 次）。
- **裁定**：**不许标记为已修**。下一步只有两条路：(a) 复现取现场（看门狗那几个字段）→ 定位"谁该 tick 它"；
  (b) 直接补一个"发现漏 tick 就兜底驱动一次"的修法 —— 但 **(b) 属于在病因未明时加特判，违反项目规矩**，
  故建议先 (a)。

### 2-2【P1 · 同型缺陷第二次出现】"字段置 null + 之后仍被 tick"只修了 1/20
- **事实**：脚本扫出 **19 处**（`ScaffoldLifecycleTask` 4、`MineTask` 3、`TransferTask`、`FluidMineCheckTask`、
  `ClearGuardCheckTask`、`PickupGateCheckTask`、`ToolSupplyCheckTask`、`TransferCheckTask`、
  `PathingRegressionTask`、`WalkToDiagnosticTask`、`WriteBudgetCheckTask`、`RegressionBatteryTask` …）
  形如"子任务字段被置 null，而 `.tick()` 调用点无空守卫"。
- **证据**：同型 NPE **09-06 已经崩过一次**（`MovementSequenceWalkTask.tick:54`，`movements is null`），
  09-13 再崩一次（`MineTask.tickRestore:402`）。
- **裁定**：**单独立项**（终态闩锁推广）。当前只有"电池 try/catch"兜住后果，**根因面仍在**。
  建议做法：统一契约（"任务终态后再 tick 幂等"）+ 要么抽基类、要么逐类加闩锁；并给每个类补一条幂等断言。

### 2-3【P1 · 违反项目日志规矩】探针噪声仍在生产代码里（8 个调用点）
- **量化**（本轮一轮电池，`[alice]` 共 **4118 行**）：`[MineTask探针]` **212**、`[MiningPlanner探针]` **93**
  （另有 `[MineTask重规划探针]`/`[MineTask升级决策探针]`/`[LOS探针/预检查]`）。
  `[MineTask探针] 挖掘状态` 实测**最高 6 行/秒**（有 `lastProbeStatus` 守卫，但状态会来回跳）。
- **冲突**：`alice-scene-based-testing` §6 明确"验证通过后**立即删除临时探针**，只保留终态日志"。
- **裁定**：**需清理**，但**门控**——冻结（§2-1）未闭环前，`segment_stall`/`entity_tick_missing`
  必须保留；`[MineTask探针]`/`[MiningPlanner探针]` 等与冻结无关，可在下一次提交里降级/删除。
  建议：保留"状态**首次**变化"一行（每用例至多几条），去掉反复跳变。

### 2-4【P2 · 已修】文档漂移 3 处
- `AI_DECISIONS.md` D-167 状态行仍写"未验证 / 电池 25 项"；`AI_PROJECT_STATE.md` D-167 行仍写"待客户端"。
  ✅ 本次审查已修（改为 `WINDOWS_CLIENT` + 电池 23 项）。
- 唯一未修：`AI_DECISIONS.md:5784` 那处 "25/250(10%)" 是**工具耐久数字**，不是电池项数，**不是漂移**（误报）。

### 2-5【P2 · 结构性遗留】`pathing/` 顶层包被删空后语义混乱
- 现状：`pathing/` 顶层只剩 `MovementHelper`、`FootCellRuleCheck`、`risk/RiskSwitches` + `core/**`。
  新内核在 `core`，但这两个**被内核大量使用**的类留在上一层的"legacy 位置"。
- 裁定：**单独立项**（小改）：把 `MovementHelper`/`FootCellRuleCheck` 迁到 `pathing/core/`
  （或至少补一行包级说明"本包只剩内核共享工具，legacy 内核已于 D-171/173 删除"）。
  迁包会动 300+ 引用，**不值得在本轮做**，登记即可。

### 2-6【P2 · 覆盖缺口】三条断言从未被真实触发
| 断言 | 缺口 | 风险 |
|---|---|---|
| D-168 `foreignDrops` 不计入 | 本轮世界无残留 ⇒ 该分支未跑过 | 中：断言可能写错而无人知；**补法**：跑电池前手动往 mine_course 丢 1 个物品 |
| D-169 `fixture_not_top_level` | 只在"被嵌进别人步骤"时触发，当前没有这种调用 | 低（属防御） |
| D-176 watchdog | 本轮未复现冻结 | 高（与 §2-1 同一件事） |

### 2-7【P3 · 诊断通道重叠】4 套诊断并存
`PhysicsProbe`（200 tick，需遥控器触发，**且遥控器会干扰输入** ⇒ 对本场景不可用）、
`entityTickCount` 计数、`segment_stall`（失败终态）、`entity_tick_missing`（看门狗）。
- 裁定：**冻结闭环后合并**——保留 `segment_stall`（失败现场）+ watchdog（首次告警），
  评估退役 `PhysicsProbe`（入口有副作用，容易误导）与冗余计数。

---

## 3. 补丁堆积风险总评

| 维度 | 数字/事实 | 判断 |
|---|---|---|
| 净代码量 | **+1979 / −3701 = 净删 1722 行** | ✅ 与"越修越臃肿"相反，方向是**减重** |
| 删除类改动 | 33 个 legacy 文件整批删除（按路径证明零活引用） | ✅ 有证据、可编译验证 |
| 新增能力 | K-3（安全点停止）、K-5（状态语义接线）、资源自检脚本 | ✅ 都有夹具/脚本断言 |
| 新增诊断 | `segment_stall`、`entity_tick_missing`、2 个计数、`K4` 计数 | ⚠ **诊断类在增长**，需按 §2-3/§2-7 清理 |
| 半修/欠账 | 冻结未闭环、19 处同类隐患、3 条断言未触发 | ⚠ 已全部登记，**没有假装完成** |

**结论**：本轮补丁**不是**"越堆越乱的补丁堆" —— 主体是**删除 + 夹具纠错 + 契约化**，
且每一项都有可复现证据；但有两处真实欠账必须盯着：**(1) 冻结只诊断未修；(2) 终态幂等只覆盖 1/20**。

---

## 4. 建议动作

**立刻（低风险）— ✅ 已完成（D-177）**
1. ✅ 探针降噪：`[MineTask探针] 挖掘状态` 改为 `(phase,status)` 首次出现才打（原最高 6 行/秒 → 典型 3~6 行/用例），
   标签去掉"探针"字样（D-177 ①）。
2. ✅ ⑦ **D-168 残留分支改为每轮真实执行**：夹具主动播种外来掉落物 + `foreignOk` 断言（D-177 ⑦）。
3. ✅ ⑤ **终态幂等集中执行点**：电池对每个终态步任务补 tick 两次并打 `idempotent=`（D-177 ⑤）。
4. ⏳ ② `pathing/` 包语义：先登记，不动代码（迁包牵 300+ 引用）。

**待冻结复现后**
3. 取 `entity_tick_missing` 现场 → 定"谁该 tick 假人" → 再选修法（§2-1）。
4. 合并/退役重叠诊断（§2-7）。

**单独立项**
5. 终态幂等推广到全部任务（§2-2）—— **⑤ 已把契约变成可观测断言**，剩余工作是按电池点名结果
   逐个给任务加闩锁（或抽基类）。
6. `MovementHelper`/`FootCellRuleCheck` 迁入 `core`（§2-5）。
7. 补三条未触发的断言（§2-6）：D-168 残留分支优先（可手工造残留，一条右键流程）。

---

## 5. 诚实边界（本审查**没有**证明的事）

- ❌ "冻结已修" —— 只有诊断；本轮未复现。
- ❌ "同类 NPE 面已消除" —— 只修了 `MineTask`；其余 19 处靠调用方惯例 + 电池 try/catch 兜后果。
- ❌ "D-168 残留容错有效" —— 分支未被真实触发（逻辑成立是用代码推的，不是实测的）。
- ❌ "看门狗字段足以定性冻结" —— 字段是按机理**推测**出来的（removed/区块/玩家表/连接），
  尚未在真实冻结时读到过；若下次现场显示这些量都"正常"，说明要走"谁在 tick 连接"这条更深的路。

---

# 附：基层（base layer）专项审查（用户要求，2026-09-13 晚）

> 用户判断："这些问题很多都来自项目的基层"。逐条**用代码与日志核实**，不靠印象。

## B-1 【已核实·生产安全】任务生命周期：终态即清任务

**事实（代码）**：`BotSession.tick()` 里 `Task.Status status = task.tick();`
→ `switch(status)`: `DONE/FAILED → complete(...)` → `recordTerminal(...)` → **`clearTask()`**（`task = null`）。
之后每 tick 开头 `if (task == null) return;` ⇒ **生产路径永远不会 tick 一个终态任务**。

**结论**：2026-09-13 的服务端崩溃**不是生产路径具备的性质**，而是**测试台（夹具/诊断任务）直接 tick 任务**时
破坏了契约 ⇒ 归类为"**测试台调用纪律**"缺陷，而不是"会话调度"缺陷。
**已采取的基层措施**：① 契约写进 `Task.tick()` javadoc（唯一声明处，含 09-06/09-13 两次 NPE 的来由）；
② 电池对每个终态步任务补 tick 两次并断言 `idempotent=`（集中执行点，不必逐个类改）。

## B-2 【已核实·隐患面】19 处"直接 tick 任务"的调用点

脚本扫描"Task/Runner/Step/Job 字段被置 null 且 `.tick()` 无 null 守卫" ⇒ 19 处。逐个归类后：

| 类别 | 数量 | 风险 | 处置 |
|---|---|---|---|
| 生产任务（会被会话 tick） | 2（`MineTask`、`TransferTask`） | **低**：会话终态即清任务（B-1）⇒ 不会被二次 tick | `MineTask` 已加终态闩锁；`TransferTask` 待观察 |
| 夹具/自检任务（被自身或电池直接 tick） | 17 | **中**：夹具写法一旦"终态后又 tick"就崩（09-13 实例） | 电池已 try/catch 隔离 + `idempotent=` 断言点名；逐个加闩锁列为单独立项 |

**结论**：隐患面**真实存在但已被两层兜住**（隔离 + 断言），不需要立刻改 17 个夹具；
但**契约推广**仍是欠账，且这是**同型缺陷第二次出现**（09-06 已崩过一次）。

## B-3 【已核实·未闭环】假人 tick 依赖实体 tick（与任务层不同源）

**事实（读数）**：冻结现场 `segmentTicks=121 / entityTicksInSegment=0 / travelCallsInSegment=0`，
`input=forward=1.00`、`onGround=true`、`delta=0`、三格方块正常。
**结构事实（代码）**：任务/会话跑在 **`ServerTickEvent.Phase.END`**（全局）；而 `BotPlayer.tick()`
是**实体 tick**（`BotPlayer.tick() → aiStep() → travel()`），受**区块 entity-ticking** 影响。
⇒ 两者**不同源**：会话可以一直跑，而实体一次都不 tick —— 与观测完全一致。
**首要假设**：机器人所在区块的 **entity-ticking 掉了**（或实体被移出 tick 列表）⇒ 物理冻结、无任何报错、之后自恢复。
**验证手段（已加）**：看门狗新增 `entityTicking=level.isPositionEntityTicking(pos)`
（连同 `removed / levelLoaded / inLevelPlayers / inPlayerList / connection / task`）。
**未闭环**：仍未复现；**不许**在拿到现场前加"补 tick"这类特判。

## B-4 【已核实·夹具纪律】"夹具自己的前提也必须断言"第三次应验

本轮唯一失败 = 我上一轮新加的 `foreignOk` 断言——**原因是播种点算错**（用 `start+Z1`，
而测量盒以 **target** 为中心 ±6；`exec_blocked` 的 target z=134 ⇒ 盒 z∈[128,140]，播种在 z=141 ⇒ 盒外）。
**已修**：播种点改为"测量盒内 + 空气 + 下方有支撑"的第一个候选，找不到就**如实降级**（不做该断言并告警）。

**正面结果（D-168 分支首次实测成立）**：`exec_direct`/`exec_floating`/`exec_chain` 三个用例
打出 `foreignOk=true(另有残留1件不计入)` ⇒ **"残留不计入判据"这条逻辑第一次被真实执行并通过**。

**教训（写进规矩）**：夹具**自己制造的现场**（播种位置、盒范围）也必须自检——
否则出现的是"断言写得对、前提摆错了"的假失败，比不写断言更浪费时间。

## B-5 本轮日志事实汇总（供对账）

```
电池： (22/23) ticks=3312 → FAIL（唯一失败步 = mine_regression，唯一失败用例 = exec_blocked）
mine_regression：free/wall/blocked/headroom/buried/exec_direct/exec_floating/exec_chain/no_tool_refuses/
                 scope_reopen_keeps_drops = PASS；exec_blocked=FAIL(foreignOk=false，播种点算错)
idempotent=false：0 次      entity_tick_missing：0 次      segment_stall：0 次
异常/崩溃：0 次              K4=OK(真异常 0 / 写入类例外 75)
```
