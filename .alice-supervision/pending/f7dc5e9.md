# Alice work-session review packet

- Commit: `f7dc5e910fdfce13e68b3570cbb65b9f852d65d6`
- Subject: fix: align bot inventory screen with snapshot slot indices
- Authored: 2026-08-23T13:54:02+08:00
- Handover updated in this commit: `true`
- Active plan ID: `20260824-bot-inventory-gui-view-index-fix-v1`
- Active plan status at handoff: `APPROVED_FOR_IMPLEMENTATION`
- Push status: verify separately after `git push origin master`

## Changed files

```text
docs/HANDOVER.md
src/main/java/com/dddgn/alice/client/gui/BotInventoryScreen.java
```

## Commit summary

```text
 docs/HANDOVER.md                                   |  5 +--
 .../dddgn/alice/client/gui/BotInventoryScreen.java | 41 +++++++++++-----------
 2 files changed, 23 insertions(+), 23 deletions(-)
```

## Handover delta

```diff
diff --git a/docs/HANDOVER.md b/docs/HANDOVER.md
index 8349e33..60a343e 100644
--- a/docs/HANDOVER.md
+++ b/docs/HANDOVER.md
@@ -70,8 +70,9 @@ GitHub：`github` 远端 `dddgn486/Alice-mcbot`（本会话未推 GitHub，push
 - Bot inventory GUI final fix (awaiting client verification): active plan `20260824-bot-inventory-gui-final-fix-v1`, baseline `2b995ba`. User re-test after 2b995ba → picked-up items still vanish / placed items still double; user chose to follow the source-research advice to use `inventory.png` + standard geometry. `BotInventoryScreen.renderBg` now blits the vanilla `inventory.png` (176x166) for the bot backpack section (which uses vanilla player-backpack slot geometry) and fills the region below y=166 with a plain 0xFF-alpha panel for the player grid. Added `[GUI_DEBUG]` diagnostic logging to `quickMoveStack` (before/after move: index, slotItem, stackCount, moved, stackNow, slotNow, returned original count) and to `clicked` (before/after/swallowed) so client testing can pinpoint the vanish/double root cause. Also verified armor on menu slot 0-3, offhand on menu slot 4, BOT_SLOT_CAP=41. Server verification: `BOT_INVENTORY_FIXTURE_SUITE PASS` (slotCount=77 noOverlap=true customArmor=true quickMoveOut=true) and all existing suites PASS at 03:33:29. Client re-test must capture and report the `[GUI_DEBUG]` logs to the supervisor to confirm/refute the vanish/double root cause.
 - Bot inventory GUI pickup sync fix (awaiting client verification): active plan `20260824-bot-inventory-gui-pickup-sync-fix-v1`, baseline `55e3d32`. User re-test after 55e3d32 → UI improved but picked-up items still vanish; user confirmed the operation is a normal left-click (PICKUP), not Shift. Source diagnosis `.alice-supervision/research/container-menu-clicked-pickup-source-20260823.md` (scheme A) confirmed the root cause: on PICKUP the server-side tryRemove correctly moves the item to the carried stack, but `Inventory.setItem`/`removeItem` does not call `setChanged()`, and the bot's own `FakeConnection.send()` is a no-op → the player's client bot-slot snapshot does not refresh, so the picked-up item appears to vanish. Fix: in `clicked()`, after a PICKUP that touches a bot slot (slotId < BOT_SLOT_CAP) and passes the read-only guard, force `setChanged()` on all bot slots to trigger `broadcastChanges()` and sync the updated bot inventory to the player's client. `quickMoveStack` was not changed (Shift is not the user's path). Server verification: `BOT_INVENTORY_FIXTURE_SUITE PASS` (slotCount=77 noOverlap=true customArmor=true quickMoveOut=true) and all existing suites PASS at 03:43:39. Client re-test must confirm normal left-click pick-up no longer vanishes.
 - Bot inventory GUI direction B (custom packet + Screen) rewrite (awaiting client verification): active plan `20260824-bot-inventory-gui-custom-packet-v1`, baseline `1621ad2`. After 6 failed rounds of `AbstractContainerMenu`, the user chose direction B: a custom S2C snapshot + C2S action packet + pure client `Screen`, bypassing the container-menu protocol entirely (root cause was the mixed index partition / setChanged chain). New server-authoritative layered: `gui/BotInventorySnapshot.java` (record botId/name/slots, 36 ordinary + 4 armor + 1 offhand, SLOT_COUNT=41), `gui/BotInventoryService.java` (read snapshot + `applyAction` as the ONLY write path — validates permission/bot exists/`isBusy` read-only/slot 0-40/canEquip/maxStack=1/binding-curse, then pushes the refreshed snapshot), `network/BotInventoryPacket.java` (S2C, via `PacketDistributor.PLAYER.with(viewer)`, opens the screen via `DistExecutor` client-only), `network/BotInventoryActionPacket.java` (C2S PICKUP/PLACE/QUICK_MOVE, handled on the server in `BotInventoryService`), `client/ClientBotInventoryState.java` (@OnlyIn volatile snapshot + `openScreen`), and a rewritten `client/gui/BotInventoryScreen.java` (pure `Screen`, renders from the snapshot, sends C2S intents, never mutates local state). `AliceNetwork` registers both packets; `BotCommand.bot-inventory` now pushes a snapshot instead of `openMenu`; `AliceMod` no longer registers `ModMenuTypes`; `ClientMenuScreens` is a no-op. Server verification: `BOT_INVENTORY_FIXTURE_SUITE PASS` (slotCount=41, snapshotPass=true, pickupPass=true, serverAuthority=true) and all existing suites PASS at 04:07:50. Client G1-G13 await Windows testing.
-- Known limits: fixture path outcomes use transfer-only test seam, not real-world path coverage. Server-side only; does not cover actor disconnect or full manager-owned live tick. Source highlight after interrupt remains user misunderstanding (Alice has no highlight code). Bot death animation (red fall) persists on kill; short-term filter only prevents restoration after restart. Bot inventory GUI direction B is implemented server-authoritatively (snapshot + applyAction + Pure Screen) but awaits Windows client verification; main-hand slot is not duplicated (the selected ordinary slot is the authority). The server-authoritative client never mutates local slot state, eliminating the vanish/double root cause at the protocol level; the retired `BotInventoryMenu`/`ModMenuTypes`/`ClientMenuScreens` are kept (unregistered) for rollback.
-- Next safe step: A1.1 complete and accepted. Bot death animation/resurrection (long-term 方案 C) planned but not started — requires `BotPlayer.die()` override and resurrection logic. Bot inventory GUI direction B awaits Windows client G1-G13 verification (opening, 36+4+1 display, pickup/place/Shift, armor wear, task read-only, server-authoritative consistency). The post-transfer main-hand residue defect requires a separate plan on `ChestBotTransferPrimitive` mainhand/backpack sync. Do not expand into A1.2 fulfilment, persistence recovery writes, generic container support, ACL, packets/UI, or ordinary task integrations without new approved plan.
+- Bot inventory GUI view-index fix (awaiting client verification): active plan `20260824-bot-inventory-gui-view-index-fix-v1`, baseline `9534a5d`. Root cause confirmed from TLM/review logs: client Screen had view indices armor 0-3/offhand 4/main 5-31/hotbar 32-40 while the authoritative snapshot/service indices are ordinary 0-35, armor 36-39, offhand 40; clicks therefore sent the wrong slot IDs and returned `slot_empty`. Reworked `BotInventoryScreen.slotX/slotY/hitTest` to map the server snapshot directly: ordinary hotbar 0-8 at y=142, ordinary main 9-35 at y=84/102/120, armor 36-39 at x=8/y=8/26/44/62, offhand 40 at x=77/y=62. No client-side state mutation or server/service change. Server verification: `compileJava`, `git diff --check`, `BOT_INVENTORY_FIXTURE_SUITE PASS` and all existing suites PASS at 13:48:13. Client must re-test G2-G13 and capture action codes, expecting visual slot index and service slot index to match.
+- Known limits: fixture path outcomes use transfer-only test seam, not real-world path coverage. Server-side only; does not cover actor disconnect or full manager-owned live tick. Source highlight after interrupt remains user misunderstanding (Alice has no highlight code). Bot death animation (red fall) persists on kill; short-term filter only prevents restoration after restart. Bot inventory GUI direction B is implemented server-authoritatively (snapshot + applyAction + Pure Screen) but awaits Windows client verification; main-hand slot is not duplicated (the selected ordinary slot is the authority). The server-authoritative client never mutates local slot state, eliminating the vanish/double root cause at the protocol level; view-index mismatch was fixed in this package. The retired `BotInventoryMenu`/`ModMenuTypes`/`ClientMenuScreens` are kept (unregistered) for rollback.
+- Next safe step: A1.1 complete and accepted. Bot death animation/resurrection (long-term 方案 C) planned but not started — requires `BotPlayer.die()` override and resurrection logic. Bot inventory GUI direction B awaits Windows client G1-G13 verification (opening, 36+4+1 display, pickup/place/Shift, armor wear, task read-only, server-authoritative consistency) with slot-index action logs. The post-transfer main-hand residue defect requires a separate plan on `ChestBotTransferPrimitive` mainhand/backpack sync. Do not expand into A1.2 fulfilment, persistence recovery writes, generic container support, ACL, packets/UI, or ordinary task integrations without new approved plan.
 
 ## Transfer Endpoint Selector (awaiting supervisor review)
 
```

## Active Plan Snapshot

```markdown
# Alice Active Work Plan

- Plan ID: `20260824-bot-inventory-gui-view-index-fix-v1`
- Status: APPROVED_FOR_IMPLEMENTATION
- Baseline: `9534a5d`（方向 B 自定义 packet + Screen）
- User approval: explicit approval received (2026-08-24); user decides 继续推进方向 B 补丁（修 view index 映射）
- Supervisor route decision: 第 7 轮测试证据 `bot_inv ... act=PICKUP slot=6/11/33/16 code=slot_empty` 根因确认 = **客户端 Screen 的 view index 布局与服务端快照 index 语义完全错位**（客户端把普通槽 0-35 画成 armor/offhand/main/hotbar 布局，服务端 0-35 是普通槽、36-39 armor、40 offhand）。TLM 调研 `.alice-supervision/research/tlm-maid-inventory-gui-research-20260824.md` 也强调需显式 view index 语义。方案 A 保留但停用，不回档。

## Objective

修复方向 B 的 `slot_empty`：让客户端 `BotInventoryScreen` 的槽位绘制/点击 index 与服务端 `BotInventorySnapshot` 的 index 语义完全一致（0-35 普通槽、36-39 armor、40 offhand）。

## Allowed Scope

**只改** `src/main/java/com/dddgn/alice/client/gui/BotInventoryScreen.java`（+ 必要时 `gui/BotInventorySnapshot.java` 加辅助方法）

### 修复要点

1. **统一 view index 语义**（服务端为准）：
   - index 0-35：普通槽（统一 4x9 网格或 3x9 main + 9 hotbar 布局，视觉任意但 index 必须连续对应快照）
   - index 36-39：armor 列
   - index 40：offhand
2. **重写 `slotX(int)`/`slotY(int)`** 使其与快照 index 语义一致
3. **`hitTest` 命中检测**返回的 index 必须与服务端 `BotInventoryService.applyAction` 的 slotIndex 语义一致
4. 保留唯服务端权威（客户端不改本地数据、收服务端快照才刷新）

### 建议布局（供参考，主开发员可微调但 index 语义必须正确）
- 0-35 普通槽：3 行 main（index 9-35）+ 1 行 hotbar（index 0-8）——对齐 vanilla 玩家背包
- 36-39 armor 列
- 40 offhand

**必要时仅准确更新**：`docs/HANDOVER.md` 记录修复

## Explicitly Forbidden

- 不改 `BotInventoryService`/`BotInventorySnapshot` 的服务端 index 语义（0-35/36-39/40 是权威）
- 不改 `TransferTask`/`MineTask`/`ChestBotTransferPrimitive`/`TransferLedgerData`/`BotManager` 任务语义
- 不改 `syncBotInventorySlots`（主手残留 bug，另立计划）
- 不实现拖拽 cursor（留作后续）
- 不扩展为 Bot 设置页面
- 不回档/不修改方案 A（保留但停用）

## Verification Matrix

**服务端验证**：
- `./gradlew compileJava` PASS
- `git diff --check` PASS
- 既有 suites（BotInventory/Transfer/Selection/SelectorEvent/CommandParse）保持 PASS

**服务端 focused fixture**（更新 `BotInventoryFixture`）：
- 断言快照 index 语义（0-35 普通、36-39 armor、40 offhand）
- 断言 `slotX/slotY` 布局与 index 语义一致（若可测）

**客户端复测**（Windows `CLIENT_TEST_PENDING`，聚焦）：
- 打开 GUI → 点击**视觉有物品的格子** → 不再 `slot_empty`（核心）
- 拿取（PICKUP）不消失
- 放入（PLACE）不翻倍
- Shift（QUICK_MOVE）正常
- 装备穿戴（armor 36-39 / offhand 40）
- 回归：G1 入口、G2 36 槽、G11 无崩溃

## Stop Conditions

Stop and return for replanning if view index 统一后仍 `slot_empty`（可能需快照 revision/expectedStackFingerprint，进入 TLM 报告的更大 schema 改造），or existing fixtures regress.

## Implementation Time Estimate

- 实现：15-25 分钟（重写 slotX/slotY/hitTest 布局）
- 客户端复测：15-20 分钟
- 总计：30-45 分钟

## Notes

- 根因明确：客户端 Screen 用玩家背包布局（armor 在前）绘制快照，但快照 index 是 vanilla Inventory 语义（普通槽在前）→ index 错位 → 点击视觉槽发错 index → 服务端读空 → slot_empty
- 修复原则（TLM 报告）：显式 view index 语义表，客户端布局与快照 index 严格对应
- 若补丁后仍不稳 → 进入 TLM 报告 7.2-7.5 的完整 schema（snapshotRevision + expectedStackFingerprint + ActionResult）
```

## Supervisor checklist

- Does this change preserve the architecture: goal-level decision, deterministic execution, server authority, semantic GUI interfaces?
- Does it preserve the separate boundaries among `HARD_PATH`, experimental `SOFT_SURFACE`, forced road construction, survival monitoring, and future tunnel planning?
- Does it contradict a numbered decision in `docs/HANDOVER.md` or the frozen plan in `docs/PATHING_REFACTOR.md`?
- Are verification evidence, client-test requirements, known limitations, and the next safe step recorded accurately?
- Was `./gradlew compileJava` run and was the committed revision pushed to `origin/master`?
