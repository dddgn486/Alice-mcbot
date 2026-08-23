# Alice work-session review packet

- Commit: `25b1daaf3b931fb87e8c021ea50af7e9349019d6`
- Subject: fix: render bot inventory panel and sync quick-move source slot
- Authored: 2026-08-23T03:04:19+08:00
- Handover updated in this commit: `true`
- Active plan ID: `20260823-bot-inventory-gui-render-fix-v1`
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
 docs/HANDOVER.md                                   |  3 ++-
 .../dddgn/alice/client/gui/BotInventoryScreen.java | 20 ++++++-----------
 .../com/dddgn/alice/gui/BotInventoryFixture.java   | 20 ++++++++++-------
 .../java/com/dddgn/alice/gui/BotInventoryMenu.java | 25 ++++++++++++++--------
 4 files changed, 37 insertions(+), 31 deletions(-)
```

## Handover delta

```diff
diff --git a/docs/HANDOVER.md b/docs/HANDOVER.md
index ace4b90..6df502d 100644
--- a/docs/HANDOVER.md
+++ b/docs/HANDOVER.md
@@ -65,8 +65,9 @@ GitHub：`github` 远端 `dddgn486/Alice-mcbot`（本会话未推 GitHub，push
 - Bot death filter fix (USER_ACCEPTED): active plan `20260823-bot-death-filter-fix-v1`, baseline `a8b84b4`, commit `2ef35e5`. Modified `BotManager` to prevent dead Bot from being restored and causing system failure: `saveToWorld()` now saves `Health`; `restoreFromWorld()` checks `savedHealth <= 0.0f` and skips restoration with `clearBot()` log; `onServerStopping()` only saves bots with `getHealth() > 0.0f`. Manual test PASS: user killed Bot, restarted, Bot was cleared (not restored). Server verification: all fixture suites remain PASS at 00:57:53. This short-term fix prevents system failure but does not eliminate death animation (long-term solution requires BotPlayer.die() override and resurrection logic, planned separately).
 - Bot inventory GUI (awaiting client verification): active plan `20260823-bot-inventory-gui-v1`, baseline `f655be2`, HEAD (WSL) `56a05af`/`5f67115` pending review. Added a real `AbstractContainerMenu` (方案 A) over a bot's inventory, opened over the requesting player's real connection via `player.openMenu(modelProvider)` — never `bot.connection.send` (FakeConnection no-op, same lesson as equipment rendering). Files: `gui/ModMenuTypes.java` (DeferredRegister over Registries.MENU), `gui/BotInventoryMenu.java` (36 ordinary + 4 armor bot slots + player grid; read-only task guard via `BotManager.isBusy`), `client/gui/BotInventoryScreen.java` (@OnlyIn server-safe), `client/gui/ClientMenuScreens.java` (MenuScreens.register on FMLClientSetupEvent), `/alice bot-inventory <name>` subcommand (permission-2). Server verification: `BOT_INVENTORY_FIXTURE_SUITE PASS` (slotCount=76 expected=76, busy-flag matches isBusy) and all existing transfer/selection/event/parse suites PASS at 02:06:38. Client G1-G10 (opening, 36 slots, 4 armor, main-hand alignment, empty/stack counts, GUI isolation, bot_unavailable, task read-only guard, idle interaction, no server crash) await Windows testing.
 - Bot inventory GUI fix (awaiting client verification): active plan `20260823-bot-inventory-gui-fix-v1`, baseline `f0ef6fc`. User Windows test G1-G10 → FAIL/NEEDS_FIX (布局错位 + 从 bot 拿取失败 + 装备槽未渲染). Root cause: `BotInventoryMenu` used `generic_54.png` background but custom slot coordinates (bot 36 slots y=26..80 overlapping armor y=26..80), so the grid/armor/player cells misaligned with the background. Fix: re-authored `BotInventoryMenu` using the vanilla `InventoryMenu` geometry (armor column x=8 y=8/26/44/62, offhand index 40 at x=77 y=62, 27 main y=84.., 9 hotbar y=142), added the offhand slot (BOT_SLOT_CAP now 41), and re-based the player grid below (y=180/238) with no overlap; `BotInventoryScreen` now draws its own plain panel + slot cells (no fixed vanilla texture, eliminating the misalignment). `quickMoveStack` bot→player move path was validated (the bug was in the fixture asserting the cleared source stack; the item does land in the viewer grid). Server verification: `BOT_INVENTORY_FIXTURE_SUITE PASS` (slotCount=77 expected=77, noOverlap=true, quickMoveOut=true) and all existing suites PASS at 02:48:17. Client G3/G4/G9/G11 await Windows re-test. The post-transfer main-hand residue bug is a separate defect (ChestBotTransferPrimitive mainhand/backpack sync) tracked separately.
+- Bot inventory GUI render fix (awaiting client verification): active plan `20260823-bot-inventory-gui-render-fix-v1`, baseline `f4bf79a`. User re-test after f4bf79a → severe bugs: GUI panel background completely missing (slot cells float over the world) + picked-up items vanish. Screenshot `run/screenshots/2026-08-23_02.54.46.png`. Two root causes: (1) `BotInventoryScreen.render()` overrode the render and called `renderBackground()` while the base `AbstractContainerScreen.render()` also calls it — double-call leaves the self-drawn panel masked; (2) `quickMoveStack` returned the stack mutated by `moveItemStackTo` without `slot.set()`/`setChanged()`, so server/client slot state diverged. Fix: removed the custom `render()`/`renderBackground()` override (only `renderBg()` draws the panel, alpha 0xFFRRGGBB); re-wrote `quickMoveStack` with vanilla ChestMenu semantics (copy pre-move stack, move it, `slot.set(EMPTY)` when empty else `slot.setChanged()`, return the pre-move copy). Server verification: `BOT_INVENTORY_FIXTURE quickMoveOut PASS` (returnedCopy=true landed=true sourceCleared=true), `BOT_INVENTORY_FIXTURE_SUITE PASS` (slotCount=77 noOverlap=true quickMoveOut=true), all existing suites PASS at 03:02:28. Client G9 (item pick-up no longer vanishes + panel background renders) and G2-G6/G8/G10/G11 no-regression await Windows re-test.
 - Known limits: fixture path outcomes use transfer-only test seam, not real-world path coverage. Server-side only; does not cover actor disconnect or full manager-owned live tick. Source highlight after interrupt remains user misunderstanding (Alice has no highlight code). Bot death animation (red fall) persists on kill; short-term filter only prevents restoration after restart. Bot inventory GUI is implemented server-side (scheme A) but awaits Windows client verification; main-hand slot is not duplicated (the selected ordinary slot is the authority, matching the equipment renderer).
-- Next safe step: A1.1 complete and accepted. Bot death animation/resurrection (long-term 方案 C) planned but not started — requires `BotPlayer.die()` override and resurrection logic. Bot inventory GUI fix awaits Windows client re-test (G3/G4/G9/G11). The post-transfer main-hand residue defect requires a separate plan on `ChestBotTransferPrimitive` mainhand/backpack sync. Do not expand into A1.2 fulfilment, persistence recovery writes, generic container support, ACL, packets/UI, or ordinary task integrations without new approved plan.
+- Next safe step: A1.1 complete and accepted. Bot death animation/resurrection (long-term 方案 C) planned but not started — requires `BotPlayer.die()` override and resurrection logic. Bot inventory GUI render fix awaits Windows client re-test (G9 item pick-up + panel background; G2-G6/G8/G10/G11 no-regression). The post-transfer main-hand residue defect requires a separate plan on `ChestBotTransferPrimitive` mainhand/backpack sync. Do not expand into A1.2 fulfilment, persistence recovery writes, generic container support, ACL, packets/UI, or ordinary task integrations without new approved plan.
 
 ## Transfer Endpoint Selector (awaiting supervisor review)
 
```

## Active Plan Snapshot

```markdown
# Alice Active Work Plan

- Plan ID: `20260823-bot-inventory-gui-render-fix-v1`
- Status: APPROVED_FOR_IMPLEMENTATION
- Baseline: `f4bf79a`（fix: align bot inventory GUI layout, add offhand, fix quick-move）
- User approval: explicit approval received (2026-08-23); user tested after f4bf79a → 严重 bug：面板背景完全不显示 + 拿取物品消失
- Supervisor route decision: 联网调研 `.alice-supervision/research/minecraft-container-gui-correct-implementation-20260823.md` 已审核采纳。两个根因：①BotInventoryScreen.render() 重写调 renderBackground() 导致面板被遮罩覆盖；②quickMoveStack 返回被修改 stack 且未更新源槽。用户明确建议联网调查，已执行。

## Objective

修复 Bot Inventory GUI 的两个严重缺陷：①面板背景完全不显示；②拿取物品就消失。

## Allowed Scope

**只改** `src/main/java/com/dddgn/alice/client/gui/BotInventoryScreen.java` + `src/main/java/com/dddgn/alice/gui/BotInventoryMenu.java`

### 修复 1：面板背景不显示（缺陷 1）
- 移除 `BotInventoryScreen.render()` 中自定义的 `renderBackground()` 调用，让基类 `AbstractContainerScreen.render()` 统一处理时序
- 只重写 `renderBg()` 绘制面板背景（在遮罩上方）
- `fill()` 颜色使用 `0xFFRRGGBB`（明确 alpha）
- 确保面板 + 槽位格正确渲染（自绘方案，参考调研报告）

### 修复 2：拿取物品消失（缺陷 2）
- 重写 `BotInventoryMenu.quickMoveStack()` 参考原版 `ChestMenu`：
  ```java
  ItemStack stack = slot.getItem();
  ItemStack original = stack.copy();  // 保存移动前副本
  if (index < BOT_SLOT_CAP) {
      if (!moveItemStackTo(stack, BOT_SLOT_CAP, this.slots.size(), true)) return ItemStack.EMPTY;
  } else {
      if (!moveItemStackTo(stack, 0, BOT_SLOT_CAP, false)) return ItemStack.EMPTY;
  }
  if (stack.isEmpty()) {
      slot.set(ItemStack.EMPTY);   // 源槽清空
  } else {
      slot.setChanged();           // 源槽仍有剩余
  }
  return original;  // 返回移动前副本（原版语义）
  ```

**必要时仅准确更新**：`docs/HANDOVER.md` 记录修复

## Explicitly Forbidden

- 不改 `TransferTask`/`MineTask`/`ChestBotTransferPrimitive`/`TransferLedgerData`/`BotManager` 任务语义
- 不改 `ChestBotTransferPrimitive.syncBotInventorySlots`（主手残留 bug，另立计划）
- 不改 equipment 渲染
- 不扩展为 Bot 设置页面

## Verification Matrix

**服务端验证**：
- `./gradlew compileJava` PASS
- `git diff --check` PASS
- 既有 suites（BotInventory/Transfer/Selection/SelectorEvent/CommandParse）保持 PASS

**服务端 focused fixture**：
- 更新 `BotInventoryFixture`：断言 quickMoveStack bot→玩家取回成功（使用独立 viewer bot 验证落位），不再断言"返回非空 stack"（原版语义是返回移动前副本）

**客户端复测**（Windows `CLIENT_TEST_PENDING`）：
- G9 空闲交互（拿取物品**不消失**、放入、Shift）
- 面板背景显示（GUI 有边框/背景包裹槽位格子）
- G2/G3/G4/G5/G6/G8/G10/G11 不回归

## Stop Conditions

Stop and return for replanning if修复仍失败（可能需进一步反编译确认 AbstractContainerScreen 精确时序），or existing fixtures regress.

## Implementation Time Estimate

- 实现：15-25 分钟（修复时序 + quickMoveStack）
- 客户端复测：15-20 分钟
- 总计：30-45 分钟

## Notes

- 两个缺陷都是明确、独立的：时序 bug（renderBackground 双重调用）+ 原版语义错误（quickMoveStack 返回值/源槽更新）
- 参考原版 `PlayerInventoryScreen`/`ChestScreen`/`ChestMenu` 的正确实现（调研报告已给出）
- 用户强调的"参考成熟实现"原则在此次修复中严格执行
```

## Supervisor checklist

- Does this change preserve the architecture: goal-level decision, deterministic execution, server authority, semantic GUI interfaces?
- Does it preserve the separate boundaries among `HARD_PATH`, experimental `SOFT_SURFACE`, forced road construction, survival monitoring, and future tunnel planning?
- Does it contradict a numbered decision in `docs/HANDOVER.md` or the frozen plan in `docs/PATHING_REFACTOR.md`?
- Are verification evidence, client-test requirements, known limitations, and the next safe step recorded accurately?
- Was `./gradlew compileJava` run and was the committed revision pushed to `origin/master`?
