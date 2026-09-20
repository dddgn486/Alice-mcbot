package com.dddgn.alice.task;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.item.FixtureToolKit;
import com.dddgn.alice.job.lumber.LumberCandidateSource;
import com.dddgn.alice.job.lumber.LumberRegionState;
import com.dddgn.alice.job.lumber.RegionLumberJob;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.perception.ScopeBuffer;
import com.dddgn.alice.job.policy.NearestPolicy;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * **`MAINTAIN` 的"不可维持"判据**（`D-349` / 勘测侧 Pit 2 的现场取证）：
 * 常驻区域作业**不许"看起来在跑、其实终态已不可达"**。
 *
 * <h2>它钉住的是哪一条事实</h2>
 * 旧实现把"区内**无树 ∧ 无苗 ∧ 不欠树**"这个事实**已经算出来了**（就是 `idle-stop` 的判据），
 * 但**只在 `idle-stop=true` 时**才用它 ⇒ 默认模式（常驻）下这个事实**没人知道**：
 * 玩家把区域清成石头、或砍光不再补种之后，Job 一直退避巡查，**没有任何判据会说**。
 * ⇒ 修复（`D-349`）＝ 与模式无关地**如实登记并上报**（一次性 warn + 事件环 + 告知创建者 + 给出"可做什么"），
 * 并且**恢复后自动清除**；**不擅自收工**（用户 2026-09-12 裁定：常驻只由玩家/决策层显式打断）。
 *
 * <h2>判据（四条，缺一不可）</h2>
 * <table border="1">
 *   <tr><th></th><th>判据</th><th>期望</th><th>旧实现</th></tr>
 *   <tr><td>①</td><td><b>前提</b>：场景真的是"无树无苗不欠树"（自建空盒 + `baseline=0` + 背包无苗）</td>
 *       <td>真</td><td>真</td></tr>
 *   <tr><td>②</td><td>⭐ <b>触发</b>：{@link RegionLumberJob#maintainUnreachable()} 变真**且**上报了"可做什么"</td>
 *       <td>真（≤{@link #TRIGGER_CAP_TICKS} tick）</td><td><b>假 ⇒ 判红</b></td></tr>
 *   <tr><td>③</td><td>⭐ <b>不越权</b>：此刻 Job **仍是 `RUNNING`**（没有擅自收工）</td>
 *       <td>真</td><td>真</td></tr>
 *   <tr><td>④</td><td>⭐ <b>恢复</b>：注入树苗 + `baseline>0` ⇒ 标记**清除**且真的补种成功</td>
 *       <td>真（≤{@link #RECOVER_CAP_TICKS} tick）</td><td>真（清除逻辑本身是新增，删掉它也红）</td></tr>
 * </table>
 *
 * <h2>几何前提（§6.9.1 ①：盒以谁为中心、多大、种子放哪 —— 写下来并自断言）</h2>
 * <ul>
 *   <li>原点 {@link #ORIGIN}（x 3800、z 2000、y 100 空中 —— 与 3200/3400/3600 的三个夹具**错开**）；</li>
 *   <li>地板 = **草方块**（y+0，整块铺满）：不只要有地方站，**补种需要可种植的地面**（石头种不了苗）；</li>
 *   <li>区域 = 地板范围本身（`baseY` = 脚位 y+1，`maxHeight` 8 ⇒ 扫描窗口 `[baseY-2, baseY+8]`）；</li>
 *   <li>盒内**没有任何原木/树叶/树苗**（自断言 ⇒ "无树"这个前提不是假设）；</li>
 *   <li>bot 起点 = 盒中心（站在草方块上）。</li>
 * </ul>
 *
 * <h2>副作用边界（§6.9.2）</h2>
 * 不调 {@code assign*}/{@code beginTask}（自己 new Job + 自己 tick）；区域状态
 * （`baselineTrees` / `baselineDerived` / 我种的苗**账本**）**先存后还原**——它是**跨夹具共享的会话状态**，
 * 不清干净会把后续 `region_maintain` 的"欠树"算错；自建地形**收尾清回空气 + 撤销 forceload**，
 * 落物全部 discard，bot 回传送前位置。
 */
public final class RegionMaintainUnmaintainableCheckTask implements Task {

    /** 盒原点（y=100 空中；**全新一片** ⇒ 与其它夹具互不干扰）。 */
    private static final BlockPos ORIGIN = new BlockPos(3800, 100, 2000);

    private static final int LANE_LENGTH = 16;
    private static final int LANE_HALF_WIDTH = 8;
    private static final int HEADROOM = 6;

    /** 地板层相对原点的 y 偏移（脚位 = +1）。 */
    private static final int FOOT_DY = 1;

    /** 巡查间隔（与 `region_maintain` 同值；`IDLE_PATROLS=3` ⇒ 约 60~120 tick 进入待机分支）。 */
    private static final int PATROL_INTERVAL_TICKS = 20;

    private static final int TRIGGER_CAP_TICKS = 400;
    private static final int RECOVER_CAP_TICKS = 400;
    private static final int BUDGET_TICKS = 1200;

    /** 恢复阶段注入的欠树棵数（>0 ⇒ 有活可做）。 */
    private static final int RECOVER_BASELINE = 3;

    private enum Phase { SETUP, TRIGGER, RECOVER, ASSERT, DONE }

    private final BotPlayer bot;
    private final ServerPlayer observer;
    private final ScopeBuffer scope;

    private Phase phase = Phase.SETUP;
    private int ticks;
    private int phaseTicks;
    private int checks;
    private final List<String> failures = new ArrayList<>();

    private RegionLumberJob job;
    private LumberRegionState.Region region;

    // ---- 前提 ----
    private BlockPos entryFoot;
    private boolean built;
    private boolean emptyNoTrees;
    private boolean noSaplingsInInventory;
    private boolean idleStopOff;
    private boolean toolReady;

    // ---- 区域状态存档（跨夹具共享 ⇒ 必须还原）----
    private BlockPos plantSpot;
    private boolean spotValid;

    private int savedBaseline;
    private boolean savedBaselineDerived;
    private String savedSaplingItem;
    private List<BlockPos> savedMySaplings = List.of();

    // ---- 观察 ----
    private int triggeredAtTick = -1;
    private String remediation = "";
    private boolean stillRunningWhenTriggered;
    private int recoveredAtTick = -1;
    private boolean plantedSomething;

    public RegionMaintainUnmaintainableCheckTask(BotPlayer bot, ServerPlayer observer, ScopeBuffer scope) {
        this.bot = bot;
        this.observer = observer;
        this.scope = scope;
    }

    @Override
    public String taskName() {
        return "RegionMaintainUnmaintainableCheck";
    }

    @Override
    public TaskTarget target() {
        return TaskTarget.block(ORIGIN.offset(LANE_LENGTH / 2, FOOT_DY, 0));
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
            case TRIGGER -> trigger();
            case RECOVER -> recover();
            case ASSERT -> assertResult();
            case DONE -> failures.isEmpty() ? Task.Status.DONE : Task.Status.FAILED;
        };
    }

    // ==================== SETUP ====================

    private Task.Status setup() {
        ServerLevel level = bot.serverLevel();
        forceload(level, true);

        // 地板：**草方块**（补种需要可种植的地面）+ 上方空气
        for (int x = 0; x <= LANE_LENGTH; x++) {
            for (int z = -LANE_HALF_WIDTH; z <= LANE_HALF_WIDTH; z++) {
                level.setBlock(ORIGIN.offset(x, 0, z), Blocks.GRASS_BLOCK.defaultBlockState(), 3);
                for (int dy = FOOT_DY; dy <= HEADROOM; dy++) {
                    level.setBlock(ORIGIN.offset(x, dy, z), Blocks.AIR.defaultBlockState(), 3);
                }
            }
        }
        built = level.getBlockState(ORIGIN).is(Blocks.GRASS_BLOCK)
                && level.getBlockState(ORIGIN.offset(LANE_LENGTH, 0, LANE_HALF_WIDTH)).is(Blocks.GRASS_BLOCK);

        region = new LumberRegionState.Region(
                ORIGIN.getX(), ORIGIN.getZ() - LANE_HALF_WIDTH,
                ORIGIN.getX() + LANE_LENGTH, ORIGIN.getZ() + LANE_HALF_WIDTH,
                ORIGIN.getY() + FOOT_DY, HEADROOM + 2);
        emptyNoTrees = countLogs(level) == 0 && countLeaves(level) == 0;

        entryFoot = bot.blockPosition();
        teleport(level, ORIGIN.offset(LANE_LENGTH / 2, FOOT_DY, 0));

        // ⚠️ 区域状态是**跨夹具共享的会话状态**：先存档，收尾还原（否则会算错别人的"欠树"）
        LumberRegionState state = LumberRegionState.get(level.getServer());
        savedBaseline = state.baselineTrees(bot.getUUID());
        savedBaselineDerived = state.baselineDerived(bot.getUUID());
        savedSaplingItem = state.saplingItem(bot.getUUID());
        savedMySaplings = state.mySaplings(bot.getUUID());

        // 清干净"我的苗"账本 + 空背包 + **不发苗**（前提：无苗）
        for (BlockPos planted : savedMySaplings) {
            state.forgetSapling(bot.getUUID(), planted);
        }
        FixtureToolKit.resetInventory(bot);
        noSaplingsInInventory = state.mySaplingCount(bot.getUUID()) == 0;

        // `baseline = 0`（= 这块区域本来就没有树）⇒ `deficit = 0` ⇒ 落在"无树无苗不欠树"上
        state.setBaselineTrees(bot.getUUID(), 0);
        state.setBaselineDerived(bot.getUUID(), true);
        // ⚠️ **不得**打开 `idle-stop`：默认常驻是本夹具要验的语义（收工只由玩家/决策层打断）
        idleStopOff = !state.autoIdleStop(bot.getUUID());

        FixtureToolKit.ensurePickaxe(bot);
        toolReady = !bot.getMainHandItem().isEmpty();

        job = new RegionLumberJob(bot, region, scope, new LumberCandidateSource(),
                new NearestPolicy(), PATROL_INTERVAL_TICKS, BUDGET_TICKS);
        BotLog.info("[RegionUnmaint] CHECK setup origin={} region={} built={} 无树={} 无苗={} idleStop关={}"
                        + " baseline={}(derived={})",
                ORIGIN.toShortString(), region.describe(), built, emptyNoTrees, noSaplingsInInventory,
                idleStopOff, 0, true);
        phase = Phase.TRIGGER;
        return Task.Status.RUNNING;
    }

    // ==================== TRIGGER：跑真 Job，等"不可维持"被登记 ====================

    private Task.Status trigger() {
        phaseTicks++;
        Task.Status status = job.tick();
        if (job.maintainUnreachable()) {
            triggeredAtTick = phaseTicks;
            remediation = job.maintainRemediation();
            stillRunningWhenTriggered = status == Task.Status.RUNNING;
            BotLog.info("[RegionUnmaint] CHECK **不可维持已登记**：第 {} tick，Job 状态={}（未擅自收工={}）"
                            + " 上报的可做什么=「{}」",
                    triggeredAtTick, status, stillRunningWhenTriggered, remediation);
            // 恢复阶段：注入"欠树 + 手里有苗" ⇒ 有活可做
            LumberRegionState state = LumberRegionState.get(bot.serverLevel().getServer());
            state.setSaplingItem(bot.getUUID(), "minecraft:oak_sapling");
            state.setBaselineTrees(bot.getUUID(), RECOVER_BASELINE);
            // ⚠️ 补种点的**唯一出处**是 `RegionLumberJob.plantSpotFor`：它只认
            // `LumberRegionState.pendingReplant` 里记着的点（"树桩空出来"），**不是**"随便一块草地"。
            // 首跑就是漏了这一条 ⇒ 日志 `欠树 deficit=3 但当前没有可补种的位置` ⇒ 恢复阶段假红。
            // 点选在**紧邻 bot 站位**（不必走路 ⇒ 不依赖寻路在夹具里能不能跑）。
            plantSpot = ORIGIN.offset(LANE_LENGTH / 2, FOOT_DY, 1);
            spotValid = bot.serverLevel().getBlockState(plantSpot).isAir()
                    && bot.serverLevel().getBlockState(plantSpot.below())
                        .is(net.minecraft.tags.BlockTags.DIRT);
            state.addPendingReplant(bot.getUUID(), plantSpot);
            FixtureToolKit.ensureHotbarStack(bot, () -> new ItemStack(Items.OAK_SAPLING),
                    stack -> stack.is(Items.OAK_SAPLING), 8, "sapling");
            BotLog.info("[RegionUnmaint] CHECK 注入恢复条件：baseline={} + 手里 {} 个树苗",
                    RECOVER_BASELINE, bot.getInventory().countItem(Items.OAK_SAPLING));
            phase = Phase.RECOVER;
            phaseTicks = 0;
            return Task.Status.RUNNING;
        }
        if (phaseTicks > TRIGGER_CAP_TICKS) {
            BotLog.warn("[RegionUnmaint] CHECK 等到上限 {} tick 仍未登记不可维持（Job 状态={}）",
                    TRIGGER_CAP_TICKS, status);
            phase = Phase.ASSERT;
        }
        return Task.Status.RUNNING;
    }

    // ==================== RECOVER：有活可做 ⇒ 标记必须清除 ====================

    private Task.Status recover() {
        phaseTicks++;
        Task.Status status = job.tick();
        plantedSomething = job.plantedSomething();
        if (!job.maintainUnreachable() && plantedSomething) {
            recoveredAtTick = phaseTicks;
            BotLog.info("[RegionUnmaint] CHECK **恢复可维持**：第 {} tick，标记已清除 + 真的补种成功"
                            + "（treesChopped={} planted={}）", recoveredAtTick, job.treesChopped(),
                    plantedSomething);
            phase = Phase.ASSERT;
            return Task.Status.RUNNING;
        }
        if (phaseTicks > RECOVER_CAP_TICKS) {
            BotLog.warn("[RegionUnmaint] CHECK 恢复阶段到上限 {} tick（标记={} 补种={} 状态={}）",
                    RECOVER_CAP_TICKS, job.maintainUnreachable(), plantedSomething, status);
            phase = Phase.ASSERT;
        }
        return Task.Status.RUNNING;
    }

    // ==================== ASSERT ====================

    private Task.Status assertResult() {
        ServerLevel level = bot.serverLevel();
        LumberRegionState state = LumberRegionState.get(level.getServer());

        // ---- 前提（红了先怀疑夹具）----
        check("前提：草方块地板铺好了（built=" + built + "）", built);
        check("前提：盒内**真的没有**原木/树叶（无树=" + emptyNoTrees + "）", emptyNoTrees);
        check("前提：我名下**没有**在长的苗（无苗=" + noSaplingsInInventory + "）", noSaplingsInInventory);
        check("前提：`idle-stop` 是**关**的（常驻语义才是有意为之；关=" + idleStopOff + "）", idleStopOff);
        check("前提：主手不为空（否则补种阶段判不出来是夹具问题）", toolReady);
        check("前提：恢复用的补种点有效（空气 + 下方属 `#minecraft:dirt`）｜spot="
                + (plantSpot == null ? "(未设)" : plantSpot.toShortString()) + " valid=" + spotValid, spotValid);

        // ---- ② 触发 ----
        check("⭐ 触发：`maintainUnreachable()` 在 " + TRIGGER_CAP_TICKS + " tick 内变真（实际第 "
                + triggeredAtTick + " tick）—— 旧实现只在 `idle-stop=true` 时才用这个判据 ⇒ 默认模式**恒为假** ⇒ 判红",
                triggeredAtTick > 0);
        check("⭐ 上报：给出了**可做什么**（非空 = 真的登记并告知了，不只是内部算了一下）｜remediation="
                + (remediation.isEmpty() ? "(空)" : remediation), !remediation.isEmpty());

        // ---- ③ 不越权 ----
        check("⭐ 不越权：登记不可维持的那一刻 Job **仍是 RUNNING**（收工只由玩家/决策层打断）",
                stillRunningWhenTriggered);

        // ---- ④ 恢复 ----
        check("⭐ 恢复：注入「欠树 + 有苗」后标记**清除**且真的补种成功（清除@第 " + recoveredAtTick
                + " tick，补种=" + plantedSomething + "）", recoveredAtTick > 0 && plantedSomething);

        // ---- 收尾（失败路径也走，§6.9.2）----
        // ⚠️ 本夹具**自己 new + 自己 tick**（没经编排器注册）⇒ 收尾只要**不再 tick** 即可，
        // 不需要也没有"取消任务"的 API（别去 `BotManager` 里找一个不存在的入口）。
        for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, box())) {
            item.discard();
        }
        clearBox(level);
        forceload(level, false);
        for (BlockPos planted : state.mySaplings(bot.getUUID())) {
            state.forgetSapling(bot.getUUID(), planted);         // 清掉本轮种下的
        }
        if (plantSpot != null) {
            state.removePendingReplant(bot.getUUID(), plantSpot);   // 只撤本夹具加的那一个
        }
        for (BlockPos planted : savedMySaplings) {
            state.addMySapling(bot.getUUID(), planted);          // 还原跨夹具共享状态
        }
        state.setBaselineTrees(bot.getUUID(), savedBaseline);
        state.setBaselineDerived(bot.getUUID(), savedBaselineDerived);
        if (savedSaplingItem != null) {
            state.setSaplingItem(bot.getUUID(), savedSaplingItem);
        }
        bot.controller().stopMovement();
        if (entryFoot != null) {
            teleport(level, entryFoot);
        }
        check("收尾：自建地形清回空气（非空气格=" + (boxIsAir(level) ? 0 : countNonAir(level)) + "）", boxIsAir(level));
        check("收尾：区域状态已还原（baseline=" + state.baselineTrees(bot.getUUID()) + " derived="
                        + state.baselineDerived(bot.getUUID()) + " mySaplings=" + state.mySaplingCount(bot.getUUID())
                        + "）",
                state.baselineTrees(bot.getUUID()) == savedBaseline
                        && state.baselineDerived(bot.getUUID()) == savedBaselineDerived
                        && state.mySaplingCount(bot.getUUID()) == savedMySaplings.size());

        boolean pass = failures.isEmpty();
        BotLog.info("[RegionUnmaint] SUMMARY checks={} failures={} 触发@{}tick 恢复@{}tick 补种={} → {}｜失败项：{}",
                checks, failures.size(), triggeredAtTick, recoveredAtTick, plantedSomething,
                pass ? "PASS" : "FAIL", failures);
        if (observer != null && !observer.isRemoved()) {
            observer.sendSystemMessage(Component.literal("[alice] 区域不可维持取证 " + (pass ? "PASS" : "FAIL")
                    + "（触发@" + triggeredAtTick + "，恢复@" + recoveredAtTick + "，详见日志 [RegionUnmaint]）"));
        }
        phase = Phase.DONE;
        return failures.isEmpty() ? Task.Status.DONE : Task.Status.FAILED;
    }

    // ==================== 工具 ====================

    private AABB box() {
        return new AABB(ORIGIN.getX(), ORIGIN.getY(), ORIGIN.getZ() - LANE_HALF_WIDTH - 1,
                ORIGIN.getX() + LANE_LENGTH + 2, ORIGIN.getY() + HEADROOM + 2,
                ORIGIN.getZ() + LANE_HALF_WIDTH + 2);
    }

    private int countLogs(ServerLevel level) {
        int total = 0;
        for (int x = 0; x <= LANE_LENGTH; x++) {
            for (int z = -LANE_HALF_WIDTH; z <= LANE_HALF_WIDTH; z++) {
                for (int dy = FOOT_DY; dy <= HEADROOM; dy++) {
                    if (level.getBlockState(ORIGIN.offset(x, dy, z)).is(net.minecraft.tags.BlockTags.LOGS)) {
                        total++;
                    }
                }
            }
        }
        return total;
    }

    private int countLeaves(ServerLevel level) {
        int total = 0;
        for (int x = 0; x <= LANE_LENGTH; x++) {
            for (int z = -LANE_HALF_WIDTH; z <= LANE_HALF_WIDTH; z++) {
                for (int dy = FOOT_DY; dy <= HEADROOM; dy++) {
                    if (level.getBlockState(ORIGIN.offset(x, dy, z)).is(net.minecraft.tags.BlockTags.LEAVES)) {
                        total++;
                    }
                }
            }
        }
        return total;
    }

    private void forceload(ServerLevel level, boolean on) {
        for (int cx = ORIGIN.getX() >> 4; cx <= (ORIGIN.getX() + LANE_LENGTH) >> 4; cx++) {
            for (int cz = (ORIGIN.getZ() - LANE_HALF_WIDTH) >> 4; cz <= (ORIGIN.getZ() + LANE_HALF_WIDTH) >> 4; cz++) {
                level.setChunkForced(cx, cz, on);
            }
        }
    }

    private void clearBox(ServerLevel level) {
        for (int x = 0; x <= LANE_LENGTH; x++) {
            for (int z = -LANE_HALF_WIDTH; z <= LANE_HALF_WIDTH; z++) {
                for (int dy = 0; dy <= HEADROOM; dy++) {
                    level.setBlock(ORIGIN.offset(x, dy, z), Blocks.AIR.defaultBlockState(), 3);
                }
            }
        }
    }

    private boolean boxIsAir(ServerLevel level) {
        for (int x = 0; x <= LANE_LENGTH; x++) {
            for (int z = -LANE_HALF_WIDTH; z <= LANE_HALF_WIDTH; z++) {
                for (int dy = 0; dy <= HEADROOM; dy++) {
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
                for (int dy = 0; dy <= HEADROOM; dy++) {
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
