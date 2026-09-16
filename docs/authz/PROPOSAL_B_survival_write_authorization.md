# 提案 B：维生自救的**受限写授权**（搭桥 / 垫柱子出水）—— 待用户裁定

> **状态：提案（未批准、未实现）**。批准后才动 `docs/authz/AUTHZ_REGISTRY.csv` 与代码 —— 在那之前注册表**保持如实**。
> 触发来源：台账 §5.11 ③（用户 2026-09-15 问"bot 被水流围住怎么办" ⇒ 逼出这条：Alice 过水的替代手段**全是写手段**，而写手段全被 D-076 授权卡住）。

## 一句话
bot 被水困住时，今天的逃生任务只拿"纯通行"授权 ⇒ **走不到**。本提案给它一张**只允许放置**的受限凭证，让它能搭桥/垫柱子自救 —— 代价是给 D-076 红线开**一个受控口子**（仅维生、仅放置、有上限、有账本）。

## 事实（现状，均可 grep）
1. **默认通行无写权限**：`PathRequest.of` 只给 `TRAVERSE/DIAGONAL/ASCEND/DESCEND`（`pathing/core/search/PathRequest.java:35-40`）；逃生任务用的正是它（`task/WalkToTask.java:53`）。
2. **红线是"可执行"的**：`action/WritePolicyMatrix.java` 的 `P-01` 行明写 —— *"纯通行任务**刻意显式 TEMP**：走到某处不该留下永久痕迹；移动集**刻意不含** `withWorldModification`（D-076 红线的可执行版本）"*。
3. **搭桥/垫柱子的机制全部现成，而且没有流体排除**：
   - `pathing/core/search/SurfaceMovementProvider.java:393-425`（`appendPlaceStepAndTraverse`：目的地走 `canWalkThrough` ⇒ **水可以放**（水里那格放行）、岩浆/火被拒）；
   - 同文件 `:276-284`（`appendPillar`：脚下是水+有放置面 ⇒ 能一路垫上去）；
   - 写边在**规划期**就按预算剪枝（`context.writesAllowed(...)`）⇒ 不会规划出"执行到一半必然被拒"的路径。
4. **授权机器全部现成**：`WriteGrant(requester, reason)`（`action/WriteGrant.java:23`）+ `WriteBudget`（D-106 作用域上限）+ `WriteAudit` + `WorldModLedger`（TEMP 必须配对拆除 / KEEP 不该拆）+ 登记表 `docs/authz/AUTHZ_REGISTRY.csv` + 闸门 `tools/check-authz-registry.sh`。
5. **任务类别由 requester 推导**（`WritePolicyMatrix.taskOf:401` + `PREFIX_RULES`），**不由调用点自报** ⇒ 新 requester 必须登记，否则记 `UNREGISTERED`（计数 + WARN）。

## 提案（最小件，五处）
1. **`PathRequest.survivalEscape(...)`**：移动集 = 纯通行 + **`PLACE_STEP_AND_TRAVERSE` + `PILLAR`**；**不含**任何 `BREAK_*` / `DOWNWARD`（只放不挖）。
2. **`WritePolicyMatrix`**：新增 `MovementGrant.SURVIVAL_ESCAPE`（指向上面那个工厂；词表即工厂名，不新造词）+ 新行
   `P-xx`：`Zone.EXTERNAL × Task.SURVIVAL`，授权 `{OF, PURE_TRAVERSAL, SURVIVAL_ESCAPE}`，
   理由复用 `{STEP_PLACEMENT, SUPPORT_PLACEMENT}`（**不加新理由词**）；`PREFIX_RULES` 登记 `survival-escape ⇒ Task.SURVIVAL`。
3. **逃生用阶梯**（不是默认升档）：先纯通行（今天行为）⇒ 只有计划确实 `UNREACHABLE`（**不是** `SEARCH_LIMIT`）才升档；
   D-238 的出口预检同步改成同一阶梯 ⇒ "值不值得起逃生"与"用什么授权"永远一致。
4. **预算**：逃生作用域 `WriteBudget` 放置上限 N（建议 **8**）+ 每次危险事件最多升档 **1** 次 +
   背包没有可用方块 ⇒ 如实登记 `no_blocks` 并回落纯通行（不假装能过）。
5. **回收**：按登记表的 `Obligation` 走。**建议 TEMP**（过桥后沿桥回收，复用既有 `scaffoldRemoval`）；回收不到 ⇒ 进账本"待收尾" + 大声登记。

## 判据（可红 · 挂既有 BASELINE 步 `survival_exit`，不新增电池步）
- **场景**（按项目硬纪律：孤立长方体区域，边界外一圈含上下为空气、不与其它场景相连）：3 格宽水渠，对岸 8 格内有干落点。断言：纯通行 `UNREACHABLE` ✓；升档后计划**到达**且**含 ≥1 条 `PLACE_STEP_AND_TRAVERSE`** ✓；
  真的走过去、脚落在对岸 ✓；账本按 `Obligation` 登记（TEMP ⇒ 事后拆除）✓。
- **上限守卫**：水渠宽 > 预算 ⇒ 仍 `UNREACHABLE`（**不许把每条河都变成免费桥**）✓。
- **反向对照**：撤掉升档 ⇒ 上述判据精确变红 ✓。

## 边界（诚实记，避免被当成万能药）
- **不解决"深水浮着"**：起点必须有合法脚位；浮在水面（脚下是水）时规划器起不来 ⇒ 那一档仍归 **乙**（踩水保命）+ **丙**（内核水位移动）。
- **不替代丙**：丙 = 不用写世界的游泳；B = 用写世界换一条命。两者互补，不是二选一。
- 放宽的只是**一张凭证的作用域**：仅 `Task.SURVIVAL`、仅放置、有上限、有账本、有反向对照；其它任务**一字不变**（`P-01` 的纯通行红线原样保留）。

## 需要你拍板的 5 个问题
| # | 问题 | 我的建议 |
|---|---|---|
| Q1 | 授权域：只给**维生逃生**，还是也给普通通行？ | 只给维生逃生（普通通行维持 `P-01` 红线） |
| Q2 | 动作集：**只放置**（搭桥/垫柱子），还是也允许破坏（挖穿挡路的墙）？ | 只放置（破坏是另一档风险，且掉落/工具磨损另算） |
| Q3 | 回收义务：**TEMP**（必须拆）/ KEEP（留痕迹但登记）/ 逐次判定？ | TEMP（过桥后沿桥回收；拆不掉则进账本待收尾） |
| Q4 | 触发：只在纯通行 `UNREACHABLE` 时升档，还是总是允许？ | 阶梯（默认无写世界；写只是兜底） |
| Q5 | 上限：一次逃生 ≤ **8** 个方块 + 每次危险事件最多升档一次 + 无方块如实登记？ | 按建议 |

**未决即不动手**：这 5 个问题任一未定，本提案保持"仅文档"，代码与注册表都不碰。
