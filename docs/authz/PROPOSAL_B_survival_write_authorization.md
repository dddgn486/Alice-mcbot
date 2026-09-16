# 提案 B（改版 v2）：**路径规划器可以在什么条件下写世界** —— 合并「挖矿通道挖掘」与「维生自救」的同一次拍板

> **状态：提案（未批准、未实现）**。批准后才动代码与 `docs/authz/AUTHZ_REGISTRY.csv`；在那之前注册表保持如实。
> **改版原因**：`survey/09` §3 指出 —— 挖矿「通道挖掘」与溺水「自救」卡在**同一对**前置上
> （① 放宽 D-076 写授权 ② 内核垂直移动），而它们在文档里是**两份独立的待拍板**；
> 分开拍很可能得出**互相矛盾**的两个答案。故本提案合并两者，只问**一个问题**。
> 本文件只放**指针与结论**；勘测原文见 `survey/09-逃生与液体与粗目标复核-20260915.md`。

## 一句话
**别再问"哪个危险/哪种地形可以写世界"，要问"这个任务的信封里有没有写世界的权利"** —— 有 ⇒ 逃生的写权是
**信封的后果**（事前留一份逃生准备金），不是被困住时临时授予；没有 ⇒ 如实失败（`exit=none`）等干预。

## 事实（**均已亲验**，带 file:line）
1. **默认通行无写权限**：`PathRequest.of` 只给 `TRAVERSE/DIAGONAL/ASCEND/DESCEND`（`pathing/core/search/PathRequest.java:35-40`），逃生任务用的正是它（`task/WalkToTask.java:53`）。
2. **红线是"可执行"的**：`WritePolicyMatrix` 的 `P-01` 行明写 —— *"纯通行任务**刻意显式 TEMP**…移动集**刻意不含** `withWorldModification`（D-076 红线的可执行版本）"*。
3. **正确的轴已经是一个字段**：`MovementCapabilities.changesWorld()`（`:8`），且正是 `CapabilityGate.check:62` 的第一道分派（**不改世界 ⇒ 直接放行**，不查授权/预算/保护）。
4. **预算与账本全现成**：`WriteBudget.DEFAULT_MAX_BREAKS = 64`（"用户 2026-09-11 裁定接受 64 起点；按观测再收紧"）+ `Caps(maxBreaks, maxPlaces, maxContainerWrites)`；`WriteAudit`；`WorldModLedger`（TEMP 必拆 / KEEP 不拆）；登记表 `docs/authz/AUTHZ_REGISTRY.csv`（闸门 `tools/check-authz-registry.sh`）。
5. **搭桥/垫柱子/挖穿的机制全现成**：`SurfaceMovementProvider.appendPlaceStepAndTraverse:393-425`（往水里放方块 ✓ 目的地走 `canWalkThrough` ⇒ 岩浆/火自动被拒）、`:276-284`（`appendPillar`）；写边在**规划期**按 `writesAllowed` 剪枝。
6. **真正的杀手（§4.4，亲验）**：`REFUGE_RADIUS=8` + 出口必须**可规划**（D-238）+ `PathRequest.of` **不含 `PILLAR`** ⇒ **自挖竖井里 bot 无解**（爬不上、挖不动、退路被切）。
7. **逃生路径不评估危险（§4.3，亲验）**：`PlanRouteSafety.lavaContacts` 全仓只有 **2 个消费者**（`item/PathingLavaGuardItem:86`、`task/PathingRegressionTask:386`，都是诊断/夹具）⇒ 逃生规划的是"最短纯通行路线"，可能正好穿岩浆/火焰。
8. ⚠️ **勘测里一条引用已过期**：`scaffoldRemoval` 的"已在 `WORLD_WRITE_AUTHORIZATION.md` 登记"——**该文件名今天不在仓库里**；
   现行单一出处是 `docs/authz/AUTHZ_REGISTRY.csv` + 自动生成的 `docs/authz/OVERVIEW.md`。引它要按现行名字引。

## 提案（合并后一次拍板；五处 + 一条用法纪律）
1. **轴 = 任务信封**：只有信封允许改世界的任务（`changesWorld()==true`）才有逃生写权。**不按"野外/基地"、不按危险种类分**。
2. **机制 = 逃生准备金**：把 `WriteBudget` 的每一次授权留一小份（例如 **破坏 8 / 放置 8**）**只能用于逃生**；
   专属 `WriteReason`（如 `ESCAPE_EXCAVATION` / `ESCAPE_PLACEMENT`）⇒ 台账/日志能分清"为干活挖的"与"为逃命挖的"。
3. **登记**：新 `MovementGrant`（指向新工厂 `PathRequest.survivalEscape(...)`，移动集 = 纯通行 + `PLACE_STEP_AND_TRAVERSE`
   + `PILLAR` +（**待你定**）`BREAK_AND_TRAVERSE`/`DOWNWARD`）+ `WritePolicyMatrix` 新行 + `AUTHZ_REGISTRY.csv` + 跑闸门。
4. **用法纪律（沿用我的阶梯，写权不滥用）**：先纯通行（今天行为）⇒ 只有计划确实 `UNREACHABLE`（**不是** `SEARCH_LIMIT`）
   才动用准备金；D-238 的出口预检同步用同一阶梯 ⇒ "值不值得起逃生"与"用什么授权"永远一致。
5. **上限与失败**：每次危险事件最多升档 1 次；准备金用尽 ⇒ **如实失败**（`exit=none` + 大声登记），
   不假装能过（例如要爬 60 格竖井 ⇒ 那是还没有的**垂直移动能力**，不是预算问题）。
6. **回收**：按登记表 `Obligation` 走（放置建议 TEMP ⇒ 复用既有 `scaffoldRemoval`；挖开的地形按 KEEP 登记）。

## 判据（可红；挂既有 BASELINE 步 `survival_exit`，不新增电池步）
- **水渠**（3 格宽，对岸 8 格内有干落点；孤立长方体区域）：纯通行 `UNREACHABLE` ⇒ 升档后到达**且含 ≥1 条
  `PLACE_STEP_AND_TRAVERSE`** ⇒ 真走过去、脚落对岸 ⇒ 账本按 `Obligation` 登记。
- **竖井**（§4.4 那个杀手）：bot 自挖竖井里灌水 ⇒ 升档后能**垫柱子/挖侧壁**出来；准备金不足时如实 `exit=none`。
- **上限守卫**：水渠宽 > 准备金 ⇒ 仍 `UNREACHABLE`（**不许把每条河都变成免费桥**）。
- **反向对照**：撤掉升档 ⇒ 上述判据精确变红。

## 边界（诚实记）
- **不解决"深水浮着"**：起点需合法脚位 ⇒ 那一档仍归 **乙**（踩水）+ **C1 垂直/水位能力**。
- **不替代 C1**：C1 = 内核垂直能力（最贵，`survey/08` §10 明确"需要真实存档地形才能排"）；B = 在**信封内**用写世界换路。
- **`survey/09` 的更便宜前置 B1「原路返回」**：全仓**无轨迹记忆** ⇒ 环形缓冲 + 逃生候选格来源从"8 格内最近"改成"先看轨迹"
  （不碰 D-076、不需新 Movement，且正好能防住 D-238 抓到的那个真实案例：`refuge=240,105,306` 几何成立但规划不可达）
  ⇒ **我的建议是先做 B1，再做本提案**（B1 让问题不发生）。
- 顺带（**不在本提案内**，各一行）：`B3 逃生路径的岩浆检查`（§4.3，机制现成缺消费者）。

## 需要你拍板的 5 个问题
| # | 问题 | 我的建议 |
|---|---|---|
| Q1 | 轴：**按任务信封**（`changesWorld()`）发逃生写权，还是按危险种类/地形？ | 按任务信封（勘测 §7.2："逃生的权利应当是有权冒险的后果"） |
| Q2 | 动作集：只放置（搭桥/垫柱子）/ 也允许破坏（挖侧壁出竖井）？ | **放置 + 破坏 + PILLAR**（竖井那档必须能挖，否则 Q1 白给） |
| Q3 | 准备金：破坏 8 / 放置 8（从 64 起点的授权里预留）？每次危险事件最多升档 1 次？ | 按建议（不够用就如实失败） |
| Q4 | 回收：放置 TEMP（必拆，复用 `scaffoldRemoval`）/ 挖开的地形按 KEEP 登记？ | 放置 TEMP + 破坏 KEEP 登记（挖掉的地形无法"回收"） |
| Q5 | 先做哪件：`B1 原路返回`（最便宜、不碰红线）还是直接做本提案？ | **先 B1，再做本提案**；两者判据互相独立，可分别验收 |

**未决即不动手**：Q1–Q5 任一未定，本提案保持"仅文档"，代码与注册表都不碰。
