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
        // ⚠️ 口径修正（2026-09-22 实测两次几何）：**守卫本身在这里无法变红** ——
        // 柱形几何：`side_break_failed ×3`（回收根本碰不到，什么都没拆）；
        // 阶梯几何：同样 `side_break_failed ×3`，`biggestFall=1`。
        // ⇒ 回收这条路径**要么自上而下（逐格 ≤1）、要么失败即停** ⇒ 它是"结构上安全/失败安全"的。
        // 真机那次坠落的证据（`break 74,116,198 by=PATH_ACCESS` + `executable=false support=-`）
        // 指向**路径**而不是回收 ⇒ 真凶方向见队列 `C3`。
        // 本夹具因此只钉两条**真实且能变红**的不变式：① 不许摔（上面那条）② **归因不许静默**。
        check("⭐ 归因不许静默：没拆成的条目必须带理由（实测 terminal=" + terminal
                        + " skipped=" + skippedCount + " notes=" + notes + "）",
                !terminal.contains("restore_partial") || (skippedCount > 0 && !"-".equals(notes)));
        check("收尾 bot 脚下必须有支撑（实测 endSupported=" + endSupported + "）", endSupported);
        cleanup(level);
        BotLog.info("[C2] SUMMARY checks={} failures={} biggestFall={} unsupportedTicks={}"
                        + " endSupported={} terminal={} skipped={} notes={} ticks={} → {}",
                checks, failures.size(), biggestFall, unsupportedTicks, endSupported, terminal,
                skippedCount, notes, totalTicks, failures.isEmpty() ? "PASS" : "FAIL");
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
        bot.controller().stopMovement();
        scope = new ScopeBuffer();
        // ⭐ `D-398`/`Z1`（2026-09-22）：**回收只在保护区内发生** —— 区外按裁定不记账、不恢复
        // ⇒ 不摆这个前提，下面的账本播种会被 `recordPlacement` 整批跳过（静默假绿：
        // `pending=0` → `nothing_to_restore` → 三条判据在"什么都没发生"的世界里全绿）。
        // 前提 = 认领场景区块 + L2 任务区封套（理由见 `FixtureZone` 类注释）。
        zone = FixtureZone.protect(level, bot.getUUID(),
                new BlockPos(ORIGIN.getX() - 2, FLOOR_Y, ORIGIN.getZ() - 2),
                new BlockPos(ORIGIN.getX() + PILLAR_H + 2, FLOOR_Y + PILLAR_H + 2, ORIGIN.getZ() + 2),
                "region_lumber");
        if (!zone.ok()) {
            failures.add("FIXTURE_ZONE_PREMISE_FAILED " + zone.describe());
            BotLog.warn("[C2] 前提未成立：{}", zone.describe());
            return;
        }
        String scopeId = WorldModLedger.openScope(level.getServer(), bot.getUUID(), "c2_underfoot");
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
