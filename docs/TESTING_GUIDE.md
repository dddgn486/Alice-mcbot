# Alice 测试流程指南（一键优先）

> 目的：真人测试尽量**一次动作拿到全部信息**。禁止要求输入坐标或长参数。
> 更新时间：2026-09-08

---

## 0. 一次性准备

1. 启动固定客户端：`D:\JAVA_projects\worldedit-test\versions\1.20.1-Forge_47.4.10`（确认 mod 为最新工件）
2. 生成 bot（若当前维度没有）：`/alice spawn`
3. 领取测试物品：
   ```
   /give @s alice:pathing_battery
   /give @s alice:pathing_planner
   ```

**场景由数据包一键生成**（无需自己搭地形）：
```
/function alice_test:pathing_course
```
它会自动生成：上升台阶（东侧 1 格高）、下降坑（北侧 1 格深）、2 段下降链，
把玩家放到起点并发放寻路自检电池。
数据包源文件在仓库 `tools/test-scenes/alice_test/`（同时已部署到测试世界）。

**你不需要自己整理日志**：说「测完了」，AI 会主动读 `latest.log` / `debug.log` 并按前缀分析。

---

## 1. 通用原则

- **一键优先**：能用物品右键就不打命令；能用单词命令就不打坐标。
- **一次只测一项**，等聊天出现终态消息再进下一项。
- **需要"看"的结论必须由真人确认**：是否跳跃、是否回冲、是否卡边缘、是否抖动、有无紫黑贴图。
  日志只能证明"位置对了"，证明不了观感——这类问题 AI 会主动问你。
- **失败也要测**：把 bot 指向不可达处，确认它诚实失败。

---

## 1.5 测试夹具设计原则（新功能照此办理）

后续每加一个功能，测试夹具都按这套模式做，避免"测试太繁琐"重演：

| 原则 | 做法 | 本仓库参考 |
|---|---|---|
| 一键场景 | 需要特定地形就用数据包函数生成，不让用户手搭 | `/function alice_test:pathing_course`、`tools/test-scenes/` |
| 场景孤立长方体 | 定义长方体区域，边界外至少一圈（含上下）为**空气**，不得与其他场景/地形相连；**不要求封闭，开阔场景即可**；必要时才加围墙/底部隔离地板 | `pathing_course_reset` 先整体清空再建造 |
| 一键测试 | 多个检查项合并为一个自检任务，一次右键跑完 | `alice:pathing_battery` → `PathingBatteryTask` |
| 机器可判读 | 末尾输出 `SUMMARY key=VALUE ...`，失败码明确 | `[R3 Battery] SUMMARY ...` |
| 各项独立 | 每个子项开始前 bot 复位到统一起点（`reset_to_hub`） | `PathingBatteryTask.resetToHub()` |
| 缺地形不算失败 | 不支持项记 `SKIP` | 同上 |
| 候选同源 | 夹具用规划器的 `MovementProvider` 检测可测项 | `SurfaceMovementProvider` |
| 场景入库 | 数据包与夹具源码进仓库，不依赖客户端临时文件 | `tools/test-scenes/alice_test/` |
| 视觉结论真人确认 | 是否跳跃/回冲/卡边缘必须问用户 | §1 通用原则 |

---

## 2. ★ 一键自检（推荐，两次操作覆盖全部）

**第 1 步（建场景，一条命令）**：
```
/function alice_test:pathing_course
```
自动生成标准课程（上升台阶 / 下降坑 / 2 段下降链），把玩家放到起点，并发放电池。

**第 2 步（跑测试，一次右键）**：手持 `alice:pathing_battery`，右键任意方块 → 等约 8 秒。

> **场景专属测试器**：只负责启动测试，**起点由场景固定**（`(0,64,46)`，与 `pathing_course` 一致），
> 与你站在哪里无关。已有 bot 会被传送到固定起点；没有 bot 会自动生成在固定起点。
> 每个子项开始前也会锚回固定起点（日志 `anchor_to_start`），保证各项独立可测。

**它自动跑完**（每项开始前把 bot 放回统一起点，保证各项独立可测；地形不支持的项记 `SKIP`）：

| 项目 | 内容 |
|---|---|
| `plan_flat` | 规划平地路径 |
| `plan_up` | 规划上升路径 |
| `plan_budget` | 小预算远距离规划（验证 `SEARCH_LIMIT` 语义） |
| `traverse` | 执行水平移动 |
| `diagonal` | 执行对角移动 |
| `ascend` | 执行上升（**应看到跳跃**） |
| `descend` | 执行下降 |
| `chain2` | 执行 2 段连续下降（验证 D-027 无回冲） |

**聊天/日志**：
```
[R3 Battery] plan_flat=REACHED(2) plan_up=REACHED(1) plan_budget=SEARCH_LIMIT
[R3 Battery] item=traverse seg=0 result=PASS actualFoot=...
[R3 Battery] SUMMARY plan_flat=REACHED(2) plan_up=REACHED(1) plan_budget=SEARCH_LIMIT
             traverse=PASS diagonal=PASS ascend=PASS descend=PASS chain2=PASS
```

**判定**：
- 出现项应为 `PASS`；`SKIP` = 该地形不支持，不算失败
- `plan_*` 状态语义正确：可达=`REACHED`，小预算=`SEARCH_LIMIT`（**不是** `UNREACHABLE`）
- **亲眼确认**：ascend 是否跳跃、chain2 是否还有明显回冲、有无卡边缘

---

## 3. ★ R4 路径会话（规划 → 逐段执行）

**物品**：`alice:pathing_session`（路径会话测试器，场景专属）

**操作**：场景函数已把它发到背包；右键任意方块即可。

**它做什么**：把 bot 锚定到固定起点 `(0,64,46)` → 规划到固定目标 `(0,62,44)`（2 段下降链底部）→ 由 `PathSession` 逐段执行。

**预期日志**：
```
[R4 Session] planned session=... status=REACHED movements=2 cost=2.00 ... from=0,64,46 to=0,62,44
[R4 Session] segment_start index=0/2 type=DESCEND ... tolerance=COLUMN
[R4 Session] segment_done  index=0 type=DESCEND actualFoot=0,63,45
[R4 Session] segment_start index=1/2 type=DESCEND ... tolerance=EXACT
[R4 Session] segment_done  index=1 type=DESCEND actualFoot=0,62,44
[R4 Session] completed session=... segments=2 ticks=... finalFoot=0,62,44
[R4 Session] result session=... status=COMPLETED segments=2/2 ticks=... finalFoot=0,62,44
```

**判定**：`status=COMPLETED segments=2/2`；两段之间不中断、不报 `STALE`/`BLOCKED`。

**失败语义**（R4 新增，供上层任务/LLM 决策）：
`STALE`（起点漂移/世界变化）、`BLOCKED`（目标被阻塞）、`TIMEOUT`（单段超时）、
`INVALID_PRECONDITION`、`MOVEMENT_FAILED`、`CANCELLED`。

---

## 4. 细查入口（一键自检发现问题时再用）

### 4.1 只规划（bot 不动）
`alice:pathing_planner`：
- **右键方块** → 规划到该方块正上方
- **Shift+右键方块** → 规划到该方块本身

聊天给出状态/步数/成本/节点/用时 + 逐步明细。或零参数命令：
```
/alice pathing plan-here      → 规划到你自己脚下
```

### 4.2 单步 Movement（单词方向，无坐标）
```
/alice pathing traverse east
/alice pathing diagonal northeast
/alice pathing ascend east
/alice pathing descend east
```
地形要求：目标必须满足该 Movement 的前置（同高相邻 / 对角相邻 / 高 1 格且头顶留 2 格 / 低 1 格）。

### 4.3 多段链
```
/alice pathing chain east 3
```
地形：从 bot 站立处向东做 3 级标准楼梯（每级下降 1 格、宽 1 格，上方留 2 格）。

---

## 4.5 ★ R5-2 破坏通行（BreakAndTraverse）

**场景**：`/function alice_test:break_course`
生成：固定起点 `(0,64,66)` → 目标 `(7,64,66)`，中间 `x=3` 有 **2 格高石墙**。

**操作**：手持 `alice:pathing_breaker`（函数已发放）右键任意方块。

**预期日志**：
```
[R4 Session] planned ... status=REACHED movements=... （路径含 BREAK_AND_TRAVERSE）
[R4 Session] segment_start ... type=BREAK_AND_TRAVERSE
[BreakAndTraverse] blocked_cleared pos=3,64,66 index=0
[BreakAndTraverse] blocked_cleared pos=3,65,66 index=1
[R4 Session] result ... status=COMPLETED
```

**判定**：
- 规划确实使用 `BREAK_AND_TRAVERSE`（而不是绕路/失败）
- bot **逐步破坏**两个方块（能看到破坏进度/摆动，不是瞬间消失）
- 破坏后走过墙，最终脚位 `(7,64,66)`
- 不允许出现 `BREAK_BLOCK_PROTECTED` / `BREAK_BLOCK_UNBREAKABLE`

**同时回归**（R5-1 原语改造影响面）：跑一次既有挖掘场景 `alice:mining_scene_tester`，
确认 legacy 清障路径（`BreakAndWalkMovement` → 进度破坏）没有回归。

---

## 4.6 ★ R5-3 放置台阶通行（PlaceStepAndTraverse）

**场景**：`/function alice_test:place_course`
生成：起点 `(0,64,66)` → **2 格宽缺口（x=2..3）** → 平台 → **低 2 格的目标 `(7,62,66)`**。

**操作**：手持 `alice:pathing_placer`（函数已发放）右键任意方块。

**预期日志**：
```
[R4 Session] planned ... status=REACHED movements=...
[R4 Session] segment_start ... type=PLACE_STEP_AND_TRAVERSE
[PlaceStepAndTraverse] placed pos=2,63,66 ...
[PlaceStepAndTraverse] placed pos=3,63,66 ...
[PlaceStepAndTraverse] placed pos=7,62,66 ...
[R4 Session] result ... status=COMPLETED finalFoot=7,62,66
```

**判定**：
- bot 在缺口处**放置圆石**（能看到方块出现，不是踩空/穿过去）
- 落差处**先放置台阶再下**（2 格落差被拆成 1 格）
- 最终脚位 `(7,62,66)`，`status=COMPLETED`
- 不允许出现 `PLACE_RESOURCE_UNAVAILABLE` / `PLACE_NO_VALID_FACE`

---

## 5. ★ legacy 路径上升兼容（D-030）

**场景**：一条命令建好
```
/function alice_test:legacy_ascend
```
生成：低位平台（你脚下）→ 东侧 **1 格台阶** → 再东 **2 格高墙**。

**操作**：
1. `/alice follow on`
2. 向东走上 1 格台阶，观察 bot 是否跟随并**跳跃上台阶**
3. 继续走向 2 格高墙，观察 bot **不应爬上去**

**预期**：
- 1 格台阶：bot 跟随成功（`FollowTask` 正常推进，无卡住/超时）
- 2 格高墙：bot 停住、不上墙（诚实失败或原地等待），**不允许穿墙/爬墙**

**日志**：`follow_unsettled` / `follow_no_path` 等 legacy 失败码不应在 1 格台阶场景出现。

---

## 6. 日志前缀

| 前缀 | 含义 |
|---|---|
| `[R3 Battery]` | 一键自检：规划检查、逐项执行、SUMMARY |
| `[R3 Plan]` | 规划器输出（状态、步数、成本、节点、用时、逐步明细） |
| `[R2-C Traverse/Diagonal/Ascend/Descend]` | 单步 Movement 终态 |
| `[R2-C Chain]` | 多段链逐段进度与终态 |
| `[Descend-PROBE]` | Descend 临时逐 tick 探针（验收后删除） |

---

## 7. 失败码速查

| 失败码 | 含义 |
|---|---|
| `*_STALE_START` | 起点与计划不符（合法位置集问题） |
| `*_INVALID_PRECONDITION` | 目标不可站/不可穿/无支撑 |
| `DESCEND_REJECTED_OVERSHOOT_CLIFF` | 过冲列过深（>2 格）或不可行走 |
| `DESCEND_REJECTED_LANDING_HAZARD` | 过冲列有熔岩/火/岩浆块 |
| `DESCEND_OVERSHOT_BELOW_TARGET` | 真的落得比目标更低 |
| `*_SETTLING_TIMEOUT` | 到位后未在容差内稳定 |
| `SEARCH_LIMIT` | 预算耗尽（**不是**不可达） |
| `UNREACHABLE` | 搜索空间穷尽，确实不可达 |

---

## 8. 反馈模板（AI 会替你填）

```
测试项：一键自检（alice:pathing_battery）
操作：站在楼梯顶端平地，右键方块
聊天结果：<SUMMARY 行>
亲眼观察：ascend 是否跳跃 / chain2 是否回冲 / 有无卡边缘
复现：必现 / 偶发
```
