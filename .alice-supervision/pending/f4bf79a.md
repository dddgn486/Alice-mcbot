# Alice work-session review packet

- Commit: `f4bf79a7bceedf4dbf10bf5b278e9e557af89de8`
- Subject: fix: align bot inventory GUI layout, add offhand, fix quick-move
- Authored: 2026-08-23T02:50:09+08:00
- Handover updated in this commit: `true`
- Active plan ID: `20260823-bot-inventory-gui-fix-v1`
- Active plan status at handoff: `APPROVED_FOR_IMPLEMENTATION`
- Push status: verify separately after `git push origin master`

## Changed files

```text
docs/HANDOVER.md
src/main/java/com/dddgn/alice/client/gui/BotInventoryScreen.java
src/main/java/com/dddgn/alice/gui/BotInventoryFixture.java
src/main/java/com/dddgn/alice/gui/BotInventoryMenu.java
```

## Commit summary

```text
 docs/HANDOVER.md                                   |   3 +-
 .../dddgn/alice/client/gui/BotInventoryScreen.java |  43 +++++----
 .../com/dddgn/alice/gui/BotInventoryFixture.java   | 104 +++++++++++++++++++--
 .../java/com/dddgn/alice/gui/BotInventoryMenu.java |  95 +++++++++++++------
 4 files changed, 188 insertions(+), 57 deletions(-)
```

## Handover delta

```diff
diff --git a/docs/HANDOVER.md b/docs/HANDOVER.md
index e3be933..ace4b90 100644
--- a/docs/HANDOVER.md
+++ b/docs/HANDOVER.md
@@ -64,8 +64,9 @@ GitHub：`github` 远端 `dddgn486/Alice-mcbot`（本会话未推 GitHub，push
 - Bot equipment rendering (USER_ACCEPTED): multiple overlapping attempts resolved. Root cause per research (`.alice-supervision/research/fake-player-equipment-sync-20260823.md`): `bot.connection.send()` sends to the bot's own FakeConnection (no-op, drops packet); must broadcast to real tracking players. Final fix `ef43bd6` uses `((ServerLevel)bot.level()).getChunkSource().broadcast(bot, packet)`, the vanilla-native broadcast pattern. Server verification: all fixture suites remain PASS at 01:44:39. Client acceptance (2026-08-23 01:47-01:49): V1 basic (3 iron_ingot), V2 different items (diamond/stone/pickaxe), V3 multi-slot all PASS. Evidence `.alice-supervision/client-tests/a8b84b4-bot-equipment-rendering/evidence/` (latest.log 01:47:12-01:49:13; 3 transfer requests b9d1776c/3d80c468/149c8b2d). Prior attempts `a8b84b4` (packet send) and `2d26da8` (setItemSlot) both failed client-side and were superseded by the broadcast fix.
 - Bot death filter fix (USER_ACCEPTED): active plan `20260823-bot-death-filter-fix-v1`, baseline `a8b84b4`, commit `2ef35e5`. Modified `BotManager` to prevent dead Bot from being restored and causing system failure: `saveToWorld()` now saves `Health`; `restoreFromWorld()` checks `savedHealth <= 0.0f` and skips restoration with `clearBot()` log; `onServerStopping()` only saves bots with `getHealth() > 0.0f`. Manual test PASS: user killed Bot, restarted, Bot was cleared (not restored). Server verification: all fixture suites remain PASS at 00:57:53. This short-term fix prevents system failure but does not eliminate death animation (long-term solution requires BotPlayer.die() override and resurrection logic, planned separately).
 - Bot inventory GUI (awaiting client verification): active plan `20260823-bot-inventory-gui-v1`, baseline `f655be2`, HEAD (WSL) `56a05af`/`5f67115` pending review. Added a real `AbstractContainerMenu` (方案 A) over a bot's inventory, opened over the requesting player's real connection via `player.openMenu(modelProvider)` — never `bot.connection.send` (FakeConnection no-op, same lesson as equipment rendering). Files: `gui/ModMenuTypes.java` (DeferredRegister over Registries.MENU), `gui/BotInventoryMenu.java` (36 ordinary + 4 armor bot slots + player grid; read-only task guard via `BotManager.isBusy`), `client/gui/BotInventoryScreen.java` (@OnlyIn server-safe), `client/gui/ClientMenuScreens.java` (MenuScreens.register on FMLClientSetupEvent), `/alice bot-inventory <name>` subcommand (permission-2). Server verification: `BOT_INVENTORY_FIXTURE_SUITE PASS` (slotCount=76 expected=76, busy-flag matches isBusy) and all existing transfer/selection/event/parse suites PASS at 02:06:38. Client G1-G10 (opening, 36 slots, 4 armor, main-hand alignment, empty/stack counts, GUI isolation, bot_unavailable, task read-only guard, idle interaction, no server crash) await Windows testing.
+- Bot inventory GUI fix (awaiting client verification): active plan `20260823-bot-inventory-gui-fix-v1`, baseline `f0ef6fc`. User Windows test G1-G10 → FAIL/NEEDS_FIX (布局错位 + 从 bot 拿取失败 + 装备槽未渲染). Root cause: `BotInventoryMenu` used `generic_54.png` background but custom slot coordinates (bot 36 slots y=26..80 overlapping armor y=26..80), so the grid/armor/player cells misaligned with the background. Fix: re-authored `BotInventoryMenu` using the vanilla `InventoryMenu` geometry (armor column x=8 y=8/26/44/62, offhand index 40 at x=77 y=62, 27 main y=84.., 9 hotbar y=142), added the offhand slot (BOT_SLOT_CAP now 41), and re-based the player grid below (y=180/238) with no overlap; `BotInventoryScreen` now draws its own plain panel + slot cells (no fixed vanilla texture, eliminating the misalignment). `quickMoveStack` bot→player move path was validated (the bug was in the fixture asserting the cleared source stack; the item does land in the viewer grid). Server verification: `BOT_INVENTORY_FIXTURE_SUITE PASS` (slotCount=77 expected=77, noOverlap=true, quickMoveOut=true) and all existing suites PASS at 02:48:17. Client G3/G4/G9/G11 await Windows re-test. The post-transfer main-hand residue bug is a separate defect (ChestBotTransferPrimitive mainhand/backpack sync) tracked separately.
 - Known limits: fixture path outcomes use transfer-only test seam, not real-world path coverage. Server-side only; does not cover actor disconnect or full manager-owned live tick. Source highlight after interrupt remains user misunderstanding (Alice has no highlight code). Bot death animation (red fall) persists on kill; short-term filter only prevents restoration after restart. Bot inventory GUI is implemented server-side (scheme A) but awaits Windows client verification; main-hand slot is not duplicated (the selected ordinary slot is the authority, matching the equipment renderer).
-- Next safe step: A1.1 complete and accepted. Bot death animation/resurrection (long-term 方案 C) planned but not started — requires `BotPlayer.die()` override and resurrection logic. Bot inventory GUI (scheme A) awaits Windows client G1-G10 verification. Do not expand into A1.2 fulfilment, persistence recovery writes, generic container support, ACL, packets/UI, or ordinary task integrations without new approved plan.
+- Next safe step: A1.1 complete and accepted. Bot death animation/resurrection (long-term 方案 C) planned but not started — requires `BotPlayer.die()` override and resurrection logic. Bot inventory GUI fix awaits Windows client re-test (G3/G4/G9/G11). The post-transfer main-hand residue defect requires a separate plan on `ChestBotTransferPrimitive` mainhand/backpack sync. Do not expand into A1.2 fulfilment, persistence recovery writes, generic container support, ACL, packets/UI, or ordinary task integrations without new approved plan.
 
 ## Transfer Endpoint Selector (awaiting supervisor review)
 
```

## Active Plan Snapshot

```markdown
# Alice Active Work Plan

- Plan ID: `20260823-bot-inventory-gui-fix-v1`
- Status: APPROVED_FOR_IMPLEMENTATION
- Baseline: `f0ef6fc`（feat: add bot inventory GUI with task read-only guard）
- User approval: explicit approval received (2026-08-23); user tested G1-G10 → FAIL/NEEDS_FIX; user confirms 副手槽纳入本次 + 主手残留 bug 另立计划
- Supervisor route decision: 客户端实测显示 GUI 布局错位（generic_54 背景 vs 自定义 4x9 坐标，装备槽与普通槽 y 重叠）、空闲交互"从 bot 拿取失败"。用户决策：修复布局错位 + 拿取失败 + 纳入副手槽；主手残留 bug 另立计划。

## Objective

修复 Bot Inventory GUI 的布局错位与空闲交互拿取失败问题，并补充副手槽（offhand）显示。

## Allowed Scope

**只改** `src/main/java/com/dddgn/alice/gui/BotInventoryMenu.java` 及相关 GUI 文件（`client/gui/BotInventoryScreen.java`、`client/gui/ClientMenuScreens.java` 若需）：

### 修复 1：GUI 布局错位（主开发员已诊断）
- 更正 `BotInventoryMenu` 槽位坐标为**与原版背景匹配**的正确布局
- 问题根因：`generic_54.png` 背景是 9 列 x 5 行箱子网格（槽位 y=18..76 区间），但代码用自定义 4x9 网格（bot 36 槽 y=26..80）导致错位；且 bot 4 装备槽（y=26..80）与 36 普通槽完全重叠
- 修复方向：参考 `PlayerInventoryMenu`/`PlayerMenu` 的坐标布局，改用匹配 generic_54 背景的标准坐标（或改用正确的背景/格子数）
- 确保 bot 36 普通槽 + 4 装备槽 + 副手槽互不重叠，且与背景边框对齐

### 修复 2：空闲交互"从 bot 拿取失败"（主开发员已诊断）
- 修正 `quickMoveStack` 的 bot→玩家方向逻辑缺陷
- 当前：`moveItemStackTo(stack, BOT_SLOT_CAP, this.slots.size(), true)`（放入正常、拿取失败）
- 修复方向：核对 moveItemStackTo 目标索引/方向，确保 bot→玩家拿取正常（Shift 快速移动 + 普通点击拿取都正常）

### 增强 3：增加副手槽（用户申请，本次纳入）
- 在 GUI 布局中加入副手槽（offhand，Inventory index 40）
- 与主手、装备槽一起展示
- 若副手槽需交互，纳入任务保护逻辑（taskActive 时禁写）

**必要时仅准确更新**：`docs/HANDOVER.md` 记录修复

## Explicitly Forbidden

- 不改 `TransferTask`/`MineTask`/`ChestBotTransferPrimitive`/`TransferLedgerData`/`BotManager` 任务语义
- **不改** `ChestBotTransferPrimitive.syncBotInventorySlots` 的主手/背包同步（主手残留 bug，另立计划）
- 不改 equipment 渲染
- 不扩展为 Bot 设置页面

## Verification Matrix

**服务端验证**：
- `./gradlew compileJava` PASS
- `git diff --check` PASS
- 既有 suites（Transfer/Selection/SelectorEvent/CommandParse/BotInventory）保持 PASS

**服务端 focused fixture**：
- 新增/更新 `BotInventoryFixture`：断言槽位坐标对齐（普通槽/装备槽/副手槽互不重叠、坐标在匹配背景范围内）+ quickMoveStack bot→玩家取回成功

**客户端复测**（Windows `CLIENT_TEST_PENDING`）：
- G3 装备槽显示（修复后应可见）
- G4 主手槽与 equipment 渲染对齐（修复后应正确）
- G9 空闲交互（修复后从 bot 拿取应正常）
- G11 副手槽显示（新增，副手/主手/装备槽可见且不重叠）
- G2/G5/G6/G8/G10 保持 PASS（不回归）

## Stop Conditions

Stop and return for replanning if修复需要修改 transfer/task 语义、或主手残留 bug 与本次修复耦合过深需合并处理、或 existing fixtures regress。

## Implementation Time Estimate

- 实现：20-30 分钟（布局坐标修正 + quickMoveStack 修复 + 副手槽）
- 客户端复测：15-20 分钟（G3/G4/G9/G11 + 回归）
- 总计：35-50 分钟

## Notes

- 布局修复是根因（装备槽未渲染、槽位错位、文字穿格可能都与坐标/背景不匹配相关）
- 副手槽（offhand，Inventory index 40）需要确认与主手/装备槽的相对位置展示逻辑
- 任务保护（taskActive）需覆盖新加的副手槽（禁写）
- 主手残留 bug（transfer 后外部模型手上仍有物品）已在 HANDOVER 记录为独立待办，本次不处理
```

## Supervisor checklist

- Does this change preserve the architecture: goal-level decision, deterministic execution, server authority, semantic GUI interfaces?
- Does it preserve the separate boundaries among `HARD_PATH`, experimental `SOFT_SURFACE`, forced road construction, survival monitoring, and future tunnel planning?
- Does it contradict a numbered decision in `docs/HANDOVER.md` or the frozen plan in `docs/PATHING_REFACTOR.md`?
- Are verification evidence, client-test requirements, known limitations, and the next safe step recorded accurately?
- Was `./gradlew compileJava` run and was the committed revision pushed to `origin/master`?
