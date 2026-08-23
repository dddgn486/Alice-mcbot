# Alice work-session review packet

- Commit: `f0ef6fcba2ac7295302674c7bb0e7b0f46c5d528`
- Subject: feat: add bot inventory GUI with task read-only guard
- Authored: 2026-08-23T02:08:33+08:00
- Handover updated in this commit: `true`
- Active plan ID: `20260823-bot-inventory-gui-v1`
- Active plan status at handoff: `APPROVED_FOR_IMPLEMENTATION`
- Push status: verify separately after `git push origin master`

## Changed files

```text
docs/HANDOVER.md
src/main/java/com/dddgn/alice/AliceMod.java
src/main/java/com/dddgn/alice/bot/BotSelftest.java
src/main/java/com/dddgn/alice/client/gui/BotInventoryScreen.java
src/main/java/com/dddgn/alice/client/gui/ClientMenuScreens.java
src/main/java/com/dddgn/alice/command/BotCommand.java
src/main/java/com/dddgn/alice/gui/BotInventoryFixture.java
src/main/java/com/dddgn/alice/gui/BotInventoryMenu.java
src/main/java/com/dddgn/alice/gui/ModMenuTypes.java
```

## Commit summary

```text
 docs/HANDOVER.md                                   |   5 +-
 src/main/java/com/dddgn/alice/AliceMod.java        |   3 +
 src/main/java/com/dddgn/alice/bot/BotSelftest.java |   3 +
 .../dddgn/alice/client/gui/BotInventoryScreen.java |  52 ++++++++
 .../dddgn/alice/client/gui/ClientMenuScreens.java  |  31 +++++
 .../java/com/dddgn/alice/command/BotCommand.java   |  33 +++++
 .../com/dddgn/alice/gui/BotInventoryFixture.java   |  35 ++++++
 .../java/com/dddgn/alice/gui/BotInventoryMenu.java | 138 +++++++++++++++++++++
 .../java/com/dddgn/alice/gui/ModMenuTypes.java     |  28 +++++
 9 files changed, 326 insertions(+), 2 deletions(-)
```

## Handover delta

```diff
diff --git a/docs/HANDOVER.md b/docs/HANDOVER.md
index 90d74f4..e3be933 100644
--- a/docs/HANDOVER.md
+++ b/docs/HANDOVER.md
@@ -63,8 +63,9 @@ GitHub：`github` 远端 `dddgn486/Alice-mcbot`（本会话未推 GitHub，push
 - Bot inventory client sync fix (USER_ACCEPTED): active plan `20260822-bot-inventory-sync-fix-v1`, baseline `0add921`, commit `bd9f0c3`. Added `syncBotInventorySlots()` in `ChestBotTransferPrimitive.insertBot()` to send `ClientboundContainerSetSlotPacket` for all 36 slots after bot inventory write, following `BotManager.syncMainHand()` pattern. Server verification: fixture suites remain PASS. Client acceptance: Bot inventory now visible client-side (folded into equipment rendering test since it requires GUI to fully observe; the same sync primitive covers both). This isolated fix does not change other tasks.
 - Bot equipment rendering (USER_ACCEPTED): multiple overlapping attempts resolved. Root cause per research (`.alice-supervision/research/fake-player-equipment-sync-20260823.md`): `bot.connection.send()` sends to the bot's own FakeConnection (no-op, drops packet); must broadcast to real tracking players. Final fix `ef43bd6` uses `((ServerLevel)bot.level()).getChunkSource().broadcast(bot, packet)`, the vanilla-native broadcast pattern. Server verification: all fixture suites remain PASS at 01:44:39. Client acceptance (2026-08-23 01:47-01:49): V1 basic (3 iron_ingot), V2 different items (diamond/stone/pickaxe), V3 multi-slot all PASS. Evidence `.alice-supervision/client-tests/a8b84b4-bot-equipment-rendering/evidence/` (latest.log 01:47:12-01:49:13; 3 transfer requests b9d1776c/3d80c468/149c8b2d). Prior attempts `a8b84b4` (packet send) and `2d26da8` (setItemSlot) both failed client-side and were superseded by the broadcast fix.
 - Bot death filter fix (USER_ACCEPTED): active plan `20260823-bot-death-filter-fix-v1`, baseline `a8b84b4`, commit `2ef35e5`. Modified `BotManager` to prevent dead Bot from being restored and causing system failure: `saveToWorld()` now saves `Health`; `restoreFromWorld()` checks `savedHealth <= 0.0f` and skips restoration with `clearBot()` log; `onServerStopping()` only saves bots with `getHealth() > 0.0f`. Manual test PASS: user killed Bot, restarted, Bot was cleared (not restored). Server verification: all fixture suites remain PASS at 00:57:53. This short-term fix prevents system failure but does not eliminate death animation (long-term solution requires BotPlayer.die() override and resurrection logic, planned separately).
-- Known limits: fixture path outcomes use transfer-only test seam, not real-world path coverage. Server-side only; does not cover actor disconnect or full manager-owned live tick. Source highlight after interrupt remains user misunderstanding (Alice has no highlight code). Bot death animation (red fall) persists on kill; short-term filter only prevents restoration after restart. Bot inventory client GUI (viewing/interacting with bot inventory via a GUI) is NOT yet implemented — the sync/broadcast fixes make bot held items and inventory data client-visible, but there is no bot inventory panel to open. Bot currently has no GUI entry to view its slots.
-- Next safe step: A1.1 complete and accepted. Bot death animation/resurrection (long-term 方案 C) planned but not started — requires `BotPlayer.die()` override and resurrection logic. Bot inventory GUI (user request: view/interact with bot 36+equipment+mainhand slots via a GUI panel) is a separate future work package. Do not expand into A1.2 fulfilment, persistence recovery writes, generic container support, ACL, packets/UI, or ordinary task integrations without new approved plan.
+- Bot inventory GUI (awaiting client verification): active plan `20260823-bot-inventory-gui-v1`, baseline `f655be2`, HEAD (WSL) `56a05af`/`5f67115` pending review. Added a real `AbstractContainerMenu` (方案 A) over a bot's inventory, opened over the requesting player's real connection via `player.openMenu(modelProvider)` — never `bot.connection.send` (FakeConnection no-op, same lesson as equipment rendering). Files: `gui/ModMenuTypes.java` (DeferredRegister over Registries.MENU), `gui/BotInventoryMenu.java` (36 ordinary + 4 armor bot slots + player grid; read-only task guard via `BotManager.isBusy`), `client/gui/BotInventoryScreen.java` (@OnlyIn server-safe), `client/gui/ClientMenuScreens.java` (MenuScreens.register on FMLClientSetupEvent), `/alice bot-inventory <name>` subcommand (permission-2). Server verification: `BOT_INVENTORY_FIXTURE_SUITE PASS` (slotCount=76 expected=76, busy-flag matches isBusy) and all existing transfer/selection/event/parse suites PASS at 02:06:38. Client G1-G10 (opening, 36 slots, 4 armor, main-hand alignment, empty/stack counts, GUI isolation, bot_unavailable, task read-only guard, idle interaction, no server crash) await Windows testing.
+- Known limits: fixture path outcomes use transfer-only test seam, not real-world path coverage. Server-side only; does not cover actor disconnect or full manager-owned live tick. Source highlight after interrupt remains user misunderstanding (Alice has no highlight code). Bot death animation (red fall) persists on kill; short-term filter only prevents restoration after restart. Bot inventory GUI is implemented server-side (scheme A) but awaits Windows client verification; main-hand slot is not duplicated (the selected ordinary slot is the authority, matching the equipment renderer).
+- Next safe step: A1.1 complete and accepted. Bot death animation/resurrection (long-term 方案 C) planned but not started — requires `BotPlayer.die()` override and resurrection logic. Bot inventory GUI (scheme A) awaits Windows client G1-G10 verification. Do not expand into A1.2 fulfilment, persistence recovery writes, generic container support, ACL, packets/UI, or ordinary task integrations without new approved plan.
 
 ## Transfer Endpoint Selector (awaiting supervisor review)
 
```

## Active Plan Snapshot

```markdown
# Alice Active Work Plan

- Plan ID: `20260823-bot-inventory-gui-v1`
- Status: APPROVED_FOR_IMPLEMENTATION
- Baseline: `f655be2`（当前 HEAD，含 A1.1 全部 + equipment 渲染 + 死亡过滤；实际为 `a6b9f93` docs 提交后）
- User approval: explicit approval received (2026-08-23); user chooses 方案 A（完整交互）+ 任务保护（任务中可读）
- Supervisor route decision: 深度调研 `.alice-supervision/research/bot-inventory-gui-20260823.md`（推荐方案 A：AbstractContainerMenu 完整交互）+ 规划员 `.alice-supervision/research/bot-inventory-gui-planner-20260823.md`（提示交互写入触及任务语义）已交叉审核。用户决策：方案 A + 任务保护（任务执行中 GUI 可读但禁写）。

## Objective

为 Alice Bot 实现独立的 Inventory GUI（方案 A：`AbstractContainerMenu` 完整交互），玩家可查看/拿取/放入 Bot 的 36 普通槽 + 4 装备槽 + 主手槽，并带任务保护（Bot 执行任务时只读可看）。

## Allowed Scope

**新增文件**（`src/main/java/com/dddgn/alice/`）：
1. `gui/BotInventoryMenu.java` — 继承 `AbstractContainerMenu`
   - 注册 Bot 36 普通槽（getItem(0..35)）+ 4 装备槽（getArmor） + 主手（selected）
   - 注册玩家自身 inventory（标准 player 槽）
   - `quickMoveStack()`、`stillValid()`、`removed()`
   - **任务保护**：构造函数/`stillValid` 读取 `BotSession.task != null`；任务执行中进入只读模式（`clicked` 对 bot 槽返回空处理，禁写；`canTakeItem` 返回 false），空闲时完整交互
2. `gui/ModMenuTypes.java` — `DeferredRegister.create(Registries.MENU, "alice")` + 注册 `BOT_INVENTORY_MENU`
3. `client/gui/BotInventoryScreen.java` — `@OnlyIn(Dist.CLIENT)`，`extends AbstractContainerScreen`，客户端 `MenuScreens.register` 绑定

**修改文件**：
4. `gui/BotInventoryMenu` 的打开入口 → 新增 `BotCommand` 子命令 `/alice bot-inventory <botName>`（`requires(hasPermission(2))`）
   - 服务端调用 `player.openMenu(new SimpleMenuProvider((id, inv, p) -> new BotInventoryMenu(id, inv, bot), title))`
   - 注意：packet 经**操作者**真实 connection 发送（`player.openMenu`），不走 `bot.connection.send`（那是 FakeConnection no-op）
5. `AliceMod` 注册 ModMenuTypes + 客户端 MenuScreens（若有 FMLClientSetupEvent）
6. `docs/HANDOVER.md` 记录

## Explicitly Forbidden

- 不改 `TransferTask`/`MineTask`/`ChestBotTransferPrimitive`/`TransferLedgerData`/`BotManager` 的任务语义
- 不修改现有 transfer 的 36 槽权威圈（`InventoryObservation`/`CapacityPreflight`）
- 不把交互写入接入普通任务链（任务中强制只读）
- 不扩展为 Bot 设置页面（留作后续）
- 不实现 Offhand/其他未列装备槽的自由交互（除非在任务保护内明确）
- 不新增客户端 mod 源集（side=BOTH 单 jar，`@OnlyIn`）

## Verification Matrix

**服务端验证**：
- `./gradlew compileJava` PASS
- `git diff --check` PASS
- 既有 fixture 保持 PASS（Transfer/Selection/CommandParse 等）

**服务端 focused fixture**（可选，低优先）：
- 命令 gate（permission-2 拒绝非 op）
- 快照读取 36+4+1、任务中冻结写入
- 若无 bot 返回 `bot_unavailable`

**客户端验证矩阵**（Windows `CLIENT_TEST_PENDING`，G1-G10 参考规划员报告）：
- G1 入口与授权：op 打开 → GUI 显示；非 op 拒绝
- G2 36 普通槽显示（数量正确）
- G3 4 装备槽显示
- G4 主手槽与 equipment 渲染对齐
- G5 空槽/堆叠数量
- G6 GUI 隔离（不影响原版箱子/背包）
- G7 bot 不在场 → `bot_unavailable`
- G8 **任务保护**：Bot 执行 transfer/mine 时打开 GUI → 可读但禁写（点击无效果、不改变 inventory）
- G9 空闲交互：Bot 空闲时拿取/放入/Shift 移动正常
- G10 客户端无服务端崩溃（@OnlyIn 类加载安全）

## Stop Conditions

Stop and return for replanning if implementation requires modifying transfer/task semantics beyond the read-only lock, if task-lock needs to change TransferTask/TransferLedgerData, if @OnlyIn Screen crashes server, or if existing fixtures regress.

## Implementation Time Estimate

- 实现：45-60 分钟（新 Menu + MenuType + Screen + 命令 + 任务保护）
- 客户端验证：20-30 分钟
- 总计：1.5 小时

## Notes

- 关键教训（规划员交叉发现）：GUI 数据须经**操作者**真实 connection（`player.openMenu` / `PacketDistributor.PLAYER.with(actor)`），绝不用 `bot.connection.send`（FakeConnection no-op）。这与设备渲染 `ef43bd6` 的根因相同。
- 任务保护实现建议：`BotInventoryMenu` 持 `Supplier<Boolean> taskActive`（从 `BotManager.BotSession.task != null` 读取）；`stillValid` 始终 true（可打开），但 `clicked`/`quickMoveStack`/`moveItemStackTo` 在 taskActive 时对 bot 槽返回空处理（禁写只读）；玩家自身槽仍可自由操作。
- 若任务保护需要更精细（如只锁 bot 槽不锁玩家槽），在实现时明确区分。
```

## Supervisor checklist

- Does this change preserve the architecture: goal-level decision, deterministic execution, server authority, semantic GUI interfaces?
- Does it preserve the separate boundaries among `HARD_PATH`, experimental `SOFT_SURFACE`, forced road construction, survival monitoring, and future tunnel planning?
- Does it contradict a numbered decision in `docs/HANDOVER.md` or the frozen plan in `docs/PATHING_REFACTOR.md`?
- Are verification evidence, client-test requirements, known limitations, and the next safe step recorded accurately?
- Was `./gradlew compileJava` run and was the committed revision pushed to `origin/master`?
