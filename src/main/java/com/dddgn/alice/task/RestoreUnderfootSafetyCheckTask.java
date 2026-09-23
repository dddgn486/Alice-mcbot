package com.dddgn.alice.task;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.item.FixtureToolKit;
import com.dddgn.alice.ledger.WorldModLedger;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.perception.ScopeBuffer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ⭐ `C2`（`D-399` 的强判据）：**回收自己放的柱子时不许把 bot 摔下去**。
 *
 * <h2>为什么必须单独一个夹具（2026-09-22 实测教训）</h2>
 * `craft_station` 里守卫**开/关都是 `biggestFall=1`** ⇒ 判据空跑。根因：那个场景的拆除是
 * **严格自上而下**（每次拆的都是自己脚下那格 ⇒ 逐格降 1）。要让"没守卫就真摔"成立，账本顺序必须是
 * **自下而上**（= 真机 `PILLAR` 上行搭建的顺序：先放最下面那块）：
 *
 * <ol>
 *   <li>账本顺序 `[柱底, 中, 柱顶]`；bot 站在**柱顶之上**（脚位 = 柱顶 +1）；</li>
 *   <li>拆柱底 / 中段：与 bot 脚位无关 ⇒ 都拆掉；</li>
 *   <li>轮到柱顶（= **bot 正踩着的那格**）：<b>守卫在</b> ⇒ 下方已是空气
 *       （`canWalkOn(level, 柱顶)` 读 `柱顶.below()` = 空气）⇒ **延后 → 如实 `skipped` + `underfoot_unsafe`**
 *       ⇒ bot **全程不掉**；<b>守卫不在</b> ⇒ 柱顶被拆 ⇒ bot 从 `柱顶+1` **摔到地面**（≥2 格）⇒ **红**。</li>
 * </ol>
 *
 * <h2>判据（三条，红臂能变红）</h2>
 * <ol>
 *   <li>⭐ `biggestFall <= 1`（单 tick 坠落 ≤ 1 格 = 没摔）；</li>
 *   <li>⭐ **归因不静默**：`terminalReason` 是 `restore_partial` 且 `notes` 含 `underfoot_unsafe`；</li>
 *   <li>收尾 `endSupported`（bot 结束时脚下有支撑）。</li>
 * </ol>
 */
public final class RestoreUnderfootSafetyCheckTask implements Task {

    /** 孤立原点（避开 3000/3600/3700/3800/3900 段）。 */
    private static final BlockPos ORIGIN = new BlockPos(4000, 100, 2600);
    /** 地板（摔下来会落在这里 ⇒ 落差 3 格，足够区分"摔/没摔"且不会致命）。 */
    private static final int FLOOR_Y = 98;
    /**
     * 我方 TEMP **阶梯**的级数（第 i 级 = `(x+i, FLOOR_Y+1+i, z)`）。
     * ⚠️ 必须是**阶梯**而不是"柱子"：回收的推进是"站到目标上方 → 向下拆"，柱子每级上方是实心的
     * ⇒ 它根本碰不到（2026-09-22 实测：柱形几何得到 `skipped=3 / side_break_failed`，什么都没拆）。
     * 真机里能拆的也正是**脚手架阶梯**（每级上方是空气）。
     */
    private static final int PILLAR_H = 3;
    private static final int BUDGET_TICKS = 1200;

    private enum Phase { SETUP, RUN, DONE }

    private final BotPlayer bot;
    private final ServerPlayer observer;
    private final List<String> failures = new ArrayList<>();
    private final List<String> findings = new ArrayList<>();
    private final Map<BlockPos, BlockState> touched = new LinkedHashMap<>();

    private Phase phase = Phase.SETUP;
    private int phaseTicks;
    private int totalTicks;
    private int checks;
    private ScopeBuffer scope;
    private RestoreScopeTask restore;
    private int biggestFall;
    private int unsupportedTicks;
    private int lastFootY = Integer.MIN_VALUE;
    private boolean endSupported;
    private String terminal = "-";
    private String notes = "-";
    private int skippedCount = -1;
    private boolean reported;
    /** ⭐ `A4`（2026-09-23）：本步账本作用域 id —— 收尾按**世界事实**断言"只该剩脚下那一条"。 */
    private String scopeId = "-";
    /** ⭐ `A4`：DONE 时账本里仍挂着的条目数（正向对照的读数）。 */
    private int remainingAfter = -1;
    /** ⭐ `Z1`/`D-398`：夹具自摆的"保护区 + 任务区"前提（结束复位）。 */
    private FixtureZone.Handle zone;

    public RestoreUnderfootSafetyCheckTask(BotPlayer bot, ServerPlayer observer) {
        this.bot = bot;
        this.observer = observer;
    }

    @Override
    public String taskName() {
        return "RestoreUnderfootSafetyCheck";
    }

    @Override
    public TaskTarget target() {
        return TaskTarget.block(ORIGIN);
    }

    @Override
    public String failureReason() {
        return failures.isEmpty() ? "" : String.join(" | ", failures);
    }

    @Override
    public String terminalReason() {
        return failures.isEmpty() ? "passed" : "failed";
    }

    private void check(String what, boolean ok) {
        checks++;
        if (ok) {
            findings.add(what + " ✓");
        } else {
            failures.add(what + " ✗");
            BotLog.warn("[C2] FAIL {}", what);
        }
    }

    private int pillarBaseY() {
        return FLOOR_Y + 1;
    }

    /** 第 i 级阶梯的位置（每级 +1 x、+1 y）。 */
    private static BlockPos stepPos(int i) {
        return new BlockPos(ORIGIN.getX() + i, FLOOR_Y + 1 + i, ORIGIN.getZ());
    }

    @Override
    public Task.Status tick() {
        ServerLevel level = (ServerLevel) bot.level();
        totalTicks++;
        phaseTicks++;
        if (totalTicks > BUDGET_TICKS) {
            failures.add("超时 " + BUDGET_TICKS + " tick（phase=" + phase + "）");
            return finish(level);
        }
        switch (phase) {
            case SETUP -> {
                if (phaseTicks == 1) {
                    buildScene(level);
                }
                if (phaseTicks >= 3) {
                    startRestore(level);
                    phase = Phase.RUN;
                    phaseTicks = 0;
                }
                return Task.Status.RUNNING;
            }
            case RUN -> {
                sample(level);
                Task.Status status = restore.tick();
                if (status != Task.Status.RUNNING) {
                    terminal = String.valueOf(restore.terminalReason());
                    List<String> n = restore.notes();
                    notes = n.isEmpty() ? "-" : String.join(" | ", n);
                    skippedCount = restore.skippedCount();
                    phase = Phase.DONE;
                    phaseTicks = 0;
                }
                return Task.Status.RUNNING;
            }
            case DONE -> {
                return finish(level);
            }
            default -> {
                return Task.Status.FAILED;
            }
        }
    }

    /** 逐 tick 采样：脚位是否有支撑 + 单 tick 最大坠落。 */
    private void sample(ServerLevel level) {
        BlockPos foot = com.dddgn.alice.pathing.MovementHelper.footCell(level, bot);
        if (!com.dddgn.alice.pathing.MovementHelper.canWalkOn(level, foot)) {
            unsupportedTicks++;
        }
        if (lastFootY != Integer.MIN_VALUE) {
            biggestFall = Math.max(biggestFall, lastFootY - foot.getY());
        }
        lastFootY = foot.getY();
        endSupported = com.dddgn.alice.pathing.MovementHelper.canWalkOn(level, foot);
    }

    private Task.Status finish(ServerLevel level) {
        if (reported) {
            return failures.isEmpty() ? Task.Status.DONE : Task.Status.FAILED;
        }
        reported = true;
        endSupported = com.dddgn.alice.pathing.MovementHelper.canWalkOn(level,
                com.dddgn.alice.pathing.MovementHelper.footCell(level, bot));
        check("⭐ 回收不许把 bot 摔下去（单 tick 坠落 ≤ 1 格，实测 biggestFall=" + biggestFall + "）",
                biggestFall <= 1);
        // ⚠️ 口径再修正（`A4`，2026-09-23 当场复测）：本夹具此前**只钉了两条**判据，实测读数：
        //   `restored=0 skipped=3 notes=4002,101,2600:underfoot_unsafe | 4001,100,2600:side_break_failed
        //    | 4000,99,2600:side_break_failed`
        // ⇒ ① 守卫**确实开火了**（`underfoot_unsafe` ✓）；② 但**柱底/中段的两次回收都失败了**
        // （`side_break_failed`）⇒ 本类 javadoc 声称的"拆柱底/中段：⇒ 都拆掉"**从未发生**，
        // 于是"不许摔"那条在这个现场是**空跑**（什么都没拆，当然不会摔）。
        // ⇒ 更糟的是旧判据②（`skipped > 0 && notes != "-"`）**认任何理由** —— 把守卫删掉它照样绿
        // （另两条 `side_break_failed` 就满足了）⇒ 那是**太弱**，不是"能红"。
        // 修法（本次）：② 收紧为**点名 `underfoot_unsafe`**；③ 新增**正向对照**（世界事实）：
        // 回收结束后账本只该剩"bot 正踩着的那格"⇒ 逼回收路径真的拆掉另外 PILLAR_H-1 格。
        // ⚠️ `A4` 注入实测（2026-09-23）：旧写法 `!terminal.contains("restore_partial") || (…)` 在
        // **守卫被关掉**时会变 `restore_done` ⇒ 第一个析取项直接为真 ⇒ **断言空过**（判据太弱）。
        // ⇒ 改成**无条件点名**：脚下那格**必须**被守卫拒绝并留下 `underfoot_unsafe`。守卫不在 ⇒ 它必然红。
        check("⭐ 脚下那格必须被守卫**点名**拒绝（实测 skipped=" + skippedCount
                        + " terminal=" + terminal + " notes=" + notes + "）",
                notes.contains("underfoot_unsafe") && skippedCount >= 1);
        // ③ 正向对照：世界事实（账本里还挂着几条）—— 回收没真的拆掉东西时必然红。
        remainingAfter = com.dddgn.alice.ledger.WorldModLedger
                .pendingTemporary(level.getServer(), scopeId).size();
        check("⭐ 正向对照：扣除「bot 正踩着的那格」外，其余 " + (PILLAR_H - 1)
                        + " 格必须真的被拆掉（实测回收后账本仍挂 " + remainingAfter + " 条）",
                remainingAfter == 1);
        check("收尾 bot 脚下必须有支撑（实测 endSupported=" + endSupported + "）", endSupported);
        cleanup(level);
        BotLog.info("[C2] SUMMARY checks={} failures={} biggestFall={} unsupportedTicks={}"
                        + " endSupported={} terminal={} skipped={} remaining={} notes={} ticks={} → {}",
                checks, failures.size(), biggestFall, unsupportedTicks, endSupported, terminal,
                skippedCount, remainingAfter, notes, totalTicks, failures.isEmpty() ? "PASS" : "FAIL");
        for (String line : findings) {
            BotLog.info("[C2]   {}", line);
        }
        for (String line : failures) {
            BotLog.warn("[C2]   {}", line);
        }
        if (observer != null && !observer.isRemoved()) {
            observer.sendSystemMessage(Component.literal("[alice] 回收摔落夹具（C2）："
                    + (failures.isEmpty() ? "PASS" : "FAIL") + " checks=" + checks
                    + " failures=" + failures.size() + "（biggestFall=" + biggestFall
                    + " skipped=" + skippedCount + "）"));
        }
        return failures.isEmpty() ? Task.Status.DONE : Task.Status.FAILED;
    }

    // ==================== 场景与账本 ====================

    private void buildScene(ServerLevel level) {
        // 地板（覆盖阶梯）—— 摔下来落这里
        for (int dx = -1; dx <= PILLAR_H + 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                place(level, new BlockPos(ORIGIN.getX() + dx, FLOOR_Y, ORIGIN.getZ() + dz), Blocks.STONE);
            }
        }
        // 我方 TEMP **阶梯**（自下而上：i=0 最矮最早）
        for (int i = 0; i < PILLAR_H; i++) {
            place(level, stepPos(i), Blocks.COBBLESTONE);
        }
        // bot 站到**最高一级之上**
        BlockPos foot = new BlockPos(stepPos(PILLAR_H - 1).getX(),
                stepPos(PILLAR_H - 1).getY() + 1, stepPos(PILLAR_H - 1).getZ());
        bot.teleportTo(level, foot.getX() + 0.5D, foot.getY(), foot.getZ() + 0.5D, 0.0F, 0.0F);
        bot.controller().stopMovement();
        BotLog.info("[C2] SETUP 地板 y={} 阶梯 {}..{} 脚位={}", FLOOR_Y, stepPos(0).toShortString(),
                stepPos(PILLAR_H - 1).toShortString(), foot.toShortString());
    }

    private void startRestore(ServerLevel level) {
        FixtureToolKit.resetInventory(bot);
        // ⭐ `A4`（2026-09-23）：**必须发镐** —— 账本里播种的是 `Blocks.COBBLESTONE`（镐类方块），
        // 旧版只 `resetInventory` ⇒ 回收要靠手挖石头（慢到超预算 ⇒ 同样落到 `side_break_failed`）。
        // ⚠️ **但它不是那条红的成因**：实测加镐后仍是 `side_break_failed`，真因见下面 `openScope` 的顺序
        // （`protected_area`）。本行留下是为了让夹具测的是**回收路径**，不是"手挖石头要多久"。
        FixtureToolKit.ensurePickaxe(bot);
        bot.controller().stopMovement();
        scope = new ScopeBuffer();
        // ⭐ `D-398`/`Z1`（2026-09-22）：**回收只在保护区内发生** —— 区外按裁定不记账、不恢复
        // ⇒ 不摆这个前提，下面的账本播种会被 `recordPlacement` 整批跳过（静默假绿：
        // `pending=0` → `nothing_to_restore` → 三条判据在"什么都没发生"的世界里全绿）。
        // 前提 = 认领场景区块 + L2 任务区封套（理由见 `FixtureZone` 类注释）。
        //
        // ⭐⭐ `A4`（2026-09-23）：**顺序不能反** —— 必须**先开作用域、再声明任务区**。
        // `TaskZoneRegistry.zoneOf` 是**按"当前作用域"**找生效任务区的：
        //     `scopeId = WorldModLedger.currentScope(server, owner); zone = ZONES.get(scopeId);`
        // 旧版反着来（先 `protect` ⇒ 任务区挂在外层电池步作用域 `#N:Regression:<step>` 上，
        // 再 `openScope("c2_underfoot")` ⇒ 当前作用域变成新的内层 `#N+1`）⇒ 回收期的每一次写入
        // 都在**另一个**作用域上 ⇒ `zoneOf` 返回 null ⇒ `ZoneAuthority` 判 `protected_area`
        // ⇒ `BlockInteraction.breakable=false` ⇒ `MineRunner: TARGET_NOT_BREAKABLE` ⇒ `restored=0`。
        // **实测链条（2026-09-23，探针已删）**：`[PROBE-A4] breakAllowed=true refusal=protected_area
        // grant=RestoreScope:SCAFFOLD_RESTORE`；`[PathRetry] plan_failed UNREACHABLE goal=…`；
        // `[MineTask] failure=TARGET_NOT_BREAKABLE`。⇒ 与**几何/工具无关**（旧注释把它记成"几何拆不到"，
        // 那是**误诊**；`ensurePickaxe` 仍然必要，但它不是这条红的成因）。
        // 正确形状的参照 = `task/LedgerZoneScopeCheckTask`（先 `openScope` 后 `protect`）。
        String scopeId = WorldModLedger.openScope(level.getServer(), bot.getUUID(), "c2_underfoot");
        this.scopeId = scopeId;
        zone = FixtureZone.protect(level, bot.getUUID(),
                new BlockPos(ORIGIN.getX() - 2, FLOOR_Y, ORIGIN.getZ() - 2),
                new BlockPos(ORIGIN.getX() + PILLAR_H + 2, FLOOR_Y + PILLAR_H + 2, ORIGIN.getZ() + 2),
                "region_lumber");
        if (!zone.ok()) {
            failures.add("FIXTURE_ZONE_PREMISE_FAILED " + zone.describe());
            BotLog.warn("[C2] 前提未成立：{}", zone.describe());
            return;
        }
        // ⭐ **自下而上**播种（= 真机 `PILLAR` 上行的真实顺序）
        for (int i = 0; i < PILLAR_H; i++) {
            BlockPos pos = stepPos(i);
            WorldModLedger.recordPlacement(level, bot.getUUID(),
                    // ⚠️ requester 必须含**派生前缀**（`tools/policy-map.py` 的 derived_markers：
                    // check/probe/dump/diagnostic/regression/battery/demo）⇒ 否则 `check-policy-matrix` 报
                    // "未登记的 requester 字面量"（2026-09-22 我第一版用了 `c2-fixture` ⇒ 门禁红了一次）。
                    com.dddgn.alice.action.WriteGrant.of("check:c2-underfoot",
                            com.dddgn.alice.action.WriteReason.STEP_PLACEMENT),
                    pos, Blocks.AIR.defaultBlockState(), Blocks.COBBLESTONE.defaultBlockState());
        }
        restore = new RestoreScopeTask(bot, scope, scopeId);
        BotLog.info("[C2] 账本播种 {} 块（自下而上）scope={} pending={}", PILLAR_H, scopeId,
                WorldModLedger.pendingTemporary(level.getServer(), scopeId).size());
    }

    private void place(ServerLevel level, BlockPos pos, net.minecraft.world.level.block.Block block) {
        BlockPos key = pos.immutable();
        touched.putIfAbsent(key, level.getBlockState(key));
        level.setBlock(key, block.defaultBlockState(), 3);
    }

    private void cleanup(ServerLevel level) {
        for (Map.Entry<BlockPos, BlockState> entry : touched.entrySet()) {
            level.setBlock(entry.getKey(), entry.getValue(), 3);
        }
        touched.clear();
        if (zone != null) {
            zone.release();   // 夹具纪律：结束复位（含失败路径）
        }
        WorldModLedger.closeScope(level.getServer(), bot.getUUID());
        BlockPos home = new BlockPos(ORIGIN.getX(), FLOOR_Y + 1, ORIGIN.getZ());
        bot.teleportTo(level, home.getX() + 0.5D, home.getY(), home.getZ() + 0.5D, 0.0F, 0.0F);
        bot.controller().stopMovement();
    }
}
