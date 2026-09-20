package com.dddgn.alice.task;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.perception.ScopeBuffer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * **"登记被推迟"的现场取证（`D-348` 的判据夹具）**：证明"掉落物在 tick 末还没被登记进世界"时
 * **不许当场丢弃** —— 它在**宽限窗口**内会出现（`ScopeBuffer.PENDING_GRACE_TICKS`）。
 *
 * <h2>它钉住的是哪一条事实（`D-348` 附注一的实测）</h2>
 * <ol>
 *   <li>`EntityJoinLevelEvent` 是在 `PersistentEntitySectionManager` **把实体登记进查找表之前**发出的
 *       （探针调用栈：`PersistentEntitySectionManager:79 → EventBus`）⇒ **事件当刻 `level.getEntity(id)` 必然是 null**；</li>
 *   <li>正常情形到 tick 末就登记好了，但**登记可能被推迟 1~19 tick**
 *       （实测：真实挖掘掉落物 1~4 tick；刚 forceload 的区块里 summon 19 tick）⇒
 *       **旧实现"tick 末判一次就永久丢弃"会把真的会进世界的掉落物丢掉**（收集器永远看不到它）；</li>
 *   <li>修复 = 有界宽限窗口复验（`D-348` 修复段）。</li>
 * </ol>
 *
 * <h2>怎么做到**确定性**（这是本夹具的要点）</h2>
 * 复现条件 = **在刚 forceload 的新鲜区块里，同一 tick 内建地形 + 破坏方块**（掉落物生成时该区块的
 * 实体区块还没就绪 ⇒ 登记被推到后续 tick）。为此本夹具的 SETUP **全部在一个 tick 里做完**：
 * forceload → 建地板/矿 → 传送 → `scope.begin(...)` → **真的破坏那格矿**（走与生产同一个原语
 * `level.destroyBlock(pos, true, bot)`）⇒ 掉落物排队、tick 末确认时必然还查不到。
 *
 * <h2>判据（两条，缺一不可）</h2>
 * <table border="1">
 *   <tr><th>判据</th><th>期望</th><th>旧实现（无宽限窗口）</th></tr>
 *   <tr><td><b>前提</b>：本轮**确实**观察到了推迟（`deferredEnteredCount &gt; 0`）</td>
 *       <td>真</td><td>真（推迟照样发生，只是当场被丢）</td></tr>
 *   <tr><td>⭐ <b>期望</b>：该掉落物**最终被登记进作用域**（`liveDrops()` 里能看到它）</td>
 *       <td>真</td><td><b>假 ⇒ 判红</b>（被 tick 末那一次判定丢掉）</td></tr>
 * </table>
 * ⚠️ **反向对照**：把 `PENDING_GRACE_TICKS` 注入成 `0`（等价于旧行为）⇒ 前提判据与期望判据**同时红**
 * —— 这正是"这条判据能咬人"的证明。
 *
 * <h2>几何前提（§6.9.1 ①：盒子以谁为中心、多大、种子放哪 —— 写下来并自断言）</h2>
 * <ul>
 *   <li>原点 {@link #ORIGIN}（x 3600、z 2000、y 100 的空中 —— 与 `mine_far_drop`(3200)、
 *       `mine_run_metrics`(3400) 都错开，且**没有别的夹具用过** ⇒ "新鲜区块"这一前提才成立）；</li>
 *   <li>垫层 y+0（石头，整块铺满）＋行走层 y+1（石头），矿嵌在行走层 {@link #ORE_OFFSET_X} 处
 *       （z={@link #ORE_Z}，正下方是垫层 ⇒ 掉落物掉不进虚空）；</li>
 *   <li>作用域 = 以**矿那一格**为心、半径 {@link #SCOPE_RADIUS}（掉落物必在窗口内）；</li>
 *   <li>bot 起点 = {@code ORIGIN.offset(0, 2, 0)}（站在行走层上，**不站在矿上**）。</li>
 * </ul>
 *
 * <h2>副作用边界（§6.9.2）</h2>
 * 不调 {@code assign*}/{@code beginTask}；自建地形**收尾清回空气 + 撤销 forceload**，
 * 盒内落物**全部 discard**（失败路径也走），bot 回传送前的位置。
 */
public final class ScopePendingGraceCheckTask implements Task {

    /** 走廊原点（y=100 的空中；**全新一片** ⇒ "新鲜区块"是前提）。 */
    private static final BlockPos ORIGIN = new BlockPos(3600, 100, 2000);

    /** 地板范围（x 0..N）。 */
    private static final int LANE_LENGTH = 6;

    /** 走廊半宽（z ±N）。 */
    private static final int LANE_HALF_WIDTH = 2;

    /** 行走层上方留的空气层数。 */
    private static final int HEADROOM = 3;

    /** 矿所在格的 x 偏移 / z。 */
    private static final int ORE_OFFSET_X = 3;
    private static final int ORE_Z = 0;

    /** 行走层相对原点的 y 偏移（垫层 = +0，行走层 = +1，脚位 = +2）。 */
    private static final int WALK_LAYER_DY = 1;
    private static final int FOOT_DY = 2;

    /** 作用域半径（掉落物必在窗口内；与生产同数量级）。 */
    private static final int SCOPE_RADIUS = 8;

    /**
     * 等"推迟登记最终落地"的上限。
     *
     * <p>取 `ScopeBuffer.PENDING_GRACE_TICKS(40) + 20` ⇒ **只要窗口内出现就一定等得到**；
     * 窗口已用完仍不出现 ⇒ 如实判红（那说明这次不是"推迟"而是"真的没进世界"）。
     */
    private static final int WATCH_CAP_TICKS = 60;

    /** 夹具护栏。 */
    private static final int BUDGET_TICKS = 400;

    private enum Phase { SETUP, WATCH, ASSERT, DONE }

    private final BotPlayer bot;
    private final ServerPlayer observer;
    private final ScopeBuffer scope;

    private Phase phase = Phase.SETUP;
    private int ticks;
    private int watchTicks;
    private int checks;
    private final List<String> failures = new ArrayList<>();

    // ---- 前提（SETUP 记、ASSERT 自证）----
    private BlockPos entryFoot;
    private BlockPos orePos;
    private boolean built;
    private boolean oreWasOre;
    /** 前提：主手是镐（工具不对 ⇒ `playerDestroy` 不掉落 ⇒ 本夹具会以"前提未复现"红）。 */
    private boolean toolReady;
    private boolean oreNowAir;
    private int deferredBefore;

    // ---- 观察 ----
    private int registeredAtTick = -1;      // 第几 tick 看到它（-1 = 一直没看到）
    private int deferredEntered = -1;
    private long maxDeferTicks = -1L;
    private int observedDrops;

    public ScopePendingGraceCheckTask(BotPlayer bot, ServerPlayer observer, ScopeBuffer scope) {
        this.bot = bot;
        this.observer = observer;
        this.scope = scope;
    }

    @Override
    public String taskName() {
        return "ScopePendingGraceCheck";
    }

    @Override
    public TaskTarget target() {
        return TaskTarget.block(ORIGIN.offset(ORE_OFFSET_X, WALK_LAYER_DY, ORE_Z));
    }

    @Override
    public String failureReason() {
        return String.join(" | ", failures);
    }

    @Override
    public String terminalReason() {
        return failures.isEmpty() ? "passed" : "failed";
    }

    @Override
    public Task.Status tick() {
        if (++ticks > BUDGET_TICKS && phase != Phase.ASSERT && phase != Phase.DONE) {
            check("夹具护栏：" + BUDGET_TICKS + " tick 内必须跑完（实际 " + ticks + "）", false);
            phase = Phase.ASSERT;
        }
        return switch (phase) {
            case SETUP -> setup();
            case WATCH -> watch();
            case ASSERT -> assertResult();
            case DONE -> failures.isEmpty() ? Task.Status.DONE : Task.Status.FAILED;
        };
    }

    // ==================== SETUP（**全部在同一个 tick 里**：这是"登记被推迟"的复现条件）====================

    private Task.Status setup() {
        ServerLevel level = bot.serverLevel();
        // ① forceload 先做：**未加载区块里写方块是静默无效的**
        forceload(level, true);

        // ② 垫层 + 行走层 + 空气（矿嵌在行走层 ⇒ 掉落物落在地板上，进不了虚空）
        for (int x = 0; x <= LANE_LENGTH; x++) {
            for (int z = -LANE_HALF_WIDTH; z <= LANE_HALF_WIDTH; z++) {
                level.setBlock(ORIGIN.offset(x, 0, z), Blocks.STONE.defaultBlockState(), 3);
                level.setBlock(ORIGIN.offset(x, WALK_LAYER_DY, z), Blocks.STONE.defaultBlockState(), 3);
                for (int dy = WALK_LAYER_DY + 1; dy <= WALK_LAYER_DY + HEADROOM; dy++) {
                    level.setBlock(ORIGIN.offset(x, dy, z), Blocks.AIR.defaultBlockState(), 3);
                }
            }
        }
        orePos = ORIGIN.offset(ORE_OFFSET_X, WALK_LAYER_DY, ORE_Z);
        level.setBlock(orePos, Blocks.IRON_ORE.defaultBlockState(), 3);
        built = level.getBlockState(ORIGIN).is(Blocks.STONE)
                && level.getBlockState(ORIGIN.offset(LANE_LENGTH, 0, 0)).is(Blocks.STONE)
                && level.getBlockState(ORIGIN.offset(ORE_OFFSET_X, 0, ORE_Z)).is(Blocks.STONE);   // 垫层前提
        oreWasOre = level.getBlockState(orePos).is(Blocks.IRON_ORE);

        // ③ 传送（玩家 ticket 帮忙加载）+ 记入口脚位（收尾复位用）
        entryFoot = bot.blockPosition();
        teleport(level, ORIGIN.offset(0, FOOT_DY, 0));

        // ④ 作用域先开（`begin` 会清登记表 ⇒ 必须在破坏之前）
        scope.begin(orePos, SCOPE_RADIUS, bot.getUUID());
        deferredBefore = scope.deferredEnteredCount();

        // ④b ⚠️ **必须手持正确的工具**：`gameMode.destroyBlock` 会走 `playerDestroy`，
        //    工具不对（徒手挖铁矿）⇒ **一格掉落物都不会生成** ⇒ 夹具会以"前提未复现"红
        //    （首跑就是这么红的：`推迟计数=0`、`liveDrops()=0`，看着像产品坏了，其实是夹具没发镐）。
        com.dddgn.alice.item.FixtureToolKit.resetInventory(bot);
        com.dddgn.alice.item.FixtureToolKit.ensurePickaxe(bot);
        // ⭐ **夹具自己准备前提**（2026-09-20 `full` 实测踩到）：`ensurePickaxe` 只把镐放进**快捷栏空格（slot=0）**，
        // **不选中**它；而本夹具读的是 `getMainHandItem()`（= **选中槽**）。工艺/机器模块的夹具会合法地把选中槽
        // 挪到别的槽（`MachineCycle:445`/`StationProvision:161`/`CraftFurnaceCheckTask:162`）⇒ 在 `full` 里
        // 主手读到 `air`、前提**未复现**红；而**单跑**（bot 的选中槽还是 0）是绿的 —— 这类"只在整链里红"的
        // 现场极难复盘。⇒ 明确选中 slot 0 并广播主手，前提不再依赖前一步的残留。
        bot.getInventory().selected = 0;
        com.dddgn.alice.bot.BotManager.syncMainHand(bot);
        toolReady = bot.getMainHandItem().is(net.minecraft.world.item.Items.STONE_PICKAXE)
                || bot.getMainHandItem().is(net.minecraft.world.item.Items.IRON_PICKAXE)
                || bot.getMainHandItem().is(net.minecraft.world.item.Items.DIAMOND_PICKAXE)
                || bot.getMainHandItem().is(net.minecraft.world.item.Items.NETHERITE_PICKAXE)
                || bot.getMainHandItem().is(net.minecraft.world.item.Items.GOLDEN_PICKAXE)
                || bot.getMainHandItem().is(net.minecraft.world.item.Items.WOODEN_PICKAXE);

        // ⑤ ⭐ **同一 tick 内真的破坏那格矿** —— 原语**必须与生产挖掘相同**：
        //    `bot.gameMode.destroyBlock(pos)`（= `BlockBreakSession:106` 用的那个）⇒ **会触发
        //    `BlockEvent.BreakEvent`** ⇒ 破坏记录进 `recentBreaks` ⇒ 掉落物才能配对成我方。
        //    ⚠️ 首跑用错了原语（`level.destroyBlock`）——它**不触发 Forge 破坏事件**（`BlockInteraction:532`
        //    的注释早就写过这件事）⇒ 归属恒为 `unpaired` ⇒ 夹具在"归属"那一格假红。
        //    这就是 `alice-scene-based-testing` §6.9.1 ③ 的同族坑：**夹具必须落在真正被测的那一层**。
        boolean destroyed = bot.gameMode.destroyBlock(orePos);
        oreNowAir = level.getBlockState(orePos).isAir();

        BotLog.info("[ScopeGrace] CHECK setup origin={} 矿={} destroyed={} 现在=air:{} 作用域(center={} r={})"
                        + " 已推迟计数(前)={} —— 全部在**同一个 tick**里做完（复现条件）",
                ORIGIN.toShortString(), orePos.toShortString(), destroyed, oreNowAir,
                orePos.toShortString(), SCOPE_RADIUS, deferredBefore);
        phase = Phase.WATCH;
        return Task.Status.RUNNING;
    }

    // ==================== WATCH：等"推迟登记"落地 ====================

    private Task.Status watch() {
        watchTicks++;
        observedDrops = scope.liveDrops().size();
        if (observedDrops > 0 && registeredAtTick < 0) {
            registeredAtTick = watchTicks;
            deferredEntered = scope.deferredEnteredCount() - deferredBefore;
            maxDeferTicks = scope.maxDeferTicks();
            BotLog.info("[ScopeGrace] CHECK 掉落物**最终被登记**：watchTick={} 件数={} 本轮推迟计数={}"
                            + " 实测最大登记延迟={} tick（窗口={}）",
                    watchTicks, observedDrops, deferredEntered, maxDeferTicks,
                    graceWindowTicks());
            phase = Phase.ASSERT;
            return Task.Status.RUNNING;
        }
        if (watchTicks > WATCH_CAP_TICKS) {
            deferredEntered = scope.deferredEnteredCount() - deferredBefore;
            maxDeferTicks = scope.maxDeferTicks();
            BotLog.warn("[ScopeGrace] CHECK 等到上限 {} tick 仍未登记（推迟计数={} 最大延迟={}）",
                    WATCH_CAP_TICKS, deferredEntered, maxDeferTicks);
            phase = Phase.ASSERT;
        }
        return Task.Status.RUNNING;
    }

    /** 宽限窗口值（夹具不复制常量：直接从生产类读，避免两处漂移）。 */
    private static int graceWindowTicks() {
        try {
            var field = ScopeBuffer.class.getDeclaredField("PENDING_GRACE_TICKS");
            field.setAccessible(true);
            return field.getInt(null);
        } catch (ReflectiveOperationException e) {
            return -1;      // 读不到就如实报 -1（下面的前提判据会红），不假装
        }
    }

    // ==================== ASSERT ====================

    private Task.Status assertResult() {
        ServerLevel level = bot.serverLevel();

        // ---- ① 现场前提（红了先怀疑夹具，而不是产品）----
        check("前提：垫层 + 行走层已落地（built=" + built + "）", built);
        check("前提：破坏前那一格**确实是铁矿**（oreWasOre=" + oreWasOre + "）", oreWasOre);
        check("前提：那一格**真的被破坏**了（destroyed 后是空气=" + oreNowAir + "）", oreNowAir);
        check("前提：主手是**镐**（工具不对 ⇒ `playerDestroy` 不掉落 ⇒ 本夹具测不到任何东西；实际="
                + bot.getMainHandItem().getItem() + "）", toolReady);

        // ---- ② ⭐ 前提：本轮**确实**观察到了"登记被推迟"（否则本夹具没测到宽限窗口 ⇒ 不许算绿）----
        check("⭐ 前提：本轮**确实**出现了登记推迟（`deferredEnteredCount` 增量=" + deferredEntered
                        + " > 0）—— 否则「tick 末还没进世界」这个现场没复现，本夹具等于什么都没测"
                        + "（窗口=" + graceWindowTicks() + " tick）",
                deferredEntered > 0);

        // ---- ③ ⭐ 期望：推迟的掉落物**最终被登记进作用域**（旧实现会在这里红）----
        check("⭐ 期望：那件掉落物**最终被登记**（`liveDrops()` 件数=" + observedDrops
                        + "，第 " + registeredAtTick + " 个 watch tick 看到；实测最大登记延迟="
                        + maxDeferTicks + " tick）—— 旧实现「tick 末判一次就丢弃」⇒ 这里恒为 0 ⇒ 判红",
                observedDrops > 0 && registeredAtTick > 0);

        // ---- ④ 收尾（**失败路径也走**，§6.9.2）----
        clearBoxItems(level);
        clearBox(level);
        forceload(level, false);
        bot.controller().stopMovement();
        if (entryFoot != null) {
            teleport(level, entryFoot);
        }
        check("收尾：夹具造出来的落物全部收回（盒内剩余=" + countBoxItems(level) + "）", countBoxItems(level) == 0);
        check("收尾：自建地形清回空气 ⇒ 这片区域与其它场景依旧不相连（非空气格="
                + (boxIsAir(level) ? 0 : countNonAir(level)) + "）", boxIsAir(level));

        boolean pass = failures.isEmpty();
        BotLog.info("[ScopeGrace] SUMMARY checks={} failures={} 推迟计数增量={} 实测最大延迟={}tick 窗口={}tick"
                        + " 登记延迟(观测)={} watchTicks={} → {}｜失败项：{}",
                checks, failures.size(), deferredEntered, maxDeferTicks, graceWindowTicks(),
                registeredAtTick, watchTicks, pass ? "PASS" : "FAIL", failures);
        if (observer != null && !observer.isRemoved()) {
            observer.sendSystemMessage(Component.literal("[alice] 登记推迟取证 " + (pass ? "PASS" : "FAIL")
                    + "（推迟=" + deferredEntered + "，延迟=" + maxDeferTicks + " tick，详见日志 [ScopeGrace]）"));
        }
        phase = Phase.DONE;
        return failures.isEmpty() ? Task.Status.DONE : Task.Status.FAILED;
    }

    // ==================== 工具 ====================

    private void forceload(ServerLevel level, boolean on) {
        int minZ = ORIGIN.getZ() - LANE_HALF_WIDTH;
        int maxZ = ORIGIN.getZ() + LANE_HALF_WIDTH;
        for (int cx = ORIGIN.getX() >> 4; cx <= (ORIGIN.getX() + LANE_LENGTH) >> 4; cx++) {
            for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++) {
                level.setChunkForced(cx, cz, on);
            }
        }
    }

    /** 盒子 = 走廊本体（**与建场景、收尾用的是同一个**：§6.9.1 ① 基线盒 = 结束盒）。 */
    private AABB box() {
        return new AABB(ORIGIN.getX(), ORIGIN.getY(), ORIGIN.getZ() - LANE_HALF_WIDTH,
                ORIGIN.getX() + LANE_LENGTH + 1, ORIGIN.getY() + WALK_LAYER_DY + HEADROOM + 1,
                ORIGIN.getZ() + LANE_HALF_WIDTH + 1);
    }

    private int countBoxItems(ServerLevel level) {
        int total = 0;
        for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, box())) {
            total += item.getItem().getCount();
        }
        return total;
    }

    private void clearBoxItems(ServerLevel level) {
        for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, box())) {
            item.discard();
        }
    }

    private void clearBox(ServerLevel level) {
        for (int x = 0; x <= LANE_LENGTH; x++) {
            for (int z = -LANE_HALF_WIDTH; z <= LANE_HALF_WIDTH; z++) {
                for (int dy = 0; dy <= WALK_LAYER_DY + HEADROOM; dy++) {
                    level.setBlock(ORIGIN.offset(x, dy, z), Blocks.AIR.defaultBlockState(), 3);
                }
            }
        }
    }

    private boolean boxIsAir(ServerLevel level) {
        for (int x = 0; x <= LANE_LENGTH; x++) {
            for (int z = -LANE_HALF_WIDTH; z <= LANE_HALF_WIDTH; z++) {
                for (int dy = 0; dy <= WALK_LAYER_DY + HEADROOM; dy++) {
                    if (!level.getBlockState(ORIGIN.offset(x, dy, z)).isAir()) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private int countNonAir(ServerLevel level) {
        int total = 0;
        for (int x = 0; x <= LANE_LENGTH; x++) {
            for (int z = -LANE_HALF_WIDTH; z <= LANE_HALF_WIDTH; z++) {
                for (int dy = 0; dy <= WALK_LAYER_DY + HEADROOM; dy++) {
                    if (!level.getBlockState(ORIGIN.offset(x, dy, z)).isAir()) {
                        total++;
                    }
                }
            }
        }
        return total;
    }

    private void teleport(ServerLevel level, BlockPos foot) {
        bot.teleportTo(level, foot.getX() + 0.5D, foot.getY(), foot.getZ() + 0.5D,
                Set.of(), bot.getYRot(), bot.getXRot());
        bot.setDeltaMovement(Vec3.ZERO);
        bot.controller().stopMovement();
    }

    private void check(String what, boolean ok) {
        checks++;
        if (!ok) {
            failures.add(what);
        }
    }
}
