package com.dddgn.alice.task;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.item.FixtureToolKit;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.perception.ScopeBuffer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * ⭐ **「到位却够不到」⇒ 换格再试**（电池步 `collect_offcenter_retry`；3-b 的 `D0`+`D2` 判据）。
 *
 * <h2>它钉住的两件事（一句话各自）</h2>
 * <ol>
 *   <li><b>`D2`（行为）</b>：bot 站在"模型说够得着"的格上、而**真实盒子够不到**时，
 *       收集器**不许连着两次都选同一格**然后如实退休 —— 必须把这一格记进本簇排除集、
 *       换**次优**格，真的把东西捡回来。</li>
 *   <li><b>`D0`（取证）</b>：`approach_probe` 探的层/环必须**等于**搜索（`pickupGoalFor`）用的层/环
 *       —— 第八轮真机那份读数（`standable=0`，而 bot **正站在** `dy=-1` 层的可站格上）就是因为
 *       探针只扫 `dy=0`、而搜索扫 `dy ∈ {0,-1}`。</li>
 * </ol>
 *
 * <h2>真机事实（`docs/reviews/2026-09-21-掉落物在洞里被瞬退.md` §12②/§13.1）</h2>
 * <ul>
 *   <li>第七轮：bot 停在格内偏 <b>0.19</b> 处、物品停在自己那格的**远角** ⇒ 4 簇各被同一件物品
 *       反复聚簇 ≈ 5 秒，最后 `not_in_pickup_range` 退休，物品留在地上（12/93 件）；</li>
 *   <li>第八轮：bot 离心 <b>0.49</b>、物品压在格角（格内偏移 **0.875**）⇒ 探针打出
 *       `standable=0 standableAndReachable=0`，**漏掉了 bot 自己站着的那一格**（`dy=-1` 层）。</li>
 *   <li>定量上界：模型对「bot 站正在格中心」乐观 ⇒ 偏差上界 ≈ `0.49 + 0.375 ≈ 0.87`，
 *       而 `withinPickupReach` 的逐轴上界是 `0.125 + 0.3 + 1.0 = 1.425` ⇒ 存在一整段
 *       「**模型接受、执行期判否**」的几何。</li>
 * </ul>
 *
 * <h2>场景形状（几何全部自断言；§6.9.1 ①）</h2>
 * <pre>
 *   空中孤岛：地板 y=ORIGIN.y（x ∈ [-4,4]、z ∈ [-4,4]），上方留 4 层空气（与其它场景不相连）
 *   物品格  FLOOR(0,0) + 1    →  头顶 (0,1,0)+1 放一块石头 ⇒ **物品格站不住**（1 格高夹缝）
 *   物品落点 物品格内 x 偏移 0.9、z 偏移 0.5  ⇒ 压在格的 **+x 侧**
 *   bot     **就站在**物品格西邻 (-1,0) 里，但**离心 0.49**（x = ORIGIN.x − 0.99）
 *   ⇒ 模型（`withinPickupReach` 用"格中心"造盒子）：西邻到物品逐轴 **1.4 ≤ 1.425** ⇒ **够得着**
 *   ⇒ 真实（`reachesFrom(bot 的真实包围盒)`）：逐轴 **1.89 > 1.425** ⇒ **够不到**
 *   ⇒ 旧行为：`reanchor` 又算出**同一个西邻** ⇒ 两轮后退休 `not_in_pickup_range`，物品留地上
 *   ⇒ 新行为：西邻进排除集 ⇒ 次优（西北/正北邻格）⇒ 走过去 ⇒ 进包
 * </pre>
 *
 * <h2>它断言哪一层（技能 §6.9.1 ③）</h2>
 * <b>动作层</b>——真世界里一个真的 {@code CollectDropsTask}，被本夹具**每 tick 驱动一 tick**
 * （与 `collect_slot_approach` / `mine_far_drop` 同一个手法），世界修改授权 = `true`
 * （= 挖掘/回收作业的真实口径）⇒ "零挖掘"是真的行为约束。
 *
 * <h2>副作用边界（§6.9.2）</h2>
 * 不调 {@code assign*}/{@code beginTask} ⇒ 自己持有并 tick 一个 {@code CollectDropsTask}；
 * 造出来的地形收尾清回空气、撤销 forceload、造出来的落物全部 discard、bot 回传送前的位置。
 */
public final class CollectOffcenterRetryCheckTask implements Task {

    /** 场景原点（y=100 的空中；其它夹具不用 z=2600 这一片，见各夹具的 ORIGIN 注释）。 */
    private static final BlockPos ORIGIN = new BlockPos(3400, 100, 2600);

    /** 地板范围（相对 ORIGIN）。 */
    private static final int FLOOR_HALF_X = 4;
    private static final int FLOOR_HALF_Z = 4;
    /** 脚位/物品层与天花层（相对 ORIGIN 的 y 偏移）。 */
    private static final int FOOT_DY = 1;
    private static final int CEIL_DY = 2;
    private static final int HEADROOM = 4;

    /** 物品格（相对 ORIGIN 的脚位层格）。 */
    private static final int ITEM_DX = 0;
    private static final int ITEM_DZ = 0;
    /**
     * ⭐ 物品在格内的 **x 偏移**：`0.9` ⇒ 西邻格（格中心 x = −0.5）到物品逐轴 **1.4**
     * —— 贴着 `withinPickupReach` 的逐轴上界 `1.425`，**差 0.025**（旧粗判 1.2 会否掉它，
     * 本夹具用的就是这段区间）。
     */
    private static final double ITEM_OFFSET_X = 0.9D;
    private static final double ITEM_OFFSET_Z = 0.5D;

    /** bot 的**离心**落点（相对西邻格中心的偏差；真机实测 0.19~0.49）。 */
    private static final double BOT_OFFSET_X = 0.49D;
    /** 模型/执行两边的逐轴上界（`0.125 + 0.3 + 1.0`）—— 只用来刻画场景形状与断言区间。 */
    private static final double REACH_UPPER_BOUND = 1.425D;

    /** 产物物品：**本夹具独有**（与 `collect_slot_approach` 同一个理由：别污染别人的背包增量）。 */
    private static final String PRODUCT = "minecraft:amethyst_shard";
    /** 收集器自带的总预算（`CollectDropsTask.DEFAULT_TOTAL_BUDGET_TICKS`，照抄值）。 */
    private static final int COLLECTOR_BUDGET_TICKS = 600;
    /** 作用域半径（覆盖整个场景）。 */
    private static final int SCOPE_RADIUS = 12;
    /** 本步预算。 */
    private static final int BUDGET_TICKS = 500;
    /** 等落物落地/可见的 tick 上限。 */
    private static final int SUMMON_CAP = 60;
    /** 收集器该在多少 tick 内结束（护栏）。 */
    private static final int RUN_CAP = 300;

    private enum Phase { SETUP, SPAWN, SETTLE, RUN, ASSERT, DONE }

    private final BotPlayer bot;
    private final ServerPlayer observer;
    private final ScopeBuffer scope;

    private Phase phase = Phase.SETUP;
    private int ticks;
    private int settleTicks;
    private int runTicks;
    private final List<String> failures = new ArrayList<>();
    private int checks;
    private String setupFailure = "";

    // ---- 场景（建/拆对称）----
    private final Map<BlockPos, BlockState> probe = new LinkedHashMap<>();
    private BlockPos entryFoot;
    private int builtBlocks;
    private int preExistingNonAir = -1;

    // ---- 运行状态 ----
    private ItemEntity item;
    private UUID itemId;
    private CollectDropsTask collector;
    private Task.Status collectorStatus = Task.Status.DONE;
    private int inventoryBefore;
    private Map<BlockPos, BlockState> worldBefore = Map.of();
    private int worldChanges;
    private int collectedDelta;
    private boolean itemStillOnGround;
    private String itemReason = "";
    /** 运行期观测：bot 是否**始终**没进入真实拾取范围（真的复现了"模型说够得着、真实够不到"）。 */
    private boolean everInActualRangeWhileGoalWasSelf;
    private int pinnedCellTicks;

    // ---- 跑之前取的几何前提 ----
    private boolean premiseItemCellStandable;
    private boolean premiseSelfCellStandable;
    private boolean premiseModelReachFromBotCell;
    private boolean premiseActualMissFromBot;
    private double premiseModelAxisDistance = -1;
    private double premiseActualAxisDistance = -1;

    public CollectOffcenterRetryCheckTask(BotPlayer bot, ServerPlayer observer, ScopeBuffer scope) {
        this.bot = bot;
        this.observer = observer;
        this.scope = scope;
    }

    @Override
    public String taskName() {
        return "CollectOffcenterRetryCheck";
    }

    @Override
    public TaskTarget target() {
        return TaskTarget.block(ORIGIN.above(FOOT_DY));
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
            check("夹具护栏：" + BUDGET_TICKS + " tick 内必须跑完（实际 " + ticks + "，阶段=" + phase + "）", false);
            phase = Phase.ASSERT;
        }
        return switch (phase) {
            case SETUP -> setup();
            case SPAWN -> spawn();
            case SETTLE -> settle();
            case RUN -> run();
            case ASSERT -> assertCase();
            case DONE -> failures.isEmpty() ? Task.Status.DONE : Task.Status.FAILED;
        };
    }

    // ==================== SETUP ====================

    private Task.Status setup() {
        ServerLevel level = bot.serverLevel();
        forceload(level, true);

        preExistingNonAir = 0;
        for (int dx = -FLOOR_HALF_X; dx <= FLOOR_HALF_X; dx++) {
            for (int dz = -FLOOR_HALF_Z; dz <= FLOOR_HALF_Z; dz++) {
                for (int dy = 0; dy <= CEIL_DY; dy++) {
                    if (!level.getBlockState(ORIGIN.offset(dx, dy, dz)).isAir()) {
                        preExistingNonAir++;
                    }
                }
            }
        }
        if (preExistingNonAir != 0) {
            setupFailure = "SCENE_NOT_AIR(空中平台位置本来有 " + preExistingNonAir + " 个非空气方块)";
            check("硬前提失败：" + setupFailure + " ⇒ 本步的其余判据都不成立", false);
            phase = Phase.DONE;
            return Task.Status.RUNNING;
        }

        // ① 地板 + 上方留空气
        for (int dx = -FLOOR_HALF_X; dx <= FLOOR_HALF_X; dx++) {
            for (int dz = -FLOOR_HALF_Z; dz <= FLOOR_HALF_Z; dz++) {
                set(level, dx, 0, dz, Blocks.STONE);
                for (int dy = 1; dy <= HEADROOM; dy++) {
                    set(level, dx, dy, dz, Blocks.AIR);
                }
            }
        }
        // ② 物品格头顶那一块（1 格高夹缝 ⇒ 物品格**站不住**）
        set(level, ITEM_DX, CEIL_DY, ITEM_DZ, Blocks.STONE);
        builtBlocks = probe.size();

        if (!level.getBlockState(ORIGIN.offset(0, 0, 0)).is(Blocks.STONE)) {
            setupFailure = "TERRAIN_NOT_BUILT(空中平台没落地)";
        }

        // ③ 传送（玩家 ticket 会同步加载区块）+ 记下入口脚位（收尾复位用）
        entryFoot = bot.blockPosition();
        teleportFoot(level, ORIGIN.offset(-FLOOR_HALF_X, FOOT_DY, 0));

        FixtureToolKit.resetInventory(bot);
        check("前提：清背包后没有本夹具的产物（实际 " + countProductInInventory() + "）",
                countProductInInventory() == 0);

        BotLog.info("[CollectOffcenter] CHECK setup origin={} 地板方块={} 原本非空气={} setupFailure={}",
                ORIGIN.toShortString(), builtBlocks, preExistingNonAir,
                setupFailure.isEmpty() ? "-" : setupFailure);
        if (!setupFailure.isEmpty()) {
            phase = Phase.ASSERT;
            return Task.Status.RUNNING;
        }
        phase = Phase.SPAWN;
        return Task.Status.RUNNING;
    }

    // ==================== SPAWN → SETTLE → RUN ====================

    private Task.Status spawn() {
        ServerLevel level = bot.serverLevel();
        scope.begin(ORIGIN, SCOPE_RADIUS, bot.getUUID());
        boolean spawned = summonProduct(level, itemCell(), ITEM_OFFSET_X, ITEM_OFFSET_Z);
        inventoryBefore = countProductInInventory();
        settleTicks = 0;
        BotLog.info("[CollectOffcenter] CHECK spawn item={} itemCell={} 背包起始产物={}",
                spawned, itemCell().toShortString(), inventoryBefore);
        if (!spawned) {
            check("硬前提失败：落物没造出来（`/summon` 返回 0）", false);
            phase = Phase.ASSERT;
            return Task.Status.RUNNING;
        }
        phase = Phase.SETTLE;
        return Task.Status.RUNNING;
    }

    private Task.Status settle() {
        ServerLevel level = bot.serverLevel();
        settleTicks++;
        item = visibleItem(level);
        if (item != null && (item.onGround() || settleTicks > SUMMON_CAP)) {
            itemId = item.getUUID();
            boolean ours = scope.registerAsOurs(item, com.dddgn.alice.decision.DropPolicy.Provenance.OURS_DIRECT,
                    item.blockPosition());
            if (!ours) {
                check("硬前提失败：落物没能登记成我方（`registerAsOurs` 返回 false）", false);
                phase = Phase.ASSERT;
                return Task.Status.RUNNING;
            }
            // ⭐ 落点必须还在预期格（物品落地时不该横向漂走）
            check("前提：落物落在预期格 " + itemCell().toShortString() + "（实际 "
                            + item.blockPosition().toShortString() + "）",
                    item.blockPosition().equals(itemCell()));
            // ⭐ **本夹具的核心几何**：bot 站进"模型说够得着"的那一格，但**离心**站着
            teleportToSelfCellOffCenter(level);
            capturePremises(level);
            worldBefore = snapshot(level);
            BotLog.info("[CollectOffcenter] CHECK 就位 itemPos={} itemBox={} botPos={} botBox={}"
                            + " 模型够得着={} 真实够不到={} settled={} tick={}",
                    item.blockPosition().toShortString(), fmtBox(item.getBoundingBox()),
                    String.format(java.util.Locale.ROOT, "%.3f/%.3f", bot.getX(), bot.getZ()),
                    fmtBox(bot.getBoundingBox()), premiseModelReachFromBotCell, premiseActualMissFromBot,
                    item.onGround(), settleTicks);
            phase = Phase.RUN;
            return Task.Status.RUNNING;
        }
        if (settleTicks > SUMMON_CAP * 2) {
            check("硬前提失败：落物等了 " + settleTicks + " tick 仍不可见/未落地", false);
            phase = Phase.ASSERT;
        }
        return Task.Status.RUNNING;
    }

    private Task.Status run() {
        runTicks++;
        if (collector == null) {
            // 世界修改授权 = true（= 生产里挖掘/回收作业的真实口径，`D-372`）
            collector = new CollectDropsTask(bot, ORIGIN, scope, List.of(), true,
                    COLLECTOR_BUDGET_TICKS, com.dddgn.alice.task.mining.MiningProfile.STANDABLE_ONLY,
                    this::liveItem);
        }
        // 观测：只要目标还是"bot 自己那一格"，真实拾取范围就**不许**成立（否则本夹具没复现出偏差）
        if (collector.goalExcludedTotal() == 0) {
            pinnedCellTicks++;
            if (item != null && !item.isRemoved()
                    && CollectDropsTask.reachesFrom(bot.getBoundingBox(), item)) {
                everInActualRangeWhileGoalWasSelf = true;
            }
        }
        collectorStatus = collector.tick();
        if (collectorStatus != Task.Status.RUNNING) {
            phase = Phase.ASSERT;
            return Task.Status.RUNNING;
        }
        if (runTicks > RUN_CAP) {
            check("夹具护栏：收集器跑了 " + runTicks + " tick 还没结束（reason="
                    + collector.terminalReason() + "）", false);
            phase = Phase.ASSERT;
        }
        return Task.Status.RUNNING;
    }

    // ==================== ASSERT ====================

    private Task.Status assertCase() {
        if (!setupFailure.isEmpty()) {
            cleanup();
            phase = Phase.DONE;
            return Task.Status.RUNNING;
        }
        ServerLevel level = bot.serverLevel();
        worldChanges = countChanges(level, worldBefore);
        collectedDelta = countProductInInventory() - inventoryBefore;
        itemStillOnGround = itemId != null && entityAlive(level, itemId);
        itemReason = collector == null ? "" : collector.terminalReason();

        // ---- ⭐ 几何前提（红了 = 夹具坏，不是缺陷证据）----
        check("前提：物品格**站不住**（1 格高夹缝；实际可站=" + premiseItemCellStandable + "）",
                !premiseItemCellStandable);
        check("前提：bot 站的西邻格**可站**（实际 " + premiseSelfCellStandable + "）", premiseSelfCellStandable);
        check("前提：⭐ **模型**说够得着（复用生产谓词 `withinPickupReach`，逐轴 " + fmt(premiseModelAxisDistance)
                        + " ≤ " + REACH_UPPER_BOUND + "）—— 这就是「乐观」的那一侧",
                premiseModelReachFromBotCell);
        check("前提：⭐ **真实盒子**够不到（复用生产谓词 `reachesFrom`，逐轴 " + fmt(premiseActualAxisDistance)
                        + " > " + REACH_UPPER_BOUND + "）—— 这就是真机那一幕",
                premiseActualMissFromBot);
        check("前提：运行期 bot **始终**没进入真实拾取范围（否则本夹具没复现出偏差；离心段 "
                        + pinnedCellTicks + " tick）",
                pinnedCellTicks > 0 && !everInActualRangeWhileGoalWasSelf);

        // ---- ⭐ `D0`：探针的层/环 = 搜索的层/环 ----
        if (item != null && collector != null) {
            check("⭐ `D0`：候选枚举**含两层**（`dy=0` + `dy=-1`）—— 环 1 应 8×2=16 格（实际 "
                            + CollectDropsTask.approachCandidates(itemCell(), 1).size()
                            + "）—— 第八轮真机那份误导读数（`standable=0`）正是因为探针只扫 `dy=0`",
                    CollectDropsTask.approachCandidates(itemCell(), 1).size() == 16);
            CollectDropsTask.ApproachReading reading = collector.approachReading(item);
            check("⭐ `D0`：探针读数里**必须**有 `dy=-1` 层里那格「够得着」的记录 `(1,-1,0)stand=0/reach=1`"
                            + "（= 真机里 bot 自己站着的那一层；旧探针根本打不出这条）",
                    reading.ring().contains("(1,-1,0)stand=0/reach=1"));
            check("⭐ `D0`：层计数自洽（可站 " + reading.standable() + " = `dy=0` " + reading.standableDy0()
                            + " + `dy=-1` " + reading.standableDyMinus1() + "）",
                    reading.standable() == reading.standableDy0() + reading.standableDyMinus1());
            BotLog.info("[CollectOffcenter] PROBE standable={} standableAndReachable={} dy0={} dy-1={} ring={}",
                    reading.standable(), reading.standableAndReachable(), reading.standableDy0(),
                    reading.standableDyMinus1(), reading.ring());
        } else {
            check("⭐ `D0`：探针读数拿不到（item/collector 为空）⇒ 本组判据不成立", false);
        }

        // ---- ⭐ `D2`：换格再试必须真的把东西捡回来 ----
        check("⭐ `D2`：**排除了至少一个「站上去也够不到」的格**（实际 goal_excluded="
                        + (collector == null ? -1 : collector.goalExcludedTotal()) + "）——"
                        + "旧行为是 `reanchor` 又算出同一格、两轮后退休，物品留在地上",
                collector != null && collector.goalExcludedTotal() >= 1);
        check("⭐ `D2`：**物品真的进包了**（期望 1，实际 " + collectedDelta + "；itemReason="
                        + itemReason + "）—— 次优格必须真的够得着", collectedDelta == 1);
        check("⭐ `D2`：物品**不再留在地上**（实际 stillOnGround=" + itemStillOnGround + "）",
                !itemStillOnGround);
        check("⭐ `D2`：**没有**把「没有可站格」当成结论（no_approach="
                        + (collector == null ? -1 : collector.noApproachRetired()) + "）——"
                        + "本场景有可站格，够不到是几何问题，不是「不许规划」",
                collector != null && collector.noApproachRetired() == 0);
        check("⭐ 世界**零改动**（实际 " + worldChanges + " 格）—— 换格走位就够了，不许为捡一件东西挖地形",
                worldChanges == 0);

        cleanup();
        phase = Phase.DONE;
        return Task.Status.RUNNING;
    }

    private void capturePremises(ServerLevel level) {
        premiseItemCellStandable = canStand(level, itemCell());
        BlockPos selfCell = bot.blockPosition().immutable();
        premiseSelfCellStandable = canStand(level, selfCell);
        premiseModelReachFromBotCell = item != null && CollectDropsTask.withinPickupReach(selfCell, item);
        premiseActualMissFromBot = item != null && !CollectDropsTask.reachesFrom(bot.getBoundingBox(), item);
        if (item != null) {
            premiseModelAxisDistance = Math.abs(selfCell.getX() + 0.5D - item.getX());
            premiseActualAxisDistance = Math.abs(bot.getX() - item.getX());
        }
    }

    // ==================== 收尾 ====================

    private void cleanup() {
        ServerLevel level = bot.serverLevel();
        discardItem(level);
        clearScene(level);
        forceload(level, false);
        bot.controller().stopMovement();
        if (entryFoot != null) {
            teleportFoot(level, entryFoot);
        }
        FixtureToolKit.resetInventory(bot);
        discardNearbyProduct(level);
        int left = countSceneItems(level);
        check("收尾：夹具造出来的落物全部收回（场景内剩余=" + left + "）", left == 0);
        check("收尾：场景地形清回空气（残留非空气=" + remainingNonAir(level) + "）", remainingNonAir(level) == 0);

        boolean pass = failures.isEmpty();
        BotLog.info("[CollectOffcenter] SUMMARY checks={} failures={} collected={} goal_excluded={}"
                        + " 地板方块={} 原本非空气={} → {}｜失败项：{}",
                checks, failures.size(), collectedDelta,
                collector == null ? -1 : collector.goalExcludedTotal(), builtBlocks, preExistingNonAir,
                pass ? "PASS" : "FAIL", failures);
        if (observer != null && !observer.isRemoved()) {
            observer.sendSystemMessage(Component.literal("[alice] 掉落物「到位却够不到⇒换格再试」取证 "
                    + (pass ? "PASS" : "FAIL") + "（详见日志 [CollectOffcenter]）"));
        }
        phase = Phase.DONE;
    }

    // ==================== 世界读写与观测 ====================

    private BlockPos itemCell() {
        return ORIGIN.offset(ITEM_DX, FOOT_DY, ITEM_DZ);
    }

    private void set(ServerLevel level, int dx, int dy, int dz, net.minecraft.world.level.block.Block block) {
        BlockPos pos = ORIGIN.offset(dx, dy, dz);
        level.setBlock(pos, block.defaultBlockState(), 3);
        probe.put(pos, level.getBlockState(pos));
    }

    /** 把 bot 放进物品格的**西邻**，并在 x 上**离心 {@link #BOT_OFFSET_X}**（真机那一幕）。 */
    private void teleportToSelfCellOffCenter(ServerLevel level) {
        double x = ORIGIN.getX() - 1 + 0.5D - BOT_OFFSET_X;    // 西邻格中心 − 离心
        double z = ORIGIN.getZ() + ITEM_OFFSET_Z;
        bot.teleportTo(level, x, ORIGIN.getY() + FOOT_DY, z, Set.of(), bot.getYRot(), bot.getXRot());
        bot.setDeltaMovement(Vec3.ZERO);
        bot.controller().stopMovement();
    }

    private Map<BlockPos, BlockState> snapshot(ServerLevel level) {
        Map<BlockPos, BlockState> snapshot = new LinkedHashMap<>();
        for (BlockPos pos : probe.keySet()) {
            snapshot.put(pos, level.getBlockState(pos));
        }
        return snapshot;
    }

    private int countChanges(ServerLevel level, Map<BlockPos, BlockState> before) {
        if (before.isEmpty()) {
            return -1;
        }
        int changed = 0;
        for (Map.Entry<BlockPos, BlockState> entry : before.entrySet()) {
            if (!level.getBlockState(entry.getKey()).equals(entry.getValue())) {
                changed++;
            }
        }
        return changed;
    }

    private boolean canStand(ServerLevel level, BlockPos pos) {
        return com.dddgn.alice.pathing.MovementHelper.canStandCentered(level, pos);
    }

    private void forceload(ServerLevel level, boolean on) {
        for (int cx = (ORIGIN.getX() - FLOOR_HALF_X) >> 4; cx <= (ORIGIN.getX() + FLOOR_HALF_X) >> 4; cx++) {
            for (int cz = (ORIGIN.getZ() - FLOOR_HALF_Z) >> 4; cz <= (ORIGIN.getZ() + FLOOR_HALF_Z) >> 4; cz++) {
                level.setChunkForced(cx, cz, on);
            }
        }
    }

    private AABB box() {
        return new AABB(ORIGIN.getX() - FLOOR_HALF_X, ORIGIN.getY(), ORIGIN.getZ() - FLOOR_HALF_Z,
                ORIGIN.getX() + FLOOR_HALF_X + 1, ORIGIN.getY() + HEADROOM + 1,
                ORIGIN.getZ() + FLOOR_HALF_Z + 1);
    }

    private int countSceneItems(ServerLevel level) {
        int total = 0;
        for (ItemEntity entity : level.getEntitiesOfClass(ItemEntity.class, box())) {
            total += entity.getItem().getCount();
        }
        return total;
    }

    private int countProductInInventory() {
        int total = 0;
        var inventory = bot.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && stack.is(Items.AMETHYST_SHARD)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /** 候选来源必须**每 tick 现查**（`D-344` 的既有钩子；递快照会让收集器空等到超时并记 MISMATCH）。 */
    private List<ItemEntity> liveItem() {
        if (item == null || item.isRemoved() || !entityAlive(bot.serverLevel(), item.getUUID())) {
            return List.of();
        }
        return List.of(item);
    }

    private ItemEntity visibleItem(ServerLevel level) {
        for (ItemEntity entity : level.getEntitiesOfClass(ItemEntity.class, box())) {
            if (entity.getItem().is(Items.AMETHYST_SHARD)) {
                return entity;
            }
        }
        return null;
    }

    private boolean entityAlive(ServerLevel level, UUID id) {
        var entity = level.getEntity(id);
        return entity != null && !entity.isRemoved();
    }

    private void discardItem(ServerLevel level) {
        for (ItemEntity entity : level.getEntitiesOfClass(ItemEntity.class, box())) {
            entity.discard();
        }
        item = null;
        itemId = null;
    }

    private void discardNearbyProduct(ServerLevel level) {
        AABB around = bot.getBoundingBox().inflate(3.0D);
        for (ItemEntity entity : level.getEntitiesOfClass(ItemEntity.class, around)) {
            if (entity.getItem().is(Items.AMETHYST_SHARD)) {
                entity.discard();
            }
        }
    }

    private void clearScene(ServerLevel level) {
        for (BlockPos pos : probe.keySet()) {
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
        }
    }

    private int remainingNonAir(ServerLevel level) {
        int left = 0;
        for (BlockPos pos : probe.keySet()) {
            if (!level.getBlockState(pos).isAir()) {
                left++;
            }
        }
        return left;
    }

    private boolean summonProduct(ServerLevel level, BlockPos cell, double offsetX, double offsetZ) {
        int commands = level.getServer().getCommands().performPrefixedCommand(
                level.getServer().createCommandSourceStack().withSuppressedOutput(),
                "summon minecraft:item " + (cell.getX() + offsetX) + " " + (cell.getY() + 0.2D) + " "
                        + (cell.getZ() + offsetZ) + " {Item:{id:\"" + PRODUCT
                        + "\",Count:1b},PickupDelay:0s}");
        // 陷阱 #5：`/summon` 在抑制输出的命令源下静默失败 ⇒ **必须看返回值**
        return commands > 0;
    }

    private void teleportFoot(ServerLevel level, BlockPos foot) {
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

    private static String fmtBox(AABB box) {
        return String.format(java.util.Locale.ROOT, "[%.2f..%.2f y %.2f..%.2f z %.2f..%.2f]",
                box.minX, box.maxX, box.minY, box.maxY, box.minZ, box.maxZ);
    }

    private static String fmt(double value) {
        return String.format(java.util.Locale.ROOT, "%.3f", value);
    }
}
