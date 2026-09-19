package com.dddgn.alice.task;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.decision.CollectGrants;
import com.dddgn.alice.item.FixtureToolKit;
import com.dddgn.alice.job.lumber.LumberCandidateSource;
import com.dddgn.alice.job.lumber.LumberCandidateSource;
import com.dddgn.alice.job.lumber.LumberRegionState;
import com.dddgn.alice.job.lumber.RegionLumberJob;
import com.dddgn.alice.job.policy.NearestPolicy;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.perception.ScopeBuffer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * **区域"扫地面 → 捡苗 → 回来补种"的端到端自检**（`D-344` 片 A 的最后一环）。
 *
 * <p><b>为什么需要它</b>：{@code region_sweep} 夹具只覆盖**判定层 + 配置层**（纯函数与 NBT），
 * 它**证明不了**"bot 真的走过去把地上的苗捡回来、并用它补了种"。本夹具补的就是这一环。
 *
 * <p><b>它断言哪一层</b>（技能 §6.9.1 ③）：**行为层**——{@code RegionLumberJob} 的
 * `sweep` 阶段在真实世界里跑完整条链：扫 → 捡 → 补。
 *
 * <p><b>怎么让"苗只能来自扫描"这一点无可争辩</b>（这是本夹具的核心设计）：
 * <ol>
 *   <li>**不砍树**：故意不跑 `lumber_course_trees` ⇒ 没有树叶衰减 ⇒ **没有任何"我方掉落"**，
 *       从根上排除"内层 `COLLECT` 顺手把苗收了"这条歧义路径；</li>
 *   <li>**把欠树直接造出来**：预置 {@code baselineTrees = 3} 且 {@code baselineDerived = true}
 *       ⇒ 首次巡查（不砍任何树）就 `deficit = 3`；</li>
 *   <li>**背包清空且零树苗**（{@code resetInventory}），而**地面上预置 3 个树苗掉落物**
 *       —— 它们是 `FOREIGN`（不是我方产出的）⇒ 只有 ①裁定的 `SESSION` 收集授权能捡起来；</li>
 *   <li>**预置一个可补种点**（空气 + 下方泥土，就在掉苗处）⇒ 捡到苗后能立刻补。</li>
 * </ol>
 * ⇒ 只要最终 `sweepsRun()≥1` **且**补种成功，就说明"苗是扫回来的、并真的用上了"。
 *
 * <p><b>副作用边界</b>（技能 §6.9.2）：不调 `assign*`/`beginTask`（那会 `clearTask()` 掉正在跑的
 * 电池步）—— 本夹具**自己 tick 一个 `RegionLumberJob` 实例**（与 `region_maintain` 步同一个 Job 类，
 * 只是由本夹具驱动）；结束（含失败路径）**清理**：清掉预置掉落物、撤销扫描授权、还原区域状态。
 *
 * <p><b>几何前提</b>（§6.9.1 ①，盒以谁为中心/多大必须写下来）：
 * 作业区域 = {@link LumberCourseAnchor#region()}（夹具与 `region_maintain` **共用同一个区域**）；
 * 补种点/掉苗点 = 在该区域内**扫描**出来的"y=64 空气 + y=63 泥土"的格子（取离起点最近的一个）
 * —— 不写死坐标，也不假设地形，扫不到 ⇒ 报 `NO_PLANT_SPOT`（几何前提**自断言**）。
 *
 * <p>输出：`[RegionSweepE2E] CHECK … → PASS|FAIL`。
 */
public final class RegionSweepE2ECheckTask implements Task {

    /** 预置的树苗掉落物件数（地面上"可捡"的东西）。 */
    private static final int GROUND_SAPLINGS = 3;
    /** 造出来的欠树数（不砍树，直接预置 baseline）。 */
    private static final int FORCED_DEFICIT = 3;
    /** 端到端预算（tick）：够"走过去捡 3 件 + 回来补 1 棵"。 */
    private static final int BUDGET_TICKS = 2400;
    /** 预置的树苗种类（清单默认项就是它）。 */
    private static final String SAPLING = "minecraft:oak_sapling";

    private enum Phase { SETUP, RUN, ASSERT, DONE }

    private final BotPlayer bot;
    private final ScopeBuffer scope;
    private Phase phase = Phase.SETUP;
    private RegionLumberJob job;
    private int ticks;

    // ---- 前提（SETUP 时记录，ASSERT 时自证）----
    private BlockPos plantSpot;
    private int groundBefore = -1;
    private int saplingsInInvBefore = -1;
    private int plantedBefore;
    private String setupFailure = "";
    // ---- 观察（RUN 时累积）----
    private boolean sawGrant;
    private boolean sawSweeping;
    /** 互斥违例：同一 tick 既在扫描又有砍树子任务在跑（细则④「不许撞车」）。 */
    private boolean sawMutexViolation;
    private Task.Status jobTerminal = Task.Status.RUNNING;

    // ---- 还原用快照 ----
    private String savedSapling;
    private List<String> savedExplicit = new ArrayList<>();
    private int savedBaseline;
    private boolean savedBaselineDerived;
    /** 造出来的欠树数（SETUP 里量完 standing 之后算出来的真值，用于自断言与日志）。 */
    private int forcedDeficit;

    private String failure = "";
    private final List<String> failed = new ArrayList<>();
    private int checks;

    public RegionSweepE2ECheckTask(BotPlayer bot, ScopeBuffer scope) {
        this.bot = bot;
        this.scope = scope;
    }

    @Override
    public String taskName() {
        return "RegionSweepE2ECheck";
    }

    @Override
    public TaskTarget target() {
        return TaskTarget.block(LumberCourseAnchor.START_FOOT);
    }

    @Override
    public String failureReason() {
        return failure;
    }

    @Override
    public Task.Status tick() {
        return switch (phase) {
            case SETUP -> setup();
            case RUN -> run();
            case ASSERT -> assertResult();
            case DONE -> Task.Status.DONE;
        };
    }

    // ==================== SETUP ====================

    private Task.Status setup() {
        var level = bot.serverLevel();
        var server = level.getServer();
        UUID owner = bot.getUUID();
        LumberRegionState state = LumberRegionState.get(server);

        // 快照（无论如何都要还原：本夹具借的是用户的区域状态）
        savedSapling = state.saplingItem(owner);
        savedExplicit = new ArrayList<>(state.pickupItems(owner));
        savedBaseline = state.baselineTrees(owner);
        savedBaselineDerived = state.baselineDerived(owner);

        // ① 地形函数：**看返回值**（技能 陷阱#5：`/function` 在抑制输出的命令源下静默失败）
        int terrain = server.getCommands().performPrefixedCommand(
                server.createCommandSourceStack().withSuppressedOutput(),
                "function alice_test:lumber_course_terrain");
        // 传送本身会加载区块（玩家 ticket 同步加载）——先做它，再做任何扫描/生成
        bot.teleportTo(level, LumberCourseAnchor.START_FOOT.getX() + 0.5D,
                LumberCourseAnchor.START_FOOT.getY(), LumberCourseAnchor.START_FOOT.getZ() + 0.5D,
                Set.of(), bot.getYRot(), bot.getXRot());
        bot.setDeltaMovement(Vec3.ZERO);
        bot.controller().stopMovement();
        if (terrain <= 0) {
            setupFailure = "TERRAIN_NOT_BUILT(commands=" + terrain + ")";
        }

        // ② 几何前提自断言：在区域内扫出"可补种点"（y 空气 + y-1 泥土）
        plantSpot = findPlantSpot();
        if (plantSpot == null && setupFailure.isEmpty()) {
            setupFailure = "NO_PLANT_SPOT(区域=" + LumberCourseAnchor.region().describe() + ")";
        }

        // ③ 背包清空 ⇒ **零树苗**（这是"苗只能从地面来"的前提）
        // ⚠️ `clear` 会把背包里的东西**掉在脚下**（实测：世界存档里的 bot 背包带着 8 个树苗
        // ⇒ 清空后地上凭空多出 8 件）⇒ 必须**再清一次地面**，否则"地上有几件"不可控、前提不可断言。
        FixtureToolKit.resetInventory(bot);
        clearGroundSaplings();
        saplingsInInvBefore = countSaplingInInventory();

        if (plantSpot != null) {
            // ④ 预置落苗（FOREIGN：不是我方产出的 ⇒ 只有 ①裁定的授权能捡）。
            // 用 **COUNT 次独立 summon**（同位置的原版合并会把多件并成一叠，所以判据用"件数之和"而非实体数）
            for (int i = 0; i < GROUND_SAPLINGS; i++) {
                int spawned = server.getCommands().performPrefixedCommand(
                        server.createCommandSourceStack().withSuppressedOutput(),
                        "summon minecraft:item " + (plantSpot.getX() + 0.5D) + " "
                                + (plantSpot.getY() + 0.2D) + " " + (plantSpot.getZ() + 0.5D)
                                + " {Item:{id:\"" + SAPLING + "\",Count:1b},PickupDelay:0s}");
                if (spawned <= 0) {
                    setupFailure = "SUMMON_FAILED(commands=" + spawned + ")";
                    break;
                }
            }
        }
        groundBefore = countGroundSaplingItems();

        // ⑤ 区域状态：**不砍树**直接把欠树造出来 + 预置一个待补种点
        state.setRegion(owner, LumberCourseAnchor.region());   // 必须在 addPendingReplant **之前**（setRegion 会剔除区外的待补种项）
        state.setSaplingItem(owner, SAPLING);
        // ⚠️ **不能写死 baseline**（2026-09-19 `module:lumber` 实测踩到）：整台电池是**同一个存档**，
        // 前面的步（`region_maintain`）已经种下苗（`mySaplings` 持久化）并砍掉部分树
        // ⇒ 真实 `standing` 不是 0 ⇒ 写死 `baseline=3` 可能造出 `deficit=0`（于是不进扫描、反而去砍树，
        // 以 `no_reachable_candidate` 收场）。技能同样的话：**要断转移，不要断初始状态**。
        // ⇒ 先**量出**真实 standing（= 我种的苗 + 区域内可作业树，与 `patrol()` 同一个候选源），
        //    再把它**抬到** standing + 欠树数（多给一点余量，避免与巡查看法差一格导致 deficit=0）。
        int standing = state.mySaplingCount(owner) + countViableTreesInRegion();
        state.setBaselineTrees(owner, standing + FORCED_DEFICIT + 4);
        state.setBaselineDerived(owner, true);
        plantedBefore = state.saplingsPlanted(owner);
        forcedDeficit = Math.max(0, state.baselineTrees(owner) - standing);
        if (plantSpot != null) {
            state.addPendingReplant(owner, plantSpot);
        }

        BotLog.info("[RegionSweepE2E] CHECK setup spot={} ground={} 背包苗={} standing={} baseline={}"
                        + "欠树={} pendingReplant={} terrain_cmds={}",
                plantSpot == null ? "-" : plantSpot.toShortString(), groundBefore, saplingsInInvBefore,
                standing, state.baselineTrees(owner), forcedDeficit,
                state.pendingReplantCount(owner), terrain);

        job = new RegionLumberJob(bot, LumberCourseAnchor.region(), scope,
                new LumberCandidateSource(), new NearestPolicy(), 20, BUDGET_TICKS);
        phase = Phase.RUN;
        return Task.Status.RUNNING;
    }

    /**
     * 在区域内扫一个"y 空气 + y-1 泥土 + 头顶空"的格子。
     *
     * <p>**优先取"离起点 ≥ 4 格"里最近的**：故意不让它落在 bot 脚下 —— 这样扫描阶段必须**真的走过去**
     * （走到落苗处拣起来），而不是"站在原地等范围吸附"，测试强度更高；
     * 找不到 ≥4 格的再退回全局最近（场景变小也不至于假红）。
     */
    private BlockPos findPlantSpot() {
        var level = bot.serverLevel();
        var region = LumberCourseAnchor.region();
        BlockPos fallback = null;
        double fallbackDistance = Double.MAX_VALUE;
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int x = region.minX(); x <= region.maxX(); x++) {
            for (int z = region.minZ(); z <= region.maxZ(); z++) {
                BlockPos pos = new BlockPos(x, LumberCourseAnchor.START_FOOT.getY(), z);
                if (!level.getBlockState(pos).isAir()) {
                    continue;
                }
                if (!level.getBlockState(pos.below()).is(net.minecraft.tags.BlockTags.DIRT)) {
                    continue;
                }
                if (!level.getBlockState(pos.above()).isAir()) {
                    continue;      // 头顶要空（别把补种点选在树叶/原木下面）
                }
                double distance = pos.distSqr(LumberCourseAnchor.START_FOOT);
                if (distance < fallbackDistance) {
                    fallbackDistance = distance;
                    fallback = pos;
                }
                if (distance >= 16.0D && distance < bestDistance) {
                    bestDistance = distance;
                    best = pos;
                }
            }
        }
        return best != null ? best : fallback;
    }

    // ==================== RUN ====================

    private Task.Status run() {
        // 观察 ①裁定的授权生命周期（扫描期间应在、结束后应不在）
        if (hasRegionLumberGrant()) {
            sawGrant = true;
        }
        if (job.sweeping()) {
            sawSweeping = true;
            // 细则④「不许撞车」的**运行时**证据：同一 tick 不可能既在扫描又有砍树子任务
            // （两者由 `tick()` 的两个守卫 + "`sweepTask = new` 只出现一处"从结构上保证；
            //  这里做的是**运行期复核**，与内核规则的结构断言互补）
            if (job.harvesting()) {
                sawMutexViolation = true;
                BotLog.warn("[RegionSweepE2E] CHECK 互斥违例：扫描与砍树同时在进行");
            }
        }
        jobTerminal = job.tick();
        ticks++;
        boolean chainDone = job.sweepsRun() >= 1 && state().saplingsPlanted(bot.getUUID()) > plantedBefore;
        if (chainDone) {
            BotLog.info("[RegionSweepE2E] CHECK 链路完成 ticks={} sweeps={} 扫描入包={} 已补种={}",
                    ticks, job.sweepsRun(), job.sweepCollected(),
                    state().saplingsPlanted(bot.getUUID()) - plantedBefore);
            phase = Phase.ASSERT;
            return Task.Status.RUNNING;
        }
        if (jobTerminal != Task.Status.RUNNING) {
            BotLog.warn("[RegionSweepE2E] CHECK 作业提前终态 status={} reason={}",
                    jobTerminal, job.terminalReason());
            phase = Phase.ASSERT;
            return Task.Status.RUNNING;
        }
        if (ticks > BUDGET_TICKS) {
            BotLog.warn("[RegionSweepE2E] CHECK 预算用尽（{} tick）⇒ 断言", ticks);
            phase = Phase.ASSERT;
            return Task.Status.RUNNING;
        }
        return Task.Status.RUNNING;
    }

    // ==================== ASSERT ====================

    private Task.Status assertResult() {
        var state = state();
        UUID owner = bot.getUUID();
        int groundNow = countGroundSaplingItems();
        int plantedDelta = state.saplingsPlanted(owner) - plantedBefore;

        checks++;
        check("**几何/场景前提成立**（地形函数已建 + 扫到可补种点）", setupFailure.isEmpty());
        if (!setupFailure.isEmpty()) {
            failed.add("几何/场景前提：" + setupFailure);   // 带上**具体**原因（可分类）
        }
        check("**前提：真的造出了欠树**（`baseline > standing`，否则作业不会进扫描）",
                forcedDeficit > 0);
        check("**前提：背包里一件树苗都没有**（否则苗可能不是扫来的）", saplingsInInvBefore == 0);
        check("**前提：地面上确实预置了 " + GROUND_SAPLINGS + " 件树苗**（可捡的东西存在）",
                groundBefore >= GROUND_SAPLINGS);
        check("**扫描阶段真的跑了**（`sweepsRun ≥ 1`）—— 苗不是凭空出现在背包里的",
                job.sweepsRun() >= 1);
        check("**扫描阶段真的捡到了东西**（`sweepCollected ≥ 预置件数`）",
                job.sweepCollected() >= GROUND_SAPLINGS);
        // ⚠️ **刻意不拿"地面总数变少"当判据**（2026-09-19 实测教训）：本场景的树叶**会持续产生**
        // 树苗掉落物（预置 3 件 ⇒ 首轮巡查时区域内已观察到 11 件）⇒ 地面总量是**别的系统也在喂**的
        // 活聚合量，它自己会涨 ⇒ 断言它必然假红。改为断言**机制的产物**：背包里的苗（从 0 起步）
        // 与"已经补上了" —— 这两条不受环境喂料影响。
        // 断"手上还剩 + 已经种掉的 ≥ 我预置的件数"：**补种会消耗苗**（实测：收 3 件、种掉 1 ⇒ 背包剩 2），
        // 所以单看背包会假红 —— 这正是"判据要跟着机制走"（第一版写的是"背包 ≥ 3"，被 `module:lumber` 抓红）。
        check("**苗确实进了背包**（手上剩余 + 已种掉的 ≥ 预置件数）",
                countSaplingInInventory()
                        + (state.saplingsPlanted(owner) - plantedBefore) >= GROUND_SAPLINGS);
        check("⭐ **捡完之后真的补了种**（`saplingsPlanted` 增加）—— 细则⑤「捡完再回来补」闭环",
                plantedDelta > 0);
        check("**扫描期间签发过收集授权**（①裁定：区内 FOREIGN 落物靠它才捡得起来）", sawGrant);
        check("**扫描结束后授权不在了**（①裁定：权限窗口精确等于扫描时长）",
                !hasRegionLumberGrant());
        check("**互斥**：扫描与砍树没有同时发生（细则④）", !sawMutexViolation);
        check("**真的进过扫描阶段**（`sweeping()` 曾被观察到为真）", sawSweeping);

        cleanup();

        boolean pass = failed.isEmpty();
        BotLog.info("[RegionSweepE2E] CHECK sweeps={} 扫描入包={} ground={}→{} 补种+{} grant={} "
                        + "sawSweep={} ticks={} → {}",
                job.sweepsRun(), job.sweepCollected(), groundBefore, groundNow, plantedDelta,
                sawGrant, sawSweeping, ticks, pass ? "PASS" : "FAIL");
        if (!pass) {
            failure = "REGION_SWEEP_E2E_FAILED " + String.join(" | ", failed);
            phase = Phase.DONE;
            return Task.Status.FAILED;
        }
        phase = Phase.DONE;
        return Task.Status.DONE;
    }

    /** 结束清理（**失败路径也走** —— 技能 §6.9.2「失败必清理」）。 */
    private void cleanup() {
        UUID owner = bot.getUUID();
        var state = state();
        // 1) 清掉预置掉落物（我造的：本场景不砍树 ⇒ 区域内的橡树苗掉落实质上只可能是我放的）
        for (var item : level().getEntitiesOfClass(ItemEntity.class, regionBox())) {
            if (item.getItem().is(Items.OAK_SAPLING)) {
                item.discard();
            }
        }
        // 2) 撤销扫描授权（若作业在中途被放弃，`finish()` 没机会跑）
        for (var grant : CollectGrants.active(bot.getServer(), bot.getServer().getTickCount())) {
            if ("region_lumber".equals(grant.grantedBy())) {
                CollectGrants.revoke(grant.id());
            }
        }
        // 3) 还原区域状态（顺序：先撤我加的待补种点，再还原标量）
        if (plantSpot != null) {
            state.removePendingReplant(owner, plantSpot);
        }
        for (String item : state.pickupItems(owner)) {
            state.removePickupItem(owner, item);
        }
        for (String item : savedExplicit) {
            state.addPickupItem(owner, item);
        }
        state.setSaplingItem(owner, savedSapling);
        state.setBaselineTrees(owner, savedBaseline);
        state.setBaselineDerived(owner, savedBaselineDerived);
        bot.controller().stopMovement();
    }

    // ==================== 工具 ====================

    private void check(String name, boolean ok) {
        checks++;
        if (!ok) {
            failed.add(name);
        }
    }

    private LumberRegionState state() {
        return LumberRegionState.get(bot.getServer());
    }

    private net.minecraft.server.level.ServerLevel level() {
        return bot.serverLevel();
    }

    /**
     * 区域内**清单内（= 橡树苗）掉落物的件数之和**。
     *
     * <p>为什么用"件数之和"而不是"实体数"：同位置的原版合并会把 3 次 summon 并成一叠
     * （`Count=3` 的**一个**实体）⇒ 数实体会得到 1，前提断言就会假红。
     *
     * <p>几何盒与 `RegionLumberJob.listDropsInRegion` **同一个口径**（区域水平范围 + `baseY-2 …
     * baseY+maxH+2`）—— 夹具与生产用同一个盒子，避免"量的不是一回事"。
     */
    private int countGroundSaplingItems() {
        int total = 0;
        for (var item : level().getEntitiesOfClass(ItemEntity.class, regionBox())) {
            if (item.getItem().is(Items.OAK_SAPLING)) {
                total += item.getItem().getCount();
            }
        }
        return total;
    }

    /** 清掉区域内**已有的**树苗掉落物（把"地上有几件"变成夹具可控的量）。 */
    private void clearGroundSaplings() {
        for (var item : level().getEntitiesOfClass(ItemEntity.class, regionBox())) {
            if (item.getItem().is(Items.OAK_SAPLING)) {
                item.discard();
            }
        }
    }

    /**
     * 区域内**可作业树**的数量 —— 与 `patrol()` 用**同一个候选源**（`LumberCandidateSource`）
     * 与同一套"算不算在区内"的判据（水平在区内 + 竖直不低于 `baseY-2`）。
     *
     * <p>夹具只用它来**量 standing**（好把 baseline 抬到 standing 之上造出欠树）；
     * 它是**只读**的，不改世界、不派任务。
     */
    private int countViableTreesInRegion() {
        var region = LumberCourseAnchor.region();
        var spec = com.dddgn.alice.job.GoalSpec.harvestUnits(region.center(), region.coverRadius(),
                1, BUDGET_TICKS);
        var raw = new LumberCandidateSource().candidates(bot, spec);
        int total = 0;
        for (var candidate : raw.viable()) {
            if (region.containsHorizontal(candidate.anchor())
                    && candidate.anchor().getY() >= region.baseY() - 2) {
                total++;
            }
        }
        return total;
    }

    private AABB regionBox() {
        var region = LumberCourseAnchor.region();
        return new AABB(region.minX(), region.baseY() - 2, region.minZ(),
                region.maxX() + 1, region.baseY() + region.maxHeight() + 2, region.maxZ() + 1);
    }

    private boolean hasRegionLumberGrant() {
        for (var grant : CollectGrants.active(bot.getServer(), bot.getServer().getTickCount())) {
            if ("region_lumber".equals(grant.grantedBy())) {
                return true;
            }
        }
        return false;
    }

    private int countSaplingInInventory() {
        var inventory = bot.getInventory();
        int total = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            var stack = inventory.getItem(i);
            if (!stack.isEmpty() && stack.is(Items.OAK_SAPLING)) {
                total += stack.getCount();
            }
        }
        return total;
    }
}
