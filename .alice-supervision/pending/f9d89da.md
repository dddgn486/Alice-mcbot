# Alice work-session review packet

- Commit: `f9d89da7e6540099312fa8438a8299be1c9d3b21`
- Subject: docs: record blocked client checkpoint
- Authored: 2026-08-21T03:37:37+08:00
- Handover updated in this commit: `true`
- Active plan ID: `20260821-interface-readonly-snapshot-v1`
- Active plan status at handoff: `NEEDS_USER_DECISION`
- Push status: verify separately after `git push origin master`

## Changed files

```text
docs/HANDOVER.md
```

## Commit summary

```text
 docs/HANDOVER.md | 14 ++++++++------
 1 file changed, 8 insertions(+), 6 deletions(-)
```

## Handover delta

```diff
diff --git a/docs/HANDOVER.md b/docs/HANDOVER.md
index 2799a3d..18d1346 100644
--- a/docs/HANDOVER.md
+++ b/docs/HANDOVER.md
@@ -1,19 +1,19 @@
 # Alice 项目交接说明（给新会话的快速上手）
 
 > 用途：另一个对话/会话接手本项目时，先读本文件 + README + 设计文档，即可快速进入工作。
-> 更新：2026-08-21（当前功能 HEAD `ae32c4a`；总体产品路线已补充，文档改动尚未提交）
+> 更新：2026-08-21（最新实现提交 `8bb2d7b`；`interface-readonly-snapshot-v1` 未完成，旧存档客户端加载卡死后暂停待命）
 > 重要：本会话功能提交已同步到 Windows 端仓库（`origin` = `/mnt/d/JAVA_projects/alice`）。**每次代码修改完成后必须 `git push origin master`**，用户在 Windows 客户端实测。
 > 当前工作区的未提交改动均为监督流程：`.gitignore`、`AGENTS.md`、`docs/SUPERVISION_PROTOCOL.md`、`docs/supervision/`、`tools/` 与本文件；它们不属于 Mod 功能实现，应作为独立治理提交核对，勿覆盖或混入功能提交。
 
 ## 〇、当前 Git 状态（重要）
 
 ```
-ae32c4a feat: record task execution outcomes               ← HEAD（任务终态审计与只读状态）
+8bb2d7b feat: add readonly interface snapshots             ← 最新实现（C1 快照与独立扫描器；未完成客户端验收）
+d1def08 docs: define product architecture roadmap          （三产品主线、C0-C5 与 R37-R40）
+ae32c4a feat: record task execution outcomes               （任务终态审计与只读状态）
 d11e851 fix: settle soft path descents                    （下降诊断已实现；客户端失败后保守禁用）
 511a1e2 fix: align surface paths with collision sweeps     （直线路径与对角碰撞一致性）
 b423af3 fix: prove surface path costs                     （零 heuristic / Dijkstra 成本正确性）
-1f95687 feat: probe native travel height segments         （独立软路径高差探针）
-26819bd fix: decouple follow from safe zones              （跟随与保护区模块解耦）
 ```
 
 当前工作区不是干净状态：治理对接文件待独立提交。下一次功能工作开始前，监督员应先创建 `.alice-supervision/active-plan.md` 并标记 `APPROVED_FOR_IMPLEMENTATION`；主工作会话必须运行 `./tools/work-session-start.sh`，没有批准工作包不得扩展功能。只提交已核对归属的文件，勿覆盖用户或监督流程变更。
@@ -43,9 +43,11 @@ GitHub：`github` 远端 `dddgn486/Alice-mcbot`（本会话未推 GitHub，push
 
 `alice:interface_scanner` 的独立物品模型/名称、右键扫描行为，以及普通 `minecraft:diamond_shovel` 不再触发 Alice 扫描仍待 Windows 客户端确认；该入口不证明 C2/C3/C4 操作兼容性，也不替代既有 SOFT_SURFACE 客户端物理证据。
 
+**当前阻塞（用户 Windows 客户端证据，2026-08-21 03:29）**：加载旧存档时客户端卡死，日志最后可见阶段为 `假人已生成(玩家化): name=tango pos=17, -59, 18`、`已从世界存档恢复假人: name=tango pos=(17, -59, 18)`、`SELFTEST 待命(手动 /alice selftest 触发)`。四项扫描器客户端矩阵尚未开始，不能把本包标为完成或 `USER_ACCEPTED`。这些末行只证明卡死发生在恢复假人并进入 selftest 待命之后，尚无异常栈、线程转储或新世界对照，**不能据此归因到 C1 snapshot、独立物品注册或假人恢复中的任一模块**。按用户要求停止实现与重复启动，保留 `8bb2d7b`，状态转为待命；恢复工作时先建立旧/新世界对照并采集客户端/服务端线程证据，再决定窄修复范围。
+
 不能回退的决策：普通挖矿、拾取仍固定 `HARD_PATH`；`MineTask` 不生成道路或隧道，深层目标失败 `target_requires_tunnel`；A* 不破坏方块；本地清障仍仅限直接可见、4.5 格内、最多 2 个方块。`SOFT_SURFACE` 不得接入 `MineTask`、`DropCollectionTask`、道路或隧道，除非逐项客户端验证和单独决策批准。
 
-下一步最小安全验证：Windows 客户端先用 `alice:soft_path_probe_selector` 点击支撑方块，验证最短路线含高差、点击入口的单格上/下阶、头顶净空受阻和 bot 恢复；精确坐标 `/alice soft-path-probe <脚位>` 仅作诊断回退。记录入口 `support/foot`、路径 `cost/path`、每段 `action/from/to/foot/onGround`、失败码和最终可回收位置；用户须明确给出 `USER_ACCEPTED`、`NEEDS_FIX`、`NEEDS_REPLAN` 或 `NEEDS_DISCUSSION`。FollowTask 保持平地限制，且在该门槛前不得扩展到攻击、巡逻、流体或普通挖矿。
+下一步状态：**待命**。未经用户恢复指令，不继续实现、不重复加载旧存档、不开展扫描器或软移动客户端矩阵。恢复后第一工作包应先诊断旧存档加载卡死：对比同版本新世界与旧世界、保留完整 `latest.log`/`debug.log`、采集卡死时客户端和内置服务端线程转储，并确认最后推进的生命周期事件；在证据能区分 registry remap、假人 SavedData 恢复、连接注册、客户端同步或其他模组初始化前，不修改业务代码。`interface-readonly-snapshot-v1` 保持未完成，C2+、AttackTask、库存转移、LLM 和所有 SOFT_SURFACE 扩展继续禁止。
 
 
 ---
@@ -235,7 +237,7 @@ BUILD_UNIT → WAIT_STABLE → MOVE_TO_NEXT_UNIT →（循环）→ MINE_TARGET
 目标指定器(钻石斧)右键方块   挖掘；Shift+右键 → 独立放置任务（PlaceTask）
 软移动选择器(金斧)右键平地   NATIVE_TRAVEL；Shift+右键平地 → SELF_MOVE 回归对照；需已有空闲 bot，受 8 格/同高度限制
 道路蓝图锄(钻石锄)右键两端   生成蓝色道路预览；Shift+右键重置
-钻石铲右键                   快捷接口扫描
+接口扫描器(`alice:interface_scanner`)右键  C1 快捷扫描（客户端入口尚未验收；原版钻石铲不得触发）
 ```
 
 headless 验收：`./gradlew runServer -Dalice.selftest.auto=true`（测完自动关服，看 run/logs/latest.log）。
```

## Active Plan Snapshot

```markdown
# Alice Active Work Plan

- Plan ID: `20260821-interface-readonly-snapshot-v1`
- Status: NEEDS_USER_DECISION
- Implemented commit: `8bb2d7b1641c0b6e6063d4c78ae0357896fd0c90`
- Documentation checkpoint: pending commit at time of this state update
- Scope owner: `Alice project supervisor`

## Current State

The implementation package is not complete and is not user accepted.

- Supervisor verdict for `8bb2d7b`: `CONDITIONAL_PASS / HEADLESS_PARTIALLY_VERIFIED`.
- Client result: `NEEDS_FIX`, but scanner scenarios were not reached.
- User reports that loading an old save hung after the log showed fake-player spawn/recovery for `tango` and `SELFTEST 待命`.
- Available evidence does not identify the cause. It cannot yet be attributed to C1 snapshot capture, `alice:interface_scanner`, item registry remap, fake-player restoration, client synchronization, or another mod.
- Per user instruction, implementation and repeated testing are paused. The project is in standby.

## Preserved Progress

- Retain `8bb2d7b`: immutable C1 snapshot structure, formatter split, independent scanner item and vanilla-shovel hook removal.
- Retain review conditions in `.alice-supervision/reviews/20260821-8bb2d7b.md`.
- Scanner client scenarios in `.alice-supervision/client-tests/8bb2d7b.md` remain unexecuted.
- No C2/C3/C4, transfer, endpoint semantics, machine writes, LLM tools or task/movement expansion is authorized.

## Blocked Evidence Gap

Before choosing a fix, a resumed session needs:

1. Same-build comparison between a new disposable world and the affected old save.
2. Complete client `latest.log` and `debug.log` around the hang, not only the last Alice lines.
3. Client and integrated-server thread dumps while hung.
4. Confirmation whether the world reaches player control, remains on loading terrain, or freezes after rendering.
5. Registry remap messages and the sequence around BotSavedData/fake-player restoration.

Do not change business code until these facts distinguish the responsible lifecycle/module.

## Standby Rules

- Do not run implementation or repeat old-save loading unless the user explicitly resumes work.
- Do not mark this package `COMPLETED`, `CLIENT_VERIFIED` or `USER_ACCEPTED`.
- Do not publish another `APPROVED_FOR_IMPLEMENTATION` package.
- On resume, first create a narrow diagnostic plan from the evidence above; only then authorize a fix.
```

## Supervisor checklist

- Does this change preserve the architecture: goal-level decision, deterministic execution, server authority, semantic GUI interfaces?
- Does it preserve the separate boundaries among `HARD_PATH`, experimental `SOFT_SURFACE`, forced road construction, survival monitoring, and future tunnel planning?
- Does it contradict a numbered decision in `docs/HANDOVER.md` or the frozen plan in `docs/PATHING_REFACTOR.md`?
- Are verification evidence, client-test requirements, known limitations, and the next safe step recorded accurately?
- Was `./gradlew compileJava` run and was the committed revision pushed to `origin/master`?
