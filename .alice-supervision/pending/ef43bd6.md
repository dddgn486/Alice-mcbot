# Alice work-session review packet

- Commit: `ef43bd6b32297982658889ba373d38de7506c218`
- Subject: fix: broadcast bot equipment packet to tracking players
- Authored: 2026-08-23T01:46:29+08:00
- Handover updated in this commit: `true`
- Active plan ID: `20260823-bot-equipment-broadcast-fix-v1`
- Active plan status at handoff: `APPROVED_FOR_IMPLEMENTATION`
- Push status: verify separately after `git push origin master`

## Changed files

```text
docs/HANDOVER.md
src/main/java/com/dddgn/alice/transfer/ChestBotTransferPrimitive.java
```

## Commit summary

```text
 docs/HANDOVER.md                                           |  5 +++--
 .../dddgn/alice/transfer/ChestBotTransferPrimitive.java    | 14 +++++++++-----
 2 files changed, 12 insertions(+), 7 deletions(-)
```

## Handover delta

```diff
diff --git a/docs/HANDOVER.md b/docs/HANDOVER.md
index 9abcf97..b69008c 100644
--- a/docs/HANDOVER.md
+++ b/docs/HANDOVER.md
@@ -62,9 +62,10 @@ GitHub：`github` 远端 `dddgn486/Alice-mcbot`（本会话未推 GitHub，push
 - Bot inventory client sync fix (awaiting client verification): active plan `20260822-bot-inventory-sync-fix-v1`, baseline `0add921`. Added `syncBotInventorySlots()` in `ChestBotTransferPrimitive.insertBot()` to send `ClientboundContainerSetSlotPacket` for all 36 slots after bot inventory write, following `BotManager.syncMainHand()` pattern. Server verification: `TRANSFER_FIXTURE_SUITE` and `TRANSFER_LIFECYCLE_EXTENDED_FIXTURE_SUITE` remain PASS at 23:42:32. Client V1-V3 (basic function, multi-slot, selector path) await Windows testing; V4 performance observation optional. This isolated fix does not change other tasks (MineTask/PlaceTask inventory writes) or add highlight functionality.
 - Bot equipment rendering fix (awaiting client verification): active plan `20260822-bot-equipment-rendering-fix-v1`, baseline `bd9f0c3`. Extended `syncBotInventorySlots()` in `ChestBotTransferPrimitive` to add `ClientboundSetEquipmentPacket(bot.getId(), MAINHAND, mainHandStack)` for entity rendering after existing 36-slot inventory GUI sync. Server verification: `TRANSFER_FIXTURE_SUITE`, `TRANSFER_SELECTION_FIXTURE_SUITE`, `TRANSFER_SELECTOR_EVENT_FIXTURE_SUITE`, `TRANSFER_SELECTION_COMMAND_PARSE_FIXTURE_SUITE` all remain PASS at 00:15:17. Client V1-V3 (basic function with iron_ingot, different items diamond/stone/pickaxe, multi-slot consistency) await Windows testing; V4-V5 optional. This fix depends on `bd9f0c3` framework and only syncs MAINHAND (not offhand/armor). Does not implement slot priority logic (方案 A1).
 - Bot equipment rendering debug (awaiting client verification): active plan `20260823-bot-equipment-rendering-debug-v1`, baseline `2ef35e5`. User tested `a8b84b4` but Bot 手持物品仍不可见。Replaced `ClientboundSetEquipmentPacket` direct send with `bot.setItemSlot(EquipmentSlot.MAINHAND, mainHandStack)` (Minecraft native method may trigger more complete sync). Added diagnostic log `[EQUIPMENT_DEBUG] Syncing equipment: botId={}, selected={}, mainHand={}`. Server verification at 01:21:54: debug log correctly outputs (e.g., `botId=146, selected=0, mainHand=8 iron_ingot`); all fixture suites remain PASS. Client verification pending: user restart client, transfer 3 iron_ingot, observe `[EQUIPMENT_DEBUG]` log and Bot held item visibility.
+- Bot equipment broadcast fix (awaiting client verification): active plan `20260823-bot-equipment-broadcast-fix-v1`, baseline `2d26da8`. User tested both `a8b84b4` (packet send) and `2d26da8` (setItemSlot) but Bot 手持物品仍不可见. Root cause per research: `bot.connection.send()` sends to the bot's own FakeConnection (no-op, drops packet); must broadcast to real players tracking the bot. Removed `setItemSlot()` and `[EQUIPMENT_DEBUG]` log; added `((ServerLevel)bot.level()).getChunkSource().broadcast(bot, packet)`. Server verification at 01:44:39: all fixture suites remain PASS. Client V1-V4 (basic 3 iron_ingot, different items, multi-slot, distance) await Windows testing. This is the vanilla-native broadcast pattern (auto-filters FakeConnection, only sends to real tracking players).
 - Bot death filter fix (awaiting manual testing): active plan `20260823-bot-death-filter-fix-v1`, baseline `a8b84b4`. Modified `BotManager` to prevent dead Bot from being restored and causing system failure: `saveToWorld()` now saves `Health` field; `restoreFromWorld()` checks `savedHealth <= 0.0f` and skips restoration with `clearBot()` log; `onServerStopping()` only saves bots with `getHealth() > 0.0f`. Server verification: `TRANSFER_FIXTURE_SUITE`, `TRANSFER_SELECTION_FIXTURE_SUITE`, `TRANSFER_SELECTOR_EVENT_FIXTURE_SUITE`, `TRANSFER_SELECTION_COMMAND_PARSE_FIXTURE_SUITE` all remain PASS at 00:57:53. Manual server testing pending (modify `alice_bot.dat` Health to 0.0f, verify skip+clear log on restart). This short-term fix prevents system failure but does not eliminate death animation (Bot still turns red/falls when killed; long-term solution requires BotPlayer.die() override and resurrection logic).
-- Known limits: fixture path outcomes use transfer-only test seam, not real-world path coverage. Server-side only; does not cover actor disconnect or full manager-owned live tick. Client C2/C3 SUSPENDED/manual_takeover_required states proven by server fixture + client observation (conservative stop), but transfer-status query not captured in client logs. Source highlight after interrupt remains user misunderstanding (Alice has no highlight code). Bot death animation (red fall) persists on kill; short-term filter only prevents restoration after restart. Bot equipment rendering (`a8b84b4` packet send) not visible on client; debug attempt uses `setItemSlot()` alternative.
-- Next safe step: A1.1 complete and accepted. Bot inventory sync fix, equipment rendering debug, and death filter fix await testing. Do not expand into A1.2 fulfilment, persistence recovery writes, generic container support, ACL, packets/UI, Bot resurrection, or ordinary task integrations without new approved plan.
+- Known limits: fixture path outcomes use transfer-only test seam, not real-world path coverage. Server-side only; does not cover actor disconnect or full manager-owned live tick. Client C2/C3 SUSPENDED/manual_takeover_required states proven by server fixture + client observation (conservative stop), but transfer-status query not captured in client logs. Source highlight after interrupt remains user misunderstanding (Alice has no highlight code). Bot death animation (red fall) persists on kill; short-term filter only prevents restoration after restart. Bot equipment rendering not yet visible on client across `a8b84b4`/`2d26da8`/`707140fd` attempts; broadcast fix awaits client verification.
+- Next safe step: A1.1 complete and accepted. Bot inventory sync fix, bot equipment broadcast fix, and death filter fix await testing. Do not expand into A1.2 fulfilment, persistence recovery writes, generic container support, ACL, packets/UI, Bot resurrection, or ordinary task integrations without new approved plan.
 
 ## Transfer Endpoint Selector (awaiting supervisor review)
 
```

## Active Plan Snapshot

```markdown
# Alice Active Work Plan

- Plan ID: `20260823-bot-equipment-broadcast-fix-v1`
- Status: APPROVED_FOR_IMPLEMENTATION
- Baseline: `2d26da8`（debug: use setItemSlot for bot equipment sync with diagnostic log）
- User approval: explicit approval received (2026-08-23); user tested `2d26da8` 后 Bot 手持物品仍不可见
- Supervisor route decision: 调研报告 `.alice-supervision/research/fake-player-equipment-sync-20260823.md` 已审核采纳。根因是发送目标错误：`bot.connection.send()` 发送给 Bot 自己（FakeConnection 空操作丢弃 packet），而非广播给玩家。方案 B（使用 `ServerLevel.getChunkSource().broadcast()`）最符合原版模式。

## Objective

修复 Bot 手持物品客户端可见性：将 equipment packet 广播给跟踪 Bot 的真实玩家（而非发送给 FakeConnection）。

## Allowed Scope

**只改一个文件**：`src/main/java/com/dddgn/alice/transfer/ChestBotTransferPrimitive.java`

**具体改动**（基于调研报告方案 B）：

修改 `syncBotInventorySlots()` 方法：
1. 保留现有 36 槽位 inventory sync（`ClientboundContainerSetSlotPacket`）
2. **移除** `setItemSlot()` 直接发送
3. **新增** 使用 `ServerLevel.getChunkSource().broadcast()` 广播 equipment packet：

```java
private static void syncBotInventorySlots(Inventory inventory) {
    if (!(inventory.player instanceof ServerPlayer bot)) return;
    
    // 同步 inventory 槽位（现有逻辑保持不变）
    for (int i = 0; i < inventory.getContainerSize(); i++) {
        bot.connection.send(new ClientboundContainerSetSlotPacket(-2, 0, i, inventory.getItem(i)));
    }
    
    // 广播 equipment 给跟踪 Bot 的真实玩家
    ItemStack mainHandStack = inventory.getItem(inventory.selected);
    if (!mainHandStack.isEmpty()) {
        ClientboundSetEquipmentPacket packet = new ClientboundSetEquipmentPacket(
            bot.getId(),
            List.of(Pair.of(EquipmentSlot.MAINHAND, mainHandStack))
        );
        ((ServerLevel)bot.level()).getChunkSource().broadcast(bot, packet);
    }
}
```

4. **移除** `[EQUIPMENT_DEBUG]` 诊断日志（不再需要）

**必要时仅准确更新**：`docs/HANDOVER.md` 记录修复（重新使用广播方式）

## Explicitly Forbidden

- 不改 `BotPlayer.java`、`BotManager.java`
- 不方案 A（广播给所有玩家）——性能差
- 不方案 C（反射清空缓存）——延迟高
- 不方案 D（重写 BotPlayer）——影响面大
- 不改其他任务或模块

## Verification Matrix

**服务端验证**：
- `./gradlew compileJava` PASS
- `git diff --check` PASS
- 既有 fixture 保持 PASS（不应受影响）

**客户端验证**（V1-V4）：
- **V1（基本功能）**：Transfer 3 铁锭 → 观察 Bot 搬运途中手持铁锭模型可见
- **V2（不同物品）**：Transfer diamond/stone/pickaxe → 观察所有类型正确渲染
- **V3（多槽位）**：Transfer iron_ingot×64 → 观察手持与主手槽位一致
- **V4（不同距离，可选）**：从远处观察 Bot → 确认 broadcast 正常

## Stop Conditions

Stop and return for replanning if broadcast 仍不工作（可能需深度调研 entity tracker 机制），or existing fixtures regress.

## Implementation Time Estimate

- 实现：5-10 分钟
- 客户端验证：15-20 分钟
- 总计：20-30 分钟

## Notes

- 根因：FakeConnection.send() 是空操作，packet 发送给 Bot 自己会被丢弃
- 方案 B 使用原版 `ServerLevel.getChunkSource().broadcast()`，自动过滤 FakeConnection，只发给跟踪 Bot 的真实玩家
- 这是最符合 Minecraft 原版模式的解决方案
```

## Supervisor checklist

- Does this change preserve the architecture: goal-level decision, deterministic execution, server authority, semantic GUI interfaces?
- Does it preserve the separate boundaries among `HARD_PATH`, experimental `SOFT_SURFACE`, forced road construction, survival monitoring, and future tunnel planning?
- Does it contradict a numbered decision in `docs/HANDOVER.md` or the frozen plan in `docs/PATHING_REFACTOR.md`?
- Are verification evidence, client-test requirements, known limitations, and the next safe step recorded accurately?
- Was `./gradlew compileJava` run and was the committed revision pushed to `origin/master`?
