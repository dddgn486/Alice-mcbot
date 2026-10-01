package com.dddgn.alice.fixture;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.job.JobDeclaration;
import com.dddgn.alice.job.lumber.LumberCandidateSource;
import com.dddgn.alice.job.lumber.LumberAreaState;
import com.dddgn.alice.job.lumber.RegionLumberJob;
import com.dddgn.alice.job.policy.NearestPolicy;
import com.dddgn.alice.ledger.ModifyAudit;
import com.dddgn.alice.ledger.WorldModLedger;
import com.dddgn.alice.action.BlockInteraction;
import com.dddgn.alice.region.WorkingArea;
import com.dddgn.alice.region.authz.Quota;
import com.dddgn.alice.write.WriteReason;
import com.dddgn.alice.write.WritePolicyMatrix;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.perception.ScopeBuffer;
import com.dddgn.alice.protection.AreaData;
import com.dddgn.alice.region.JobRegionRegistry;
import com.dddgn.alice.region.authz.AreaPermission;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import com.dddgn.alice.task.Task;
import com.dddgn.alice.task.TaskTarget;

/**
 * ⭐ **任务区门禁**（`§5.12` 第 4 件的"几何 + 锁定"层，`D-338` 附注二/附注四）—— 电池步
 * `task_zone`（**EXTRA**）。
 *
 * <p>它守的是四件事，**一件不少**：
 * <ol>
 *   <li><b>派生几何</b>：工作区域（**方块级**）⇒ 任务区（**区块级最小覆盖**），单向；
 *       最小性用**逐方块枚举**这条独立路径做等式断言（不是同一公式抄两遍）；Y 不参与派生；</li>
 *   <li><b>覆盖规则</b>（⛔ **2026-10-01 用户裁定后已改**）：任务区**可以**覆盖保护区，
 *       **也可以覆盖安全区**（安全区退化为「保护区上的一个标记位」、与保护区**同权限**
 *       ⇒「任务区不得覆盖安全区」这条**失去了立足点**）；声明任务区**不改**玩家的区域声明
 *       （安全区/保护区计数一字未动）；覆盖规则今天只剩「**任何区域 → `job` 区 ⛔拒绝**」；</li>
 *   <li><b>锁定与生命周期</b>：唯一写入者是任务；作用域一收尾（终态/被替换/显式打断）
 *       ⇒ 权威**自动消失**（不需要谁记得来关）；过期条目被 prune 掉；没有作用域 ⇒ `NO_SCOPE`；</li>
 *   <li><b>生产接线</b>：真跑一次 {@link RegionLumberJob}（**生产 Job 类，不是影子实现**）——
 *       首 tick 会解算出任务区，终态后任务区已解除；工作区域压到安全区上时**照常解算任务区**
 *       （⛔ `task_zone_conflict` 那条失败路径**已删除**，且任务区**真的覆盖**那个安全区区块）。</li>
 * </ol>
 *
 * <p><b>本步刻意不做的两件事（不许"顺手"补上）</b>：① **不动任何权限行为** ——
 * 保护区块在 `AreaData.protectionReason` 下**照旧**是 `protected_area`（判据里有这一条，
 * 防止"任务区一落地就把闸门放宽了"；接闸门是第 4 件的下一片，且要等**第 5 件权限阶梯**拍板）；
 * ② 不改世界 —— 夹具只做**集合运算 + 一处认领/声明**（收尾按增量还原），
 * 不像素地形、不加载远处区块（`protectionReason` 对已认领区块**不读方块**就返回）。
 *
 * <p><b>专用孤立区</b>（不与任何既有场景/基准重叠；全是纯集合运算 ⇒ 真实存档里也能跑）：
 * 块 `x 35200..35240 / z 35200..35210`（**故意跨 3 个区块** 2200..2202 × 2200）、
 * 第二区块 `35360..35363 / 35360..35363`（区块 2210,2210，1 个区块）、
 * 区外安全区点 = 区块 2215,2215。**所有维度级计数都按增量断言**。
 */
public final class JobRegionCheckTask implements Task {

    // ==================== 工作区域（方块级）====================

    /** 工作区域 ①：**故意跨区块边界**（x 35200..35240 ⇒ 区块 2200..2202；z 35200..35210 ⇒ 区块 2200）。 */
    private static final int AREA_MIN_X = 35200;
    private static final int AREA_MAX_X = 35240;
    private static final int AREA_MIN_Z = 35200;
    private static final int AREA_MAX_Z = 35210;
    private static final int[] AREA_CHUNKS_X = {2200, 2201, 2202};
    private static final int AREA_CHUNK_Z = 2200;
    /** 覆盖规则用例挑**中间**那个区块（2201,2200）：安全区退化后任务区**可以**覆盖它。 */
    private static final int CONFLICT_CHUNK_X = 2201;

    /** 工作区域 ②：1 个区块（区块 2210,2210）—— 换区用例与**生产 Job 用例**共用。 */
    private static final int AREA2_MIN_X = 35360;
    private static final int AREA2_MAX_X = 35363;
    private static final int AREA2_MIN_Z = 35360;
    private static final int AREA2_MAX_Z = 35363;
    private static final int AREA2_CHUNK_X = 2210;
    private static final int AREA2_CHUNK_Z = 2210;

    /** 两区**之外**的安全区点（区块 2215,2215）：证明覆盖规则只看向交集。 */
    private static final int OUTSIDE_CHUNK_X = 2215;
    private static final int OUTSIDE_CHUNK_Z = 2215;

    private static final BlockPos AREA_START = new BlockPos(AREA_MIN_X, -60, AREA_MIN_Z);
    private static final BlockPos AREA2_START = new BlockPos(AREA2_MIN_X, -60, AREA2_MIN_Z);
    private static final BlockPos OUTSIDE_POS = new BlockPos(OUTSIDE_CHUNK_X << 4, -60, OUTSIDE_CHUNK_Z << 4);

    /** 世界地板 Y（无头/客户端存档都是 y=-61 面、脚位 -60；与 `SafeReturnCheckTask` 同一口径）。 */
    private static final int FOOT_Y = -60;

    // ---- 区域级授权面（权限阶梯，`D-338` 附注七）的专用格：都落在**已认领**的孤立区里 ----
    /** 授权面用的工作区域：块 `35200..35215 × 35204..35219` ⇒ 恰好区块 (2200,2200)。 */
    private static final int AUTH_MIN_X = 35200;
    private static final int AUTH_MAX_X = 35215;
    private static final int AUTH_MIN_Z = 35204;
    private static final int AUTH_MAX_Z = 35219;
    /** 区内：被任务区覆盖（区块 2200,2200）。 */
    private static final BlockPos AUTH_INSIDE = new BlockPos(35204, FOOT_Y, 35208);
    /** 认领了但**不被**任务区覆盖（区块 2202,2200）—— 判"授权不许越界"（不泄漏）。 */
    private static final BlockPos AUTH_OTHER_CHUNK = new BlockPos(35236, FOOT_Y, 35208);
    /** **安全区**（区块 2201,2200，`SAFE_OVERLAY` 相位声明的那一个）—— 退化后与保护区**同权限**。 */
    private static final BlockPos AUTH_SAFE = new BlockPos(35220, FOOT_Y, 35208);
    /** **野外**（区块 2212,2212，未认领）—— 本判据必须 `NOT_GATED`。 */
    private static final BlockPos AUTH_WILDERNESS = new BlockPos(35400, FOOT_Y, 35400);
    /** 候选扫描用例的**手搭小树**（3 格原木，够 `TreeScanner.MIN_LOGS`）：在区块 2200,2200 内。 */
    private static final BlockPos AUTH_TREE_BASE = new BlockPos(35210, FOOT_Y, 35206);
    /**
     * ⭐ **刀 2「宝贵 → 位置判据」**的格子（`D-565` ⑤）：**区内**（区块 2200,2200，被认领 ＋ 被授权面的任务区
     * 覆盖），且不与上面任何格重叠 —— 用例会**在同一格里换方块**（箱子 ⇄ 石头）来分离"位置"与"方块类型"。
     */
    private static final BlockPos AUTH_CHEST = new BlockPos(35206, FOOT_Y, 35210);
    /** `L1` 配额用例的 8 个落点（全在区块 2200,2200 内，离 bot 的站位 ≥4 格）。 */
    private static final int QUOTA_BASE_X = 35200;
    private static final int QUOTA_Z = 35212;

    private static final int SETTLE_TICKS = 30;
    private static final int BUDGET_TICKS = 600;
    /** 生产 Job 用例的 tick 预算（`maxTicks=4` ⇒ 第 5 tick 必然 `goal_timeout`）。 */
    private static final int JOB_TICK_CAP = 40;

    private enum Phase {
        PREPARE, GEOMETRY, DECLARE, SCOPE_LIFECYCLE, OVERLAY, SAFE_OVERLAY,
        AUTHORITY, JOB_SETUP, JOB_OK, JOB_SAFE_SETUP, JOB_SAFE, CLEANUP, DONE
    }

    private final BotPlayer bot;
    private final ServerPlayer observer;
    private final ScopeBuffer scope;
    private final List<String> failures = new ArrayList<>();
    private final List<String> findings = new ArrayList<>();

    private Phase phase = Phase.PREPARE;
    private int ticks;
    private int checks;
    private int settle;
    private boolean done;
    private boolean timedOut;

    // 进入前的现场（收尾按**增量**还原 ⇒ 真实存档里也能跑）
    private int chunksBefore;
    private int safeBefore;
    private int activeBefore;
    private BlockPos entryFoot;

    // 区域状态的快照（生产 Job 用例要把补种/苗/基线挪开，收尾复原 —— 否则会污染别的步）
    private List<BlockPos> pendingBefore = List.of();
    private List<BlockPos> saplingsBefore = List.of();
    private int baselineBefore;
    private boolean derivedBefore;
    private boolean regionStateSnapshotted;

    private RegionLumberJob job;
    private boolean observedZoneMidFlight;
    private int jobStartTick;

    /** 授权面用例真的写过世界的格子（原状态快照 ⇒ 收尾逐格还原 + 销账本条目）。 */
    private final Map<BlockPos, BlockState> touched = new LinkedHashMap<>();

    public JobRegionCheckTask(BotPlayer bot, ServerPlayer observer, ScopeBuffer scope) {
        this.bot = bot;
        this.observer = observer;
        this.scope = scope;
    }

    @Override
    public String taskName() {
        return "JobRegionCheck";
    }

    @Override
    public TaskTarget target() {
        return TaskTarget.block(AREA_START);
    }

    @Override
    public String failureReason() {
        return String.join(" | ", failures);
    }

    @Override
    public String terminalReason() {
        return done ? (failures.isEmpty() ? "passed" : "failed") : "";
    }

    @Override
    public Task.Status tick() {
        if (done) {
            return failures.isEmpty() ? Task.Status.DONE : Task.Status.FAILED;
        }
        if (++ticks > BUDGET_TICKS) {
            check("门禁必须在预算内跑完（" + BUDGET_TICKS + " tick）", false);
            return finish();
        }
        ServerLevel level = bot.serverLevel();
        AreaData zones = AreaData.get(level.getServer());
        switch (phase) {
            case PREPARE -> preparePhase(level, zones);
            case GEOMETRY -> geometryPhase(level, zones);
            case DECLARE -> declarePhase(level);
            case SCOPE_LIFECYCLE -> scopeLifecyclePhase(level);
            case OVERLAY -> overlayPhase(level, zones);
            case SAFE_OVERLAY -> safeOverlayPhase(level, zones);
            case AUTHORITY -> authorityPhase(level, zones);
            case JOB_SETUP -> {
                if (goTo(AREA2_START)) {
                    settle = 0;
                    phase = Phase.JOB_OK;
                }
            }
            case JOB_OK -> jobOkPhase(level);
            case JOB_SAFE_SETUP -> {
                if (goTo(AREA_START)) {
                    settle = 0;
                    phase = Phase.JOB_SAFE;
                }
            }
            case JOB_SAFE -> jobSafePhase(level);
            case CLEANUP -> {
                if (goTo(entryFoot)) {
                    cleanupPhase(level, zones);
                }
            }
            default -> {
                return finish();
            }
        }
        return Task.Status.RUNNING;
    }

    // ==================== 相位 ====================

    private void preparePhase(ServerLevel level, AreaData zones) {
        if (settle == 0) {
            chunksBefore = zones.claimedChunkCount();
            safeBefore = zones.safeChunkCount();
            activeBefore = JobRegionRegistry.activeCount(level.getServer());
            entryFoot = bot.blockPosition();
            teleport(bot, AREA_START);
            settle = 1;
            return;
        }
        if (!FixturePremise.settledOnGround(bot, settle)) {
            settle++;
            return;
        }
        BlockPos foot = bot.blockPosition();
        check("前提：专用孤立区落脚（脚位=" + foot.toShortString() + "，脚下方块="
                        + level.getBlockState(foot.below()).getBlock().getName().getString() + "）",
                !level.getBlockState(foot.below()).isAir());
        check("前提：本步有**打开的任务作用域**（任务区只活在作用域里；scope="
                        + WorldModLedger.currentScope(level.getServer(), bot.getUUID()) + "）",
                WorldModLedger.currentScope(level.getServer(), bot.getUUID()) != null);
        boolean clean = true;
        for (int chunkX : AREA_CHUNKS_X) {
            clean &= !zones.isClaimed(level, new BlockPos(chunkX << 4, FOOT_Y, AREA_CHUNK_Z << 4));
        }
        check("前提：三个测试区块（2200..2202,2200）在地图里**未被认领**（否则用例前提不成立）", clean);
        check("前提：本 bot 名下**没有残留任务区**（用例从干净状态开始）",
                JobRegionRegistry.jobRegionOf(level.getServer(), bot.getUUID()) == null);
        settle = 0;
        phase = Phase.GEOMETRY;
    }

    /** ① 派生几何：工作区域（方块级）⇒ 任务区（区块级最小覆盖），**单向**、Y 无关、纯函数。 */
    private void geometryPhase(ServerLevel level, AreaData zones) {
        ResourceLocation dimension = level.dimension().location();
        WorkingArea area = new WorkingArea(AREA_MIN_X, AREA_MIN_Z, AREA_MAX_X, AREA_MAX_Z);
        Set<Long> cover = area.chunkCover();
        // **独立口径**：把工作区域里**每一格方块**列出来，各自映射到区块 —— 这条路径与
        // `chunkCoverOf(WorkingArea)` 的矩形算术完全无关 ⇒ 等式成立才证明"最小覆盖"算对了。
        Set<Long> fromBlocks = new LinkedHashSet<>();
        List<BlockPos> blocks = new ArrayList<>();
        for (int x = AREA_MIN_X; x <= AREA_MAX_X; x++) {
            for (int z = AREA_MIN_Z; z <= AREA_MAX_Z; z++) {
                BlockPos pos = new BlockPos(x, FOOT_Y, z);
                blocks.add(pos);
                fromBlocks.add(JobRegionRegistry.chunkKey(pos));
            }
        }
        check("几何：矩形工作区域 " + area.describe() + "（" + area.areaXZ() + " 格）的**区块最小覆盖**"
                        + " == 逐方块枚举所占的区块集合（两条独立路径等式；blocks=" + blocks.size()
                        + " ⇒ chunks=" + cover.size() + "）",
                cover.equals(fromBlocks));
        check("几何：集合口径 chunkCoverOf(Collection<BlockPos>) 与矩形口径给出**同一集合**",
                JobRegionRegistry.chunkCoverOf(blocks).equals(cover));
        check("几何：**最小性**（覆盖里没有多余区块 —— 每个区块都真的被工作区域的方块命中）",
                fromBlocks.size() == cover.size());
        check("几何：跨区块边界算对了（x " + AREA_MIN_X + ".." + AREA_MAX_X + " ⇒ 区块 "
                        + AREA_CHUNKS_X[0] + ".." + AREA_CHUNKS_X[AREA_CHUNKS_X.length - 1]
                        + " × z " + AREA_CHUNK_Z + "，恰好 3 个）",
                cover.size() == 3
                        && cover.contains(ChunkPos.asLong(2200, AREA_CHUNK_Z))
                        && cover.contains(ChunkPos.asLong(2201, AREA_CHUNK_Z))
                        && cover.contains(ChunkPos.asLong(2202, AREA_CHUNK_Z)));
        check("几何：**忽略 Y**（同一 XZ 上 y=" + FOOT_Y + " 与 y=300 落在同一区块 ⇒ 与保护区同一口径）",
                JobRegionRegistry.chunkKey(new BlockPos(AREA_MIN_X, FOOT_Y, AREA_MIN_Z))
                        == JobRegionRegistry.chunkKey(new BlockPos(AREA_MIN_X, 300, AREA_MIN_Z)));
        WorkingArea reversed = new WorkingArea(AREA_MAX_X, AREA_MAX_Z, AREA_MIN_X, AREA_MIN_Z);
        check("几何：两个角**反过来写** ⇒ 同一个区域（`equals` 稳定 ⇒ 「重划同一个区」可判幂等）",
                reversed.equals(area) && reversed.chunkCover().equals(cover));
        WorkingArea single = new WorkingArea(AREA2_MIN_X, AREA2_MIN_Z, AREA2_MAX_X, AREA2_MAX_Z);
        check("几何：1 个区块大小的工作区域 ⇒ 恰好 1 个区块（相区 2 用）", single.chunkCover().size() == 1);
        check("几何：派生是**纯函数**（保护区/安全区计数在几何调用前后未变）",
                zones.claimedChunkCount() == chunksBefore && zones.safeChunkCount() == safeBefore);
        findings.add("geometry area=" + area.describe() + " blocks=" + area.areaXZ()
                + " chunks=" + cover.size() + "[" + JobRegionRegistry.describeChunks(cover) + "]");
        phase = Phase.DECLARE;
    }

    /** ② 声明 / 幂等 / 换区 / 没有作用域 ⇒ NO_SCOPE。 */
    private void declarePhase(ServerLevel level) {
        var server = level.getServer();
        UUID owner = bot.getUUID();
        ResourceLocation dimension = level.dimension().location();
        WorkingArea area = new WorkingArea(AREA_MIN_X, AREA_MIN_Z, AREA_MAX_X, AREA_MAX_Z);
        String scopeId = WorldModLedger.currentScope(server, owner);
        JobRegionRegistry.Result declared = JobRegionRegistry.declare(server, owner, "task_zone_fixture", area, dimension, false);
        check("声明：工作区域 ⇒ 任务区 成功（DECLARED，chunks=3）",
                declared.status() == JobRegionRegistry.Declare.DECLARED
                        && declared.jobRegion() != null && declared.jobRegion().chunks().size() == 3);
        check("声明：任务区的 scopeId = **当前任务作用域**（随 scope 生灭的唯一键）",
                scopeId != null && declared.jobRegion() != null && scopeId.equals(declared.jobRegion().scopeId()));
        JobRegionRegistry.JobRegion zone = JobRegionRegistry.jobRegionOf(server, owner);
        check("查询：jobRegionOf(bot) 命中，且 covers 区内格 / 不 covers 区外格（O(1) 区块查表）",
                zone != null && zone.covers(AREA_START) && !zone.covers(AREA2_START));
        check("查询：jobRegionAt(level, 区内格) 命中 —— 这就是将来「这一格要不要提权」的查表入口",
                JobRegionRegistry.jobRegionAt(level, AREA_START) != null);
        check("查询：jobRegionAt(level, 区外格) = null（不越界覆盖）",
                JobRegionRegistry.jobRegionAt(level, OUTSIDE_POS) == null);
        JobRegionRegistry.Result again = JobRegionRegistry.declare(server, owner, "task_zone_fixture", area, dimension, false);
        check("幂等：同一作用域 + 同一工作区域 ⇒ ALREADY，且生效任务区条数不变",
                again.status() == JobRegionRegistry.Declare.ALREADY
                        && JobRegionRegistry.activeCount(server) == activeBefore + 1);
        WorkingArea area2 = new WorkingArea(AREA2_MIN_X, AREA2_MIN_Z, AREA2_MAX_X, AREA2_MAX_Z);
        JobRegionRegistry.Result replaced = JobRegionRegistry.declare(server, owner, "task_zone_fixture", area2, dimension, false);
        JobRegionRegistry.JobRegion afterReplace = JobRegionRegistry.jobRegionOf(server, owner);
        check("换区：同一任务换工作区域 ⇒ REPLACED，新覆盖 1 区块、**旧区块不再被覆盖**"
                        + "（重派生 ≠ 反向裁剪工作区域）",
                replaced.status() == JobRegionRegistry.Declare.REPLACED
                        && afterReplace != null && afterReplace.chunks().size() == 1
                        && !afterReplace.covers(AREA_START));
        UUID stranger = UUID.nameUUIDFromBytes("task-zone-fixture-stranger".getBytes());
        JobRegionRegistry.Result noScope = JobRegionRegistry.declare(server, stranger,
                "task_zone_fixture", area, dimension, false);
        check("⛔没有任务作用域 ⇒ NO_SCOPE 且**不落库**（不许在任务之外造授权封套）",
                noScope.status() == JobRegionRegistry.Declare.NO_SCOPE
                        && JobRegionRegistry.jobRegionOf(server, stranger) == null
                        && JobRegionRegistry.activeCount(server) == activeBefore + 1);
        phase = Phase.SCOPE_LIFECYCLE;
    }

    /** ③ 生命周期：**随 scopeId 生灭** —— 作用域收尾 ⇒ 权威自动消失（不靠谁记得来关）。 */
    private void scopeLifecyclePhase(ServerLevel level) {
        var server = level.getServer();
        UUID fake = UUID.nameUUIDFromBytes("task-zone-fixture-owner".getBytes());
        ResourceLocation dimension = level.dimension().location();
        WorkingArea area = new WorkingArea(AREA_MIN_X, AREA_MIN_Z, AREA_MAX_X, AREA_MAX_Z);
        String scopeA = WorldModLedger.openScope(server, fake, "task_zone_fixture");
        JobRegionRegistry.Result declared = JobRegionRegistry.declare(server, fake, "task_zone_fixture", area, dimension, false);
        check("生命周期：作用域内声明成功（独立 owner，不与本步 bot 的作用域互相干扰）",
                declared.status() == JobRegionRegistry.Declare.DECLARED
                        && JobRegionRegistry.jobRegionOf(server, fake) != null);
        WorldModLedger.closeScope(server, fake);
        check("⭐生命周期：**作用域一收尾 ⇒ 任务区权威立即消失**（jobRegionOf=null；"
                        + "终态/被替换/显式打断都走这条路，不需要任何调用方记得来关）",
                JobRegionRegistry.jobRegionOf(server, fake) == null);
        // ⚠️ 顺序有意为之：**先重开作用域、再问 authority**（中间不做任何会 prune 的事）——
        // 否则"新 scope 无授权"这条可能因为**过期条目被 prune 掉了**而通过（对，但是**侥幸通过**：
        // 它证明不了"新作用域不继承旧任务区"这件事本身）。反向对照（`jobRegionOf` 退回按 owner 找）
        // 会同时打红上面两条 —— 这就是本条存在的原因。
        String scopeB = WorldModLedger.openScope(server, fake, "task_zone_fixture");
        check("生命周期：重开作用域**不继承**上一个作用域的任务区（旧条目还在表里也不给权威）",
                !scopeB.equals(scopeA) && JobRegionRegistry.jobRegionOf(server, fake) == null);
        JobRegionRegistry.Result redeclared = JobRegionRegistry.declare(server, fake, "task_zone_fixture", area, dimension, false);
        check("生命周期：新作用域里必须**重新声明**（DECLARED，而不是 ALREADY/REPLACED ⇒ 证明旧区没被继承）",
                redeclared.status() == JobRegionRegistry.Declare.DECLARED);
        check("生命周期：**过期条目被 prune 清掉**（进程内表不会无限长：旧 scope 的条目 + 新 scope 的 1 条"
                        + " + 本 bot 自己的 1 条）",
                JobRegionRegistry.activeCount(server) == activeBefore + 2);
        WorldModLedger.closeScope(server, fake);
        check("生命周期：收尾后只剩本 bot 自己的那条（fake owner 的两条都已无主 ⇒ 清掉）",
                JobRegionRegistry.activeCount(server) == activeBefore + 1);
        phase = Phase.OVERLAY;
    }

    /** ④ 覆盖规则：任务区**可以**覆盖保护区**父类**；且**本片不改任何权限行为**。 */
    private void overlayPhase(ServerLevel level, AreaData zones) {
        var server = level.getServer();
        UUID owner = bot.getUUID();
        ResourceLocation dimension = level.dimension().location();
        int claimed = 0;
        for (int chunkX : AREA_CHUNKS_X) {
            if (zones.claim(level, chunkX, AREA_CHUNK_Z)) {
                claimed++;
            }
        }
        check("覆盖前提：先把三个区块认领为**保护区父类**（本次新增 " + claimed + " 个）", claimed == 3);
        JobRegionRegistry.release(WorldModLedger.currentScope(server, owner));
        WorkingArea area = new WorkingArea(AREA_MIN_X, AREA_MIN_Z, AREA_MAX_X, AREA_MAX_Z);
        JobRegionRegistry.Result declared = JobRegionRegistry.declare(server, owner, "task_zone_fixture", area, dimension, false);
        check("⭐覆盖：任务区**可以覆盖保护区父类**（这些区块全在保护区内，声明照旧成功）",
                declared.status() == JobRegionRegistry.Declare.DECLARED
                        && declared.jobRegion() != null && declared.jobRegion().chunks().size() == 3);
        JobRegionRegistry.JobRegion zone = JobRegionRegistry.jobRegionOf(server, owner);
        check("⭐覆盖：同一格上「保护区=true」与「任务区覆盖=true」**同时成立**"
                        + "（覆盖 = 对**任务授权**的让步，不是取消保护）",
                zones.isClaimed(level, AREA_START) && zone != null && zone.covers(AREA_START));
        check("⚠本片**不改权限**：破坏闸门对保护区块**照旧拒绝**"
                        + "（protectionReason=protected_area ⇒ 红线一个字没动）",
                "protected_area".equals(zones.protectionReason(level, AREA_START)));
        phase = Phase.SAFE_OVERLAY;
    }

    /**
     * ⑤ **安全区退化后的覆盖规则**（⛔ 2026-10-01 用户裁定；**取代**原 `CONFLICT` ＋ `DEGRADE` 两个相位
     * —— 那两条断言的是一个**已被删除**的行为）。
     *
     * <p>安全区**退化**为「保护区上的一个标记位」、与保护区**同权限**
     * ⇒「任务区不得覆盖安全区」这条**失去了立足点**（用户逐字：「**这句话在安全区退化后就没有意义了**」）
     * ⇒ ⭐ 工作区域**压在安全区上照旧声明成功**；覆盖规则今天只剩「**任何区域 → `job` 区 ⛔拒绝**」。
     */
    private void safeOverlayPhase(ServerLevel level, AreaData zones) {
        var server = level.getServer();
        UUID owner = bot.getUUID();
        ResourceLocation dimension = level.dimension().location();
        WorkingArea area = new WorkingArea(AREA_MIN_X, AREA_MIN_Z, AREA_MAX_X, AREA_MAX_Z);
        check("覆盖规则前提：中间区块（" + CONFLICT_CHUNK_X + "," + AREA_CHUNK_Z
                        + "）已被声明为**安全区**（保护区上的标记位）",
                zones.declareSafe(level, CONFLICT_CHUNK_X, AREA_CHUNK_Z)
                        == AreaData.SafeDeclare.DECLARED);
        int safeNow = zones.safeChunkCount();
        int claimedNow = zones.claimedChunkCount();
        JobRegionRegistry.release(WorldModLedger.currentScope(server, owner));
        JobRegionRegistry.Result overlay = JobRegionRegistry.declare(server, owner, "task_zone_fixture", area, dimension, false);
        check("⭐覆盖规则：工作区域**压在安全区上照样声明成功**（同权限 ⇒ "
                        + "「任务区不得覆盖安全区」已删除）｜status=" + overlay.status(),
                overlay.status() == JobRegionRegistry.Declare.DECLARED && overlay.active());
        check("⭐覆盖规则：生效的任务区**真的覆盖了**那个安全区区块（" + CONFLICT_CHUNK_X + "," + AREA_CHUNK_Z + "）",
                overlay.jobRegion() != null
                        && overlay.jobRegion().covers(new BlockPos(CONFLICT_CHUNK_X << 4, FOOT_Y, AREA_CHUNK_Z << 4)));
        check("⭐覆盖规则：**不裁剪** —— 工作区域（玩家意图）一字未动（blocks=" + area.areaXZ()
                        + " chunks=" + area.chunkCover().size() + "）",
                area.areaXZ() == 41L * 11L && area.chunkCover().size() == 3);
        check("⭐覆盖规则：安全区/保护区计数**一字未动**（声明任务区不改玩家的区域声明；safe=" + zones.safeChunkCount()
                        + " claimed=" + zones.claimedChunkCount() + "）",
                zones.safeChunkCount() == safeNow && zones.claimedChunkCount() == claimedNow);
        zones.claim(level, OUTSIDE_CHUNK_X, OUTSIDE_CHUNK_Z);
        zones.declareSafe(level, OUTSIDE_CHUNK_X, OUTSIDE_CHUNK_Z);
        check("覆盖规则只看向交集：区外的安全区（" + OUTSIDE_CHUNK_X + "," + OUTSIDE_CHUNK_Z
                        + "）不进任务区（覆盖区块数仍是 " + overlay.jobRegion().chunks().size() + "）",
                overlay.jobRegion() != null && overlay.jobRegion().chunks().size() == 3);
        JobRegionRegistry.release(WorldModLedger.currentScope(server, owner));
        check("相位收尾：本 bot 名下无生效任务区（下面的授权面用例从干净状态开始）",
                JobRegionRegistry.jobRegionOf(server, owner) == null);
        phase = Phase.AUTHORITY;
    }

    /**
     * ⑤′ ⭐ **区域级授权面**（权限阶梯 `L0/L1/L2`，`D-338` 附注七②③）——夹具直接问**生产判据**
     * （`AreaPermission`），并用**真的世界写入**（`BlockInteraction` 的两条批量路径）验"放行/拦下"。
     *
     * <p>六组：① 无任务区 ⇒ **逐字回归 `protected_area`**（且放置今天**本来就该被拦** —— 补上的缺口）；
     * ② `L0` 只读 ⇒ 破坏/放置都拒；③ `L1` ⇒ 只许**临时**放置、**≤8 次**、**不许破坏**；
     * ④ `L2` 工作面 ⇒ 目标内（`EXPECTED_TARGET`）/ 目标外（`PATH_ACCESS`）破坏 + 临时放置都放行；
     * ⑤ **越界与安全区**（退化后两者都只是「认领但未被任务区覆盖」）⇒ **同一个码** `protected_area`；
     * ⑥ **野外在 `L0` 期间照旧可写**（L0 冻结野外 = 错）。⑦ **刀 2「宝贵 → 位置判据」**（`D-565` ⑤）：
     * **区内任何 `hasBlockEntity()` 一律不可挖掘** —— 判别式 = 同一位置换方块（箱子/石头）＋ 同一方块
     * 换位置（区内/野外）。
     */
    private void authorityPhase(ServerLevel level, AreaData zones) {
        var server = level.getServer();
        UUID owner = bot.getUUID();
        ResourceLocation dimension = level.dimension().location();
        WorkingArea authArea = new WorkingArea(AUTH_MIN_X, AUTH_MIN_Z, AUTH_MAX_X, AUTH_MAX_Z);
        check("授权面前提：四个测试格分属不同区块（区内 2200,2200 / 别的区块 2202,2200 / 安全区 2201,2200 / 野外 2212,2212）",
                JobRegionRegistry.chunkKey(AUTH_INSIDE) == ChunkPos.asLong(2200, AREA_CHUNK_Z)
                        && JobRegionRegistry.chunkKey(AUTH_OTHER_CHUNK) == ChunkPos.asLong(2202, AREA_CHUNK_Z)
                        && JobRegionRegistry.chunkKey(AUTH_SAFE) == ChunkPos.asLong(CONFLICT_CHUNK_X, AREA_CHUNK_Z)
                        && zones.isClaimed(level, AUTH_INSIDE) && zones.isClaimed(level, AUTH_OTHER_CHUNK)
                        && zones.isSafe(level, AUTH_SAFE) && !zones.isClaimed(level, AUTH_WILDERNESS));

        // ① 无任务区：破坏码**逐字回归** `protected_area`；放置**被拦**（今天这条闸门缺失 ⇒ 本片补上）
        JobRegionRegistry.release(WorldModLedger.currentScope(server, owner));
        check("②无任务区：破坏 `EXPECTED_TARGET` ⇒ 拒绝码**逐字仍是 `protected_area`**（既有码/文档/夹具都按它写）",
                "protected_area".equals(AreaPermission.breakRefusal(level, owner, AUTH_INSIDE,
                        WriteReason.EXPECTED_TARGET)));
        boolean placedWithoutZone = placeThroughAction(level, AUTH_INSIDE,
                grant("walk-return", WriteReason.STEP_PLACEMENT));
        check("②无任务区：**保护区内放置被拦**（这条闸门 `D-338` 核对表里记为缺口，本片补上）"
                        + "—— 世界未变 = " + level.getBlockState(AUTH_INSIDE).isAir(),
                !placedWithoutZone && level.getBlockState(AUTH_INSIDE).isAir());

        // ② L0 只读（`walk-return` ⇒ TRAVERSAL ⇒ L0）
        JobRegionRegistry.Result l0 = JobRegionRegistry.declare(server, owner, "walk-return", authArea, dimension, false);
        check("③`L0`：等级由 `WritePolicyMatrix` 解析 = L0（只读），且写入元数据",
                l0.status() == JobRegionRegistry.Declare.DECLARED
                        && l0.jobRegion().level() == WritePolicyMatrix.Level.L0_READ_ONLY);
        check("③`L0`：破坏 ⇒ `zone_read_only`",
                "zone_read_only".equals(AreaPermission.breakRefusal(level, owner, AUTH_INSIDE,
                        WriteReason.EXPECTED_TARGET)));
        check("③`L0`：放置 ⇒ `zone_read_only`，且真的没写进世界",
                "zone_read_only".equals(AreaPermission.placeRefusal(level, owner, AUTH_INSIDE,
                        WriteReason.STEP_PLACEMENT))
                        && !placeThroughAction(level, AUTH_INSIDE,
                                grant("walk-return", WriteReason.STEP_PLACEMENT))
                        && level.getBlockState(AUTH_INSIDE).isAir());
        // ⑤′ 野外不受 L0 影响（区内配额/等级**不该**冻结任务在野外的写入）
        boolean placedWilderness = placeThroughAction(level, AUTH_WILDERNESS,
                grant("walk-return", WriteReason.STEP_PLACEMENT));
        check("⑥`L0` 只冻结**区内**：同一任务在**野外**放置照旧成功（`NOT_GATED`；"
                        + "把 ≤8 做成作用域级预算上限就会连野外一起清零 —— 那是错的）",
                placedWilderness && !level.getBlockState(AUTH_WILDERNESS).isAir()
                        && AreaPermission.placeRefusal(level, owner, AUTH_WILDERNESS,
                                WriteReason.STEP_PLACEMENT) == null);

        // ③ L1 临时脚手架（`craft-station` ⇒ CRAFT ⇒ L1）
        JobRegionRegistry.Result l1 = JobRegionRegistry.declare(server, owner, "craft-station", authArea, dimension, false);
        // 同一个作用域换任务 ⇒ 是 REPLACED（不是 DECLARED）；等级仍然由矩阵解析 ⇒ 断言用 active()
        check("④`L1`：等级 = L1（临时脚手架）", l1.active()
                && l1.jobRegion().level() == WritePolicyMatrix.Level.L1_SCAFFOLD);
        check("④`L1`：**非临时**放置理由 ⇒ `zone_place_not_scaffold`（如 `REGION_REPLANT` 是计划内永久）",
                "zone_place_not_scaffold".equals(AreaPermission.placeRefusal(level, owner, AUTH_INSIDE,
                        WriteReason.REGION_REPLANT)));
        check("④`L1`：**破坏** ⇒ `zone_break_not_allowed`（临时脚手架档不许破坏）",
                "zone_break_not_allowed".equals(AreaPermission.breakRefusal(level, owner, AUTH_INSIDE,
                        WriteReason.EXPECTED_TARGET)));
        int quotaPlaced = 0;
        for (int i = 0; i < Quota.L1_MAX_PLACES; i++) {
            if (placeThroughAction(level, new BlockPos(QUOTA_BASE_X + i, FOOT_Y, QUOTA_Z),
                    grant("craft-station", WriteReason.STEP_PLACEMENT))) {
                quotaPlaced++;
            }
        }
        int ninthX = QUOTA_BASE_X + Quota.L1_MAX_PLACES;
        boolean ninth = placeThroughAction(level, new BlockPos(ninthX, FOOT_Y, QUOTA_Z),
                grant("craft-station", WriteReason.STEP_PLACEMENT));
        check("④`L1`：区内**≤8 次**放置配额真的生效（前 " + Quota.L1_MAX_PLACES + " 次落地=" + quotaPlaced
                        + "，第 " + (Quota.L1_MAX_PLACES + 1) + " 次被拒=" + !ninth
                        + "，拒绝码=" + AreaPermission.placeRefusal(level, owner, new BlockPos(ninthX, FOOT_Y, QUOTA_Z),
                                WriteReason.STEP_PLACEMENT) + "）",
                quotaPlaced == Quota.L1_MAX_PLACES && !ninth
                        && "zone_place_quota".equals(AreaPermission.placeRefusal(level, owner,
                                new BlockPos(ninthX, FOOT_Y, QUOTA_Z), WriteReason.STEP_PLACEMENT))
                        && level.getBlockState(new BlockPos(ninthX, FOOT_Y, QUOTA_Z)).isAir());
        check("④`L1`：配额计数落在**任务区**（scope）上，且只数区内放置（count="
                        + JobRegionRegistry.inJobRegionPlaceCount(l1.jobRegion().scopeId()) + "）",
                JobRegionRegistry.inJobRegionPlaceCount(l1.jobRegion().scopeId()) == Quota.L1_MAX_PLACES);

        // ④ L2 工作面（`region_lumber` ⇒ LUMBER ⇒ L2）
        // ⭐ `D-338` 附注十四：`region_lumber`(L2) 在保护区内**只对玩家显式发起**生效 ⇒ 这里传 `true`
        JobRegionRegistry.Result l2 = JobRegionRegistry.declare(server, owner, "region_lumber", authArea, dimension, true);
        check("⑤`L2`：等级 = L2（工作面）", l2.active()
                && l2.jobRegion().level() == WritePolicyMatrix.Level.L2_WORKFACE);
        check("⑤`L2`：**目标内**（`EXPECTED_TARGET`）破坏 ⇒ 放行",
                AreaPermission.breakRefusal(level, owner, AUTH_INSIDE, WriteReason.EXPECTED_TARGET) == null);
        check("⑤`L2`：**目标外**（`PATH_ACCESS`，清障）破坏 ⇒ 也放行（其后由授权/预算/账本管）",
                AreaPermission.breakRefusal(level, owner, AUTH_INSIDE, WriteReason.PATH_ACCESS) == null);
        check("⑤`L2`：临时放置 ⇒ 放行（且 `L1` 的 8 次配额**不再适用**）",
                AreaPermission.placeRefusal(level, owner, AUTH_INSIDE, WriteReason.STEP_PLACEMENT) == null
                        && JobRegionRegistry.inJobRegionPlaceCount(l2.jobRegion().scopeId()) == 0);
        boolean placedInZone = placeThroughAction(level, AUTH_INSIDE,
                grant("region_lumber", WriteReason.STEP_PLACEMENT));
        check("⑤`L2`：**真的写进了世界**（放置成功 + 该格现在是圆石）",
                placedInZone && level.getBlockState(AUTH_INSIDE).is(Blocks.COBBLESTONE));
        boolean brokeInZone = BlockInteraction.breakForBulkEdit(bot, level, AUTH_INSIDE, false,
                grant("region_lumber", WriteReason.EXPECTED_TARGET));
        check("⑤`L2`：**真的破坏成功**（世界事实 = 该格又空了）",
                brokeInZone && level.getBlockState(AUTH_INSIDE).isAir());

        // ⑤ 越界 / 安全区 / 过期作用域：一律拒（授权不许泄漏到区外）
        check("⑦越界：同属保护区但**不被任务区覆盖**的区块 ⇒ 仍拒 `protected_area`",
                "protected_area".equals(AreaPermission.breakRefusal(level, owner, AUTH_OTHER_CHUNK,
                        WriteReason.EXPECTED_TARGET)));
        check("⑦安全区**同权限**（2026-10-01 安全区退化后）：安全区格与保护区格走**同一条**判据 —— "
                        + "这里任务区**不覆盖**它 ⇒ 与「越界」**同一个码** `protected_area`"
                        + "（⛔ 原 `protected_safe_zone` 那一档已删除）",
                "protected_area".equals(AreaPermission.breakRefusal(level, owner, AUTH_SAFE,
                        WriteReason.EXPECTED_TARGET)));
        UUID stranger = UUID.nameUUIDFromBytes("task-zone-fixture-stranger".getBytes());
        check("⑦别的 bot（owner）在自己区里拿不到权限：owner 不匹配 ⇒ `protected_area`",
                "protected_area".equals(AreaPermission.breakRefusal(level, stranger, AUTH_INSIDE,
                        WriteReason.EXPECTED_TARGET)));
        // 等级解析的单一出处（含"L3 只能由玩家显式取得"）
        check("⑧等级来源（`WritePolicyMatrix` 单一出处）：`walk-return`/`mine-plan` ⇒ L0；`craft-station` ⇒ L1；"
                        + "`region_lumber` ⇒ L2",
                WritePolicyMatrix.areaLevel("walk-return", false) == WritePolicyMatrix.Level.L0_READ_ONLY
                        && WritePolicyMatrix.areaLevel("mine-plan", false) == WritePolicyMatrix.Level.L0_READ_ONLY
                        && WritePolicyMatrix.areaLevel("craft-station", false) == WritePolicyMatrix.Level.L1_SCAFFOLD
                        && WritePolicyMatrix.areaLevel("region_lumber", false) == WritePolicyMatrix.Level.L2_WORKFACE);
        check("⑧`L3` 只能由**玩家显式**取得：`road-build` 在非玩家驱动身份下**降级 L2**，玩家驱动才是 L3",
                WritePolicyMatrix.areaLevel("road-build", false) == WritePolicyMatrix.Level.L2_WORKFACE
                        && WritePolicyMatrix.areaLevel("road-build", true) == WritePolicyMatrix.Level.L3_FULL);
        // ⑦ ⭐ **第三处消费：候选扫描**（"一个判据三处消费"的第三条腿）—— 在**被认领的区块**里
        // 手搭一棵小树（3 格原木 ≥ `TreeScanner.MIN_LOGS`），直接问**生产候选源**：
        // 没有任务区 ⇒ 候选期就该被保护区拒；`L2` 覆盖 ⇒ 不再以保护区为由拒（"基地里的林场"用法）；
        // `L0` ⇒ 候选期仍拒（`zone_read_only`；挖矿走同一条路，`MINING` 也是 L0）。
        for (int dy = 0; dy < 3; dy++) {
            setBlockTracked(level, AUTH_TREE_BASE.above(dy), Blocks.OAK_LOG.defaultBlockState());
        }
        var treeSpec = JobDeclaration.harvestUnits(AUTH_TREE_BASE, 6, 1, 200);
        JobRegionRegistry.release(WorldModLedger.currentScope(server, owner));
        List<String> noZoneRejected = new LumberCandidateSource().candidates(bot, treeSpec).rejected();
        check("⑨候选扫描（第三处消费）：**无任务区** ⇒ 被认领区块里的树在候选期就被拒（`:protected_area`）",
                hasCode(noZoneRejected, "protected_area"));
        JobRegionRegistry.declare(server, owner, "region_lumber", authArea, dimension, true);
        var l2Candidates = new LumberCandidateSource().candidates(bot, treeSpec);
        check("⑨候选扫描：`L2` 任务区覆盖 ⇒ 同一棵树**不再以保护区为由被拒**（= 保护区里的目标成为合法候选；"
                        + "可达性等其它理由另行判定）｜rejected=" + l2Candidates.rejected(),
                !hasCode(l2Candidates.rejected(), "protected_area"));
        JobRegionRegistry.declare(server, owner, "walk-return", authArea, dimension, false);
        List<String> l0Rejected = new LumberCandidateSource().candidates(bot, treeSpec).rejected();
        check("⑨候选扫描：`L0` 任务区覆盖 ⇒ 候选期仍拒，且理由码换成 `zone_read_only`"
                        + "（与矿侧同一条路：`MINING` 也是 L0）",
                hasCode(l0Rejected, "zone_read_only") && !hasCode(l0Rejected, "protected_area"));

        // ⑧ 日志卫生（**客户端实测逼出来的**）：规划期谓词会反复问同一格 ⇒ 留痕必须去重，否则刷屏
        ModifyAudit.clearAllowAudit();
        int before = ModifyAudit.allowLoggedCount();
        JobRegionRegistry.declare(server, owner, "region_lumber", authArea, dimension, true);
        boolean allAllowed = true;
        for (int i = 0; i < 100; i++) {
            // 走**动作层那条**（`regionRefusal` 才留痕；`breakRefusal` 是纯判定入口，刻意不打印）
            allAllowed &= AreaPermission.regionRefusal(level, owner, AUTH_INSIDE, "protected_area",
                    WriteReason.EXPECTED_TARGET, AreaPermission.Act.BREAK) == null;
        }
        check("⑩日志卫生：**同一格 + 同一理由问 100 次 ⇒ 只留痕 1 条**（判定每次都一致；"
                        + "客户端实测里规划期谓词 50 ms 问了 5 次同一格 ⇒ 不去重会刷屏）"
                        + "｜logged=" + (ModifyAudit.allowLoggedCount() - before),
                allAllowed && ModifyAudit.allowLoggedCount() - before == 1);
        ModifyAudit.clearAllowAudit();

        // ⑨ ⭐ **第四处消费：规划/执行期的能力闸门**（`CapabilityGate.Facts`）——
        //    客户端实测暴露我上一片**漏接了这一处**：保护区里 `PILLAR`（"垫一格上去"）被裸保护区判据拒了
        //    **144 次**、全轮零放置 ⇒ 高树最高一格够不到、直接跳过（离线对照：同一场景无认领时 `places=9`、`7/7`）。
        JobRegionRegistry.declare(server, owner, "region_lumber", authArea, dimension, true);
        check("⑨能力闸门（第四处消费）：`L2` 任务区覆盖 ⇒ **放置类移动**（垫脚/`PILLAR`）落点判定放行",
                AreaPermission.movementRefusal(level, owner, AUTH_INSIDE, "protected_area", true) == null);
        check("⑨能力闸门：`L2` 下**破坏类移动**（`PATH_ACCESS` 语义）落点也放行",
                AreaPermission.movementRefusal(level, owner, AUTH_INSIDE, "protected_area", false) == null);
        JobRegionRegistry.declare(server, owner, "craft-station", authArea, dimension, false);
        check("⑨能力闸门：`L1` 允许「垫脚」（临时脚手架）但**不允许破坏类移动**"
                        + "（码=" + AreaPermission.movementRefusal(level, owner, AUTH_INSIDE, "protected_area", false) + "）",
                AreaPermission.movementRefusal(level, owner, AUTH_INSIDE, "protected_area", true) == null
                        && "zone_break_not_allowed".equals(AreaPermission.movementRefusal(level, owner,
                                AUTH_INSIDE, "protected_area", false)));
        JobRegionRegistry.release(WorldModLedger.currentScope(server, owner));
        check("⑨能力闸门：**没有任务区**时逐字仍是 `protected_area`（既有失败码/`ZONE_PROTECTED_AREA` 不变）",
                "protected_area".equals(AreaPermission.movementRefusal(level, owner, AUTH_INSIDE,
                        "protected_area", true)));
        check("⑨能力闸门：**方块/标签黑名单不参与区域授权**（`protected_block` 原样返回）",
                "protected_block".equals(AreaPermission.movementRefusal(level, owner, AUTH_INSIDE,
                        "protected_block", true)));

        // ⑩ ⭐ `D-338` 附注十四：**保护区内，非玩家发起（LLM/未归因）封顶 `L1`**
        //    —— 现场：用户点的一次性砍树被如实拒绝后，LLM 自起 `region_lumber` 把用户保护区里的树砍了（两轮）。
        JobRegionRegistry.declare(server, owner, "region_lumber", authArea, dimension, false);
        String llmBreak = AreaPermission.breakRefusal(level, owner, AUTH_INSIDE, WriteReason.EXPECTED_TARGET);
        check("⑪封顶：**LLM 自起**的 `region_lumber`（声明 L2）在保护区内 ⇒ 破坏被拒 **`zone_break_not_allowed`**"
                        + "（= 「拆不了玩家的方块」），且**不是** `protected_area`（那是「没有任务区」的码）｜code=" + llmBreak,
                "zone_break_not_allowed".equals(llmBreak));
        check("⑪封顶：同一任务区**仍允许临时放置**（「能清障垫脚」）⇒ 只砍掉「拆家」能力",
                AreaPermission.placeRefusal(level, owner, AUTH_INSIDE, WriteReason.STEP_PLACEMENT) == null);
        List<String> llmRejected = new LumberCandidateSource().candidates(bot, treeSpec).rejected();
        check("⑪封顶端到端：LLM 自起的区域任务，保护区里的树在**候选期**就被拒（`:zone_break_not_allowed`）"
                        + "⇒ 不会去砍玩家的树｜rejected=" + llmRejected,
                hasCode(llmRejected, "zone_break_not_allowed"));
        // ⚠️ **诚实纠正**（2026-09-19，`D-341`）：这一条以前还写着"⇒ 真任务会**如实失败**（`no_reachable_candidate`）"
        // —— **客户端实测证明那句是错的**：区域作业把"没权限的树"当成"区域里没有树"、走**待机巡查**，
        // 空转到 `maxTicks=24000`（20 分钟）。现在"如实失败"由 `permissionBlock` 保证
        // （码 = `no_permitted_candidate`），由下面 ⑫ 断言。
        JobRegionRegistry.declare(server, owner, "region_lumber", authArea, dimension, true);
        check("⑪封顶：**玩家显式发起**（命令/物品）时同一格**照旧放行**破坏（`L2`）⇒ 阶梯对玩家不缩水",
                AreaPermission.breakRefusal(level, owner, AUTH_INSIDE, WriteReason.EXPECTED_TARGET) == null);
        JobRegionRegistry.declare(server, owner, "road-build", authArea, dimension, false);
        check("⑪封顶：`L3` 类（修路）非玩家发起 ⇒ 先降级 `L2`、保护区内再封顶 `L1`（两级都只收紧）⇒ 破坏仍被拒",
                "zone_break_not_allowed".equals(AreaPermission.breakRefusal(level, owner, AUTH_INSIDE,
                        WriteReason.EXPECTED_TARGET)));
        JobRegionRegistry.declare(server, owner, "walk-return", authArea, dimension, false);
        check("⑪封顶**只收紧、不放宽**：`L0`（只读）非玩家发起**仍是 `L0`**，放置照样 `zone_read_only`"
                        + "（不许被「封顶」抬成可临时放置）｜code=" + AreaPermission.placeRefusal(level, owner,
                        AUTH_INSIDE, WriteReason.STEP_PLACEMENT),
                "zone_read_only".equals(AreaPermission.placeRefusal(level, owner, AUTH_INSIDE,
                        WriteReason.STEP_PLACEMENT)));
        JobRegionRegistry.declare(server, owner, "region_lumber", authArea, dimension, false);
        check("⑪封顶只作用于**已认领**区块：同一非玩家任务区在**野外**不受影响（`NOT_GATED`，野外写入照旧）",
                AreaPermission.placeRefusal(level, owner, AUTH_WILDERNESS, WriteReason.STEP_PLACEMENT) == null
                        && AreaPermission.breakRefusal(level, owner, AUTH_WILDERNESS,
                                WriteReason.EXPECTED_TARGET) == null);

        // ⑫ ⭐ `D-341`：**"无权" ≠ "没有"** —— 候选扫描把没权限的树丢进 `rejected`、`viable` 里根本没有它，
        //    于是区域作业的世界模型变成"区域里没有树" ⇒ 走**待机巡查等生长**分支（那是为树苗生长设计的
        //    正常机制、也是用户要的"等窗口"）。客户端实测（2026-09-19 19:06）：LLM 自起的 `region_lumber`
        //    在保护区内被封顶 `L1`、5 棵树全 `zone_break_not_allowed` ⇒ `viable=0 inRegion=0` +
        //    `欠树 deficit=5` **空转到 `maxTicks=24000`（20 分钟）**，期间反复唤醒 LLM。
        //    用户口径："任务要如实失败，不能继续跑" ⇒ 作业必须把"树全被**永久**拒绝"判成 `FAILED`。
        List<String> cappedRejected = new LumberCandidateSource().candidates(bot, treeSpec).rejected();
        // **几何盒（§6.9.1①，必须写下来）**：`treeRegion` **以手搭那棵树 `AUTH_TREE_BASE` 为中心**、
        // 水平半径 **4 格**、`baseY = FOOT_Y`（树的基座层）、竖直上界取 `FOOT_Y + 8`
        // ⇒ 判据里的竖直过滤窗口是 `[FOOT_Y-2, FOOT_Y+8]`，锚点在 `FOOT_Y` 的那棵树**落在盒内** ✓。
        // 反面用例的坐标也用 `treeId`（同一棵树）构造 ⇒ "盒内/盒外"是同一条事实的两个方向。
        var treeRegion = new LumberAreaState.Area(
                new WorkingArea(AUTH_TREE_BASE.getX() - 4, AUTH_TREE_BASE.getZ() - 4,
                        AUTH_TREE_BASE.getX() + 4, AUTH_TREE_BASE.getZ() + 4),
                FOOT_Y, 8);
        String blockedMain = RegionLumberJob.permissionBlock(treeRegion, cappedRejected, FOOT_Y + 8);
        check("⑫无权≠没有：**真扫描**里被封顶拒绝的树（`:zone_break_not_allowed`）⇒ `permissionBlock` 报出**主因**"
                        + "（⇒ 区域作业**如实失败** `no_permitted_candidate`，不再当「区域里没树」空转）｜blocked="
                        + blockedMain,
                hasCode(cappedRejected, "zone_break_not_allowed")
                        && blockedMain != null && blockedMain.startsWith("zone_break_not_allowed"));
        String treeId = "tree@" + AUTH_TREE_BASE.getX() + "," + AUTH_TREE_BASE.getY() + ","
                + AUTH_TREE_BASE.getZ();
        check("⑫无权≠没有：**搜索性/策略性**理由不算永久拒绝（`trunk_too_tall`（含带括号后缀）/`not_nearest`/"
                        + "`no_stand`/`null`/空串）⇒ 判据为 `null`（该照旧等生长，不许被误判成失败）",
                RegionLumberJob.permissionBlock(treeRegion, List.of(
                        treeId + ":trunk_too_tall",
                        treeId + ":trunk_too_tall(unreachable=7)",
                        treeId + ":not_nearest",
                        treeId + ":no_stand"), FOOT_Y + 8) == null
                        && !AreaPermission.permanentDenial(null)
                        && !AreaPermission.permanentDenial(""));
        check("⑫无权≠没有：**区域外**的永久拒绝不算本区域的问题（区域外的 `protected_area` ⇒ `null`）",
                RegionLumberJob.permissionBlock(treeRegion,
                        List.of("tree@0,-60,0:protected_area"), FOOT_Y + 8) == null);
        check("⑫无权≠没有：**安全区**（退化后与「越界」同码 `protected_area`）与 `L0` 也只读 ⇒ 都算永久拒绝",
                AreaPermission.permanentDenial("protected_area")
                        && AreaPermission.permanentDenial("zone_read_only"));
        // ⑫ ⚠️ **原先这里有一条"跨写法前提"断言**（受理侧 `JobRequest.Kind.name()` vs 终态侧 `Task.taskName()`
        //    必须归一到同一身份）—— `D-342` 修订后**它已废弃**：身份不再跨边界做字符串匹配
        //    （在飞身份只由 LLM 受理侧写入，别人派活由 `BotManager.beginTask` → `clearAttempt` 清掉）
        //    ⇒ 生产不再依赖"两处拼写一致"。留着它等于断言一个已不存在的约束（死规则），故删除。
        //    对应判据：夹具 `llm_contract/loop_admission_control` 的 `foreign_assignment_not_accounted`。

        // ⑬ ⭐⭐ **刀 2「宝贵 → 位置判据」**（`D-565` ⑤，2026-10-01 用户逐字：「**"宝贵"从"方块类型"
        //    改成"位置"我采纳**」「**挖掘黑名单只是一个临时手段，不能挖黑曜石本来就是它的缺陷**」
        //    「**怎么还在说清障**，应该是**区内不可挖掘**，**容器写入理由不是例外**，
        //    应该就是**任何 `hasBlockEntity()` 一律不可挖掘**，**不可动太广了**」）。
        //    ⚠️ 口径三条（⛔ 写错就是错）：① 拦的是**挖掘（`Act.BREAK`）**，⛔ 不是"不可动"；
        //    ② **容器写入理由不是例外** —— 它**本来就不走这条路**（容器写入过的是
        //    `Quota.consumeContainerWrite`，`WriteReason.CONTAINER_TRANSFER` 的 `Action.BOTH`
        //    是声明性字段、全仓只有策略表自检读它 ⇒ **无需豁免**）；③ ⛔ **不许把这件事叫"清障"**
        //    （该概念已被用户丢弃，`O128` 取 A：只丢讨论口径、⛔ 不动代码）。
        //    **判别式**：① 同一**位置**换方块（箱子 vs 石头）② 同一**方块**换位置（区内 vs 野外）
        //    —— 两个方向都断言，才说得清"判据是位置、不是方块类型"。
        JobRegionRegistry.declare(server, owner, "region_lumber", authArea, dimension, true);   // L2（玩家发起 ⇒ 不封顶）
        setBlockTracked(level, AUTH_CHEST, Blocks.CHEST.defaultBlockState());
        String chestInZone = AreaPermission.breakRefusal(level, owner, AUTH_CHEST, WriteReason.EXPECTED_TARGET);
        setBlockTracked(level, AUTH_CHEST, Blocks.STONE.defaultBlockState());
        String stoneInZone = AreaPermission.breakRefusal(level, owner, AUTH_CHEST, WriteReason.EXPECTED_TARGET);
        setBlockTracked(level, AUTH_CHEST, Blocks.CHEST.defaultBlockState());
        check("⑬位置判据①「**区内不可挖掘**」：**同一格**换成箱子 ⇒ 即便 `L2`（玩家发起 ＋ 明确目标）也拒 "
                        + "**`protected_block_entity`**（⛔ 与理由无关、⛔ 与等级无关）｜箱子=" + chestInZone
                        + " 石头=" + stoneInZone
                        + "（⇒ 同一位置上「石头放行 / 箱子拒」= 判据是**这一格是不是方块实体**）",
                "protected_block_entity".equals(chestInZone) && stoneInZone == null);
        check("⑬位置判据②**判别式**：**同一个方块**（箱子）在**野外**（未认领）⇒ 明确目标挖掘**照旧放行**"
                        + "（`NOT_GATED`；野外由成本模型 ＋ 只读审计治理）｜code="
                        + AreaPermission.breakRefusal(level, owner, AUTH_WILDERNESS, WriteReason.EXPECTED_TARGET),
                AreaPermission.breakRefusal(level, owner, AUTH_WILDERNESS,
                        WriteReason.EXPECTED_TARGET) == null);
        boolean chestBroken = BlockInteraction.breakForBulkEdit(bot, level, AUTH_CHEST, false,
                grant("region_lumber", WriteReason.PATH_ACCESS));
        String chestClearing = AreaPermission.breakRefusal(level, owner, AUTH_CHEST, WriteReason.PATH_ACCESS);
        check("⑬位置判据③**不分策略 ＋ 真的写不进世界**：同一格换**清障**理由（`PATH_ACCESS`）⇒ **同一个码**"
                        + "（code=" + chestClearing + "；⚠️ 这个格子在**区内** ⇒ 拒绝**归因到位置规则**，"
                        + "⛔ 不再是清障策略那条 `block_entity`）；且生产动作层 `breakForBulkEdit` 返回 "
                        + chestBroken + "、箱子还在=" + level.getBlockState(AUTH_CHEST).is(Blocks.CHEST)
                        + "（= 闸门真的接在动作层上，⛔ 不是「没人调用的函数」）",
                "protected_block_entity".equals(chestClearing)
                        && !chestBroken && level.getBlockState(AUTH_CHEST).is(Blocks.CHEST));
        check("⑬位置判据④⛔ **不是「不可动」**：**放置**那一支不看方块实体 —— 同一格的放置判定照旧放行"
                        + "（码=" + AreaPermission.placeRefusal(level, owner, AUTH_CHEST,
                                WriteReason.STEP_PLACEMENT) + "）；两条轴分开：放置看**等级阶梯**、"
                        + "挖掘才看这条**位置**规则",
                AreaPermission.placeRefusal(level, owner, AUTH_CHEST, WriteReason.STEP_PLACEMENT) == null);
        check("⑬位置判据⑤**归类**：`permanentDenial(\"protected_block_entity\")=true` ⇒ 区域作业会**如实失败**"
                        + "（`no_permitted_candidate`），⛔ 不会把「区内挖不动」当成「区域里没有候选」空转到 `maxTicks`"
                        + "（`D-341` 那条 20 分钟空转的同类）",
                AreaPermission.permanentDenial("protected_block_entity"));

        findings.add("authority: L0/L1/L2 判据 + 真写入（quota=" + quotaPlaced + "/"
                + Quota.L1_MAX_PLACES + " 区内放置，越界/安全区/野外/别的 owner/候选扫描各一条）");
        JobRegionRegistry.release(WorldModLedger.currentScope(server, owner));
        phase = Phase.JOB_SETUP;
        settle = 0;
    }

    /** ⑦ 生产接线（成功路径）：真跑 `RegionLumberJob` ⇒ 首 tick 解算任务区、终态自动解除。 */
    private void jobOkPhase(ServerLevel level) {
        var server = level.getServer();
        UUID owner = bot.getUUID();
        if (job == null) {
            snapshotRegionState();
            job = new RegionLumberJob(bot, new LumberAreaState.Area(
                    new WorkingArea(AREA2_MIN_X, AREA2_MIN_Z, AREA2_MAX_X, AREA2_MAX_Z),
                    FOOT_Y, 8),
                    scope, new LumberCandidateSource(), new NearestPolicy(), 20, 4);
            jobStartTick = ticks;
            BotLog.info("[JobRegionDiag] JOB_OK 起跑：area=[{},{},{},{}] 起点={}",
                    AREA2_MIN_X, AREA2_MIN_Z, AREA2_MAX_X, AREA2_MAX_Z, bot.blockPosition().toShortString());
            return;
        }
        Task.Status status = job.tick();
        if (!observedZoneMidFlight) {
            observedZoneMidFlight = true;
            JobRegionRegistry.JobRegion zone = JobRegionRegistry.jobRegionOf(server, owner);
            check("⭐生产接线：常驻区域伐木 Job **首 tick 就解算出任务区**"
                            + "（工作区域 4×4 方块 ⇒ 区块最小覆盖 1 个；zone=" + job.jobRegionStatus() + "）",
                    zone != null && zone.chunks().size() == 1
                            && job.jobRegionChunks() == 1
                            && job.jobRegionStatus().startsWith("DECLARED"));
        }
        if (status == Task.Status.RUNNING) {
            if (ticks - jobStartTick > JOB_TICK_CAP) {
                timedOut = true;
                check("生产接线：Job 必须在 " + JOB_TICK_CAP + " tick 内跑到终态（实际超时）", false);
                // 超时**不能继续 tick 同一个 Job**（既不前进、又会拖到总预算）：丢掉它、照常走后面的用例
                // 与收尾（收尾会按增量还原，失败路径也不许把认领/声明留在世界里）。
                job = null;
                observedZoneMidFlight = false;
                settle = 0;
                phase = Phase.JOB_SAFE_SETUP;
            }
            return;
        }
        check("⭐生产接线：Job 终态后**任务区自动解除**（结束/取消任务 ⇒ 自动解除，玩家无需再点一次；"
                        + "jobRegionOf=null）",
                JobRegionRegistry.jobRegionOf(server, owner) == null);
        check("生产接线：终态是预算耗尽那条（`goal_timeout`）⇒ 上面观察到的解除确实发生在**任务收尾**，"
                        + "不是别的早退路径",
                status == Task.Status.FAILED && "goal_timeout".equals(job.terminalReason()));
        findings.add("job_ok status=" + status + " reason=" + job.terminalReason()
                + " zone=" + job.jobRegionStatus() + " ticks=" + (ticks - jobStartTick));
        job = null;
        observedZoneMidFlight = false;
        settle = 0;
        phase = Phase.JOB_SAFE_SETUP;
    }

    /**
     * ⑧ 生产接线（**安全区被覆盖**路径；⛔ 取代原「冲突路径」）：工作区域压在安全区上
     * ⇒ Job **照常解算任务区**（`task_zone_conflict` 这条失败路径**已删除**）。
     */
    private void jobSafePhase(ServerLevel level) {
        var server = level.getServer();
        UUID owner = bot.getUUID();
        if (job == null) {
            job = new RegionLumberJob(bot, new LumberAreaState.Area(
                    new WorkingArea(AREA_MIN_X, AREA_MIN_Z, AREA_MAX_X, AREA_MAX_Z),
                    FOOT_Y, 8),
                    scope, new LumberCandidateSource(), new NearestPolicy(), 20, 4);
        }
        Task.Status status = job.tick();
        check("⭐安全区被覆盖：生产任务**照常解算任务区**（⛔ 不再有 `task_zone_conflict` 失败路径）｜status="
                        + status + " jobRegion=" + job.jobRegionStatus(),
                job.jobRegionStatus().startsWith("DECLARED"));
        JobRegionRegistry.JobRegion active = JobRegionRegistry.jobRegionOf(server, owner);
        check("⭐安全区被覆盖：任务区**真的覆盖了**那个安全区区块（" + CONFLICT_CHUNK_X + "," + AREA_CHUNK_Z
                        + "）｜chunks=" + job.jobRegionChunks(),
                job.jobRegionChunks() == 3 && active != null
                        && active.covers(new BlockPos(CONFLICT_CHUNK_X << 4, FOOT_Y, AREA_CHUNK_Z << 4)));
        findings.add("job_safe status=" + status + " zone=" + job.jobRegionStatus()
                + " chunks=" + job.jobRegionChunks());
        job = null;
        phase = Phase.CLEANUP;
    }

    /** ⑨ 收尾：按**增量**还原世界与区域状态，并把 bot 送回进入前的脚位。 */
    private void cleanupPhase(ServerLevel level, AreaData zones) {
        var server = level.getServer();
        UUID owner = bot.getUUID();
        JobRegionRegistry.release(WorldModLedger.currentScope(server, owner));
        zones.clearSafe(level, CONFLICT_CHUNK_X, AREA_CHUNK_Z);
        zones.clearSafe(level, OUTSIDE_CHUNK_X, OUTSIDE_CHUNK_Z);
        for (int chunkX : AREA_CHUNKS_X) {
            zones.unclaim(level, chunkX, AREA_CHUNK_Z);
        }
        zones.unclaim(level, AREA2_CHUNK_X, AREA2_CHUNK_Z);
        zones.unclaim(level, OUTSIDE_CHUNK_X, OUTSIDE_CHUNK_Z);
        restoreRegionState();
        // 授权面用例真的写过世界的格子：**逐格还原 + 销掉账本条目**（失败路径同样要还原；
        // 否则电池的"留下我方临时方块且未声明 KEEP ⇒ 判红"会把本步记成泄漏）
        for (java.util.Map.Entry<BlockPos, net.minecraft.world.level.block.state.BlockState> entry
                : touched.entrySet()) {
            level.setBlock(entry.getKey(), entry.getValue(), 3);
            WorldModLedger.forget(level, entry.getKey());
        }
        touched.clear();
        check("收尾：本 bot 名下没有残留任务区（jobRegionOf=null）", JobRegionRegistry.jobRegionOf(server, owner) == null);
        check("收尾：保护区计数回到进入前（增量 0；before=" + chunksBefore + " now="
                        + zones.claimedChunkCount() + "）", zones.claimedChunkCount() == chunksBefore);
        check("收尾：安全区计数回到进入前（增量 0；before=" + safeBefore + " now="
                        + zones.safeChunkCount() + "）", zones.safeChunkCount() == safeBefore);
        check("收尾：生效任务区条数回到进入前（before=" + activeBefore + " now="
                        + JobRegionRegistry.activeCount(server) + "）",
                JobRegionRegistry.activeCount(server) == activeBefore);
        if (timedOut) {
            check("收尾：本步没有发生用例超时", false);
        }
        JobRegionRegistry.clearAll();
        ModifyAudit.clearAllowAudit();
        phase = Phase.DONE;
        finish();
    }

    // ==================== 工具 ====================

    /** 区域状态快照（生产 Job 用例把补种/苗/基线挪开，收尾复原 —— 否则会污染别的步）。 */
    private void snapshotRegionState() {
        LumberAreaState state = LumberAreaState.get(bot.getServer());
        UUID owner = bot.getUUID();
        pendingBefore = new ArrayList<>(state.pendingReplant(owner));
        saplingsBefore = new ArrayList<>(state.mySaplings(owner));
        baselineBefore = state.baselineTrees(owner);
        derivedBefore = state.baselineDerived(owner);
        for (BlockPos pos : pendingBefore) {
            state.removePendingReplant(owner, pos);
        }
        for (BlockPos pos : saplingsBefore) {
            state.forgetSapling(owner, pos);
        }
        regionStateSnapshotted = true;
        BotLog.info("[JobRegionDiag] 区域状态快照：pending={} mySaplings={} baseline={} derived={}"
                        + "（本用例只测任务区 ⇒ 补种相关状态先挪开，收尾复原）",
                pendingBefore.size(), saplingsBefore.size(), baselineBefore, derivedBefore);
    }

    private void restoreRegionState() {
        if (!regionStateSnapshotted) {
            return;
        }
        LumberAreaState state = LumberAreaState.get(bot.getServer());
        UUID owner = bot.getUUID();
        for (BlockPos pos : pendingBefore) {
            state.addPendingReplant(owner, pos);
        }
        for (BlockPos pos : saplingsBefore) {
            state.addMySapling(owner, pos);
        }
        state.setBaselineTrees(owner, baselineBefore);
        state.setBaselineDerived(owner, derivedBefore);
        regionStateSnapshotted = false;
    }

    /** 夹具自己写一格（原状进 `touched` ⇒ 收尾逐格还原）；手搭地形/小树用，不走动作层。 */
    private void setBlockTracked(ServerLevel level, BlockPos pos, BlockState state) {
        touched.putIfAbsent(pos.immutable(), level.getBlockState(pos));
        level.setBlock(pos, state, 3);
    }

    /** 拒绝理由列表里有没有某个理由码（候选源的码形如 `tree@x,y,z:protected_area`）。 */
    private static boolean hasCode(List<String> rejected, String code) {
        for (String entry : rejected) {
            if (entry.endsWith(":" + code) || entry.endsWith(":" + code + ")")) {
                return true;
            }
        }
        return false;
    }

    /** 经**生产动作层**在保护区内放一块石头：夹具只负责"快照原状 + 收尾还原 + 销账本条目"。 */
    private boolean placeThroughAction(ServerLevel level, BlockPos pos, com.dddgn.alice.write.Attribution grant) {
        touched.putIfAbsent(pos.immutable(), level.getBlockState(pos));
        return BlockInteraction.placeBulkEdit(bot, level, pos, Blocks.COBBLESTONE.defaultBlockState(), grant);
    }

    /** 写入凭证（requester 决定任务类别 ⇒ 账本策略；reason 决定区域级授权面的判定）。 */
    private static com.dddgn.alice.write.Attribution grant(String requester,
                                                           com.dddgn.alice.write.WriteReason reason) {
        return com.dddgn.alice.write.Attribution.of(requester, reason);
    }

    /** 传送到位（**传送那一 tick 不读 `onGround` 当判据**；落地由 `FixturePremise.settledOnGround` 复核）。 */
    private boolean goTo(BlockPos foot) {
        if (settle == 0) {
            teleport(bot, foot);
            settle = 1;
            return false;
        }
        settle++;
        return FixturePremise.settledOnGround(bot, settle);
    }

    private void teleport(ServerPlayer who, BlockPos foot) {
        who.teleportTo(who.serverLevel(), foot.getX() + 0.5D, foot.getY(), foot.getZ() + 0.5D,
                Set.of(), who.getYRot(), who.getXRot());
        who.setDeltaMovement(Vec3.ZERO);
        if (who instanceof BotPlayer botPlayer) {
            botPlayer.controller().stopMovement();
        }
    }

    private Task.Status finish() {
        if (done) {
            return failures.isEmpty() ? Task.Status.DONE : Task.Status.FAILED;
        }
        done = true;
        boolean pass = failures.isEmpty();
        BotLog.info("[JobRegionDiag] SUMMARY checks={} failures={} verdict={} → {}｜{}",
                checks, failures.size(), pass ? "PASS" : "FAIL", pass ? "PASS" : "FAIL", findings);
        if (observer != null && !observer.hasDisconnected() && !observer.isRemoved()) {
            observer.sendSystemMessage(Component.literal("[alice] 任务区门禁 "
                    + (pass ? "PASS（" + checks + " 判据）" : "FAIL " + failures)));
        }
        return pass ? Task.Status.DONE : Task.Status.FAILED;
    }

    private void check(String what, boolean ok) {
        checks++;
        if (!ok) {
            failures.add(what);
        }
    }
}
