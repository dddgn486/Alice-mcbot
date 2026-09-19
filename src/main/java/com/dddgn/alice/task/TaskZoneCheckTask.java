package com.dddgn.alice.task;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.job.lumber.LumberCandidateSource;
import com.dddgn.alice.job.lumber.LumberRegionState;
import com.dddgn.alice.job.lumber.RegionLumberJob;
import com.dddgn.alice.job.policy.NearestPolicy;
import com.dddgn.alice.ledger.WorldModLedger;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.perception.ScopeBuffer;
import com.dddgn.alice.protection.SafeZoneData;
import com.dddgn.alice.protection.TaskZoneRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * ⭐ **任务区门禁**（`§5.12` 第 4 件的"几何 + 锁定"层，`D-338` 附注二/附注四）—— 电池步
 * `task_zone`（**EXTRA**）。
 *
 * <p>它守的是四件事，**一件不少**：
 * <ol>
 *   <li><b>派生几何</b>：工作区域（**方块级**）⇒ 任务区（**区块级最小覆盖**），单向；
 *       最小性用**逐方块枚举**这条独立路径做等式断言（不是同一公式抄两遍）；Y 不参与派生；</li>
 *   <li><b>覆盖规则</b>：任务区**可以**覆盖保护区**父类**；与**子类声明（安全区）**有交集 ⇒
 *       **报错 + 拒绝声明**，且**不裁剪、不降级**（安全区/保护区计数一字未动 ⇒ 系统没替玩家
 *       把安全区变成普通保护区）；取消那个声明之后**同一个工作区域**就能声明 —— 这正是报错里指的退路；</li>
 *   <li><b>锁定与生命周期</b>：唯一写入者是任务；作用域一收尾（终态/被替换/显式打断）
 *       ⇒ 权威**自动消失**（不需要谁记得来关）；过期条目被 prune 掉；没有作用域 ⇒ `NO_SCOPE`；</li>
 *   <li><b>生产接线</b>：真跑一次 {@link RegionLumberJob}（**生产 Job 类，不是影子实现**）——
 *       首 tick 会解算出任务区，终态后任务区已解除；工作区域压到安全区上时**如实失败**
 *       （`task_zone_conflict`）并报出冲突区块。</li>
 * </ol>
 *
 * <p><b>本步刻意不做的两件事（不许"顺手"补上）</b>：① **不动任何权限行为** ——
 * 保护区块在 `SafeZoneData.protectionReason` 下**照旧**是 `protected_area`（判据里有这一条，
 * 防止"任务区一落地就把闸门放宽了"；接闸门是第 4 件的下一片，且要等**第 5 件权限阶梯**拍板）；
 * ② 不改世界 —— 夹具只做**集合运算 + 一处认领/声明**（收尾按增量还原），
 * 不像素地形、不加载远处区块（`protectionReason` 对已认领区块**不读方块**就返回）。
 *
 * <p><b>专用孤立区</b>（不与任何既有场景/基准重叠；全是纯集合运算 ⇒ 真实存档里也能跑）：
 * 块 `x 35200..35240 / z 35200..35210`（**故意跨 3 个区块** 2200..2202 × 2200）、
 * 第二区块 `35360..35363 / 35360..35363`（区块 2210,2210，1 个区块）、
 * 区外安全区点 = 区块 2215,2215。**所有维度级计数都按增量断言**。
 */
public final class TaskZoneCheckTask implements Task {

    // ==================== 工作区域（方块级）====================

    /** 工作区域 ①：**故意跨区块边界**（x 35200..35240 ⇒ 区块 2200..2202；z 35200..35210 ⇒ 区块 2200）。 */
    private static final int AREA_MIN_X = 35200;
    private static final int AREA_MAX_X = 35240;
    private static final int AREA_MIN_Z = 35200;
    private static final int AREA_MAX_Z = 35210;
    private static final int[] AREA_CHUNKS_X = {2200, 2201, 2202};
    private static final int AREA_CHUNK_Z = 2200;
    /** 冲突用例挑**中间**那个区块（2201,2200）：声明安全区之后任务区不得覆盖它。 */
    private static final int CONFLICT_CHUNK_X = 2201;

    /** 工作区域 ②：1 个区块（区块 2210,2210）—— 换区用例与**生产 Job 用例**共用。 */
    private static final int AREA2_MIN_X = 35360;
    private static final int AREA2_MAX_X = 35363;
    private static final int AREA2_MIN_Z = 35360;
    private static final int AREA2_MAX_Z = 35363;
    private static final int AREA2_CHUNK_X = 2210;
    private static final int AREA2_CHUNK_Z = 2210;

    /** 两区**之外**的安全区点（区块 2215,2215）：证明冲突判据只看向交集。 */
    private static final int OUTSIDE_CHUNK_X = 2215;
    private static final int OUTSIDE_CHUNK_Z = 2215;

    private static final BlockPos AREA_START = new BlockPos(AREA_MIN_X, -60, AREA_MIN_Z);
    private static final BlockPos AREA2_START = new BlockPos(AREA2_MIN_X, -60, AREA2_MIN_Z);
    private static final BlockPos OUTSIDE_POS = new BlockPos(OUTSIDE_CHUNK_X << 4, -60, OUTSIDE_CHUNK_Z << 4);

    /** 世界地板 Y（无头/客户端存档都是 y=-61 面、脚位 -60；与 `SafeReturnCheckTask` 同一口径）。 */
    private static final int FOOT_Y = -60;

    private static final int SETTLE_TICKS = 30;
    private static final int BUDGET_TICKS = 600;
    /** 生产 Job 用例的 tick 预算（`maxTicks=4` ⇒ 第 5 tick 必然 `goal_timeout`）。 */
    private static final int JOB_TICK_CAP = 40;

    private enum Phase {
        PREPARE, GEOMETRY, DECLARE, SCOPE_LIFECYCLE, OVERLAY, CONFLICT, DEGRADE,
        JOB_SETUP, JOB_OK, JOB_CONFLICT_SETUP, JOB_CONFLICT, CLEANUP, DONE
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

    public TaskZoneCheckTask(BotPlayer bot, ServerPlayer observer, ScopeBuffer scope) {
        this.bot = bot;
        this.observer = observer;
        this.scope = scope;
    }

    @Override
    public String taskName() {
        return "TaskZoneCheck";
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
        SafeZoneData zones = SafeZoneData.get(level.getServer());
        switch (phase) {
            case PREPARE -> preparePhase(level, zones);
            case GEOMETRY -> geometryPhase(level, zones);
            case DECLARE -> declarePhase(level);
            case SCOPE_LIFECYCLE -> scopeLifecyclePhase(level);
            case OVERLAY -> overlayPhase(level, zones);
            case CONFLICT -> conflictPhase(level, zones);
            case DEGRADE -> degradePhase(level, zones);
            case JOB_SETUP -> {
                if (goTo(AREA2_START)) {
                    settle = 0;
                    phase = Phase.JOB_OK;
                }
            }
            case JOB_OK -> jobOkPhase(level);
            case JOB_CONFLICT_SETUP -> {
                if (goTo(AREA_START)) {
                    settle = 0;
                    phase = Phase.JOB_CONFLICT;
                }
            }
            case JOB_CONFLICT -> jobConflictPhase(level);
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

    private void preparePhase(ServerLevel level, SafeZoneData zones) {
        if (settle == 0) {
            chunksBefore = zones.claimedChunkCount();
            safeBefore = zones.safeChunkCount();
            activeBefore = TaskZoneRegistry.activeCount(level.getServer());
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
                TaskZoneRegistry.zoneOf(level.getServer(), bot.getUUID()) == null);
        settle = 0;
        phase = Phase.GEOMETRY;
    }

    /** ① 派生几何：工作区域（方块级）⇒ 任务区（区块级最小覆盖），**单向**、Y 无关、纯函数。 */
    private void geometryPhase(ServerLevel level, SafeZoneData zones) {
        ResourceLocation dimension = level.dimension().location();
        TaskZoneRegistry.WorkArea area = new TaskZoneRegistry.WorkArea(dimension,
                AREA_MIN_X, AREA_MIN_Z, AREA_MAX_X, AREA_MAX_Z);
        Set<Long> cover = area.chunkCover();
        // **独立口径**：把工作区域里**每一格方块**列出来，各自映射到区块 —— 这条路径与
        // `chunkCoverOf(WorkArea)` 的矩形算术完全无关 ⇒ 等式成立才证明"最小覆盖"算对了。
        Set<Long> fromBlocks = new LinkedHashSet<>();
        List<BlockPos> blocks = new ArrayList<>();
        for (int x = AREA_MIN_X; x <= AREA_MAX_X; x++) {
            for (int z = AREA_MIN_Z; z <= AREA_MAX_Z; z++) {
                BlockPos pos = new BlockPos(x, FOOT_Y, z);
                blocks.add(pos);
                fromBlocks.add(TaskZoneRegistry.chunkKey(pos));
            }
        }
        check("几何：矩形工作区域 " + area.describe() + "（" + area.areaXZ() + " 格）的**区块最小覆盖**"
                        + " == 逐方块枚举所占的区块集合（两条独立路径等式；blocks=" + blocks.size()
                        + " ⇒ chunks=" + cover.size() + "）",
                cover.equals(fromBlocks));
        check("几何：集合口径 chunkCoverOf(Collection<BlockPos>) 与矩形口径给出**同一集合**",
                TaskZoneRegistry.chunkCoverOf(blocks).equals(cover));
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
                TaskZoneRegistry.chunkKey(new BlockPos(AREA_MIN_X, FOOT_Y, AREA_MIN_Z))
                        == TaskZoneRegistry.chunkKey(new BlockPos(AREA_MIN_X, 300, AREA_MIN_Z)));
        TaskZoneRegistry.WorkArea reversed = new TaskZoneRegistry.WorkArea(dimension,
                AREA_MAX_X, AREA_MAX_Z, AREA_MIN_X, AREA_MIN_Z);
        check("几何：两个角**反过来写** ⇒ 同一个区域（`equals` 稳定 ⇒ 「重划同一个区」可判幂等）",
                reversed.equals(area) && reversed.chunkCover().equals(cover));
        TaskZoneRegistry.WorkArea single = new TaskZoneRegistry.WorkArea(dimension,
                AREA2_MIN_X, AREA2_MIN_Z, AREA2_MAX_X, AREA2_MAX_Z);
        check("几何：1 个区块大小的工作区域 ⇒ 恰好 1 个区块（相区 2 用）", single.chunkCover().size() == 1);
        check("几何：派生是**纯函数**（保护区/安全区计数在几何调用前后未变）",
                zones.claimedChunkCount() == chunksBefore && zones.safeChunkCount() == safeBefore);
        findings.add("geometry area=" + area.describe() + " blocks=" + area.areaXZ()
                + " chunks=" + cover.size() + "[" + TaskZoneRegistry.describeChunks(cover) + "]");
        phase = Phase.DECLARE;
    }

    /** ② 声明 / 幂等 / 换区 / 没有作用域 ⇒ NO_SCOPE。 */
    private void declarePhase(ServerLevel level) {
        var server = level.getServer();
        UUID owner = bot.getUUID();
        ResourceLocation dimension = level.dimension().location();
        TaskZoneRegistry.WorkArea area = new TaskZoneRegistry.WorkArea(dimension,
                AREA_MIN_X, AREA_MIN_Z, AREA_MAX_X, AREA_MAX_Z);
        String scopeId = WorldModLedger.currentScope(server, owner);
        TaskZoneRegistry.Result declared = TaskZoneRegistry.declare(server, owner, "task_zone_fixture", area);
        check("声明：工作区域 ⇒ 任务区 成功（DECLARED，chunks=3）",
                declared.status() == TaskZoneRegistry.Declare.DECLARED
                        && declared.zone() != null && declared.zone().chunks().size() == 3);
        check("声明：任务区的 scopeId = **当前任务作用域**（随 scope 生灭的唯一键）",
                scopeId != null && declared.zone() != null && scopeId.equals(declared.zone().scopeId()));
        TaskZoneRegistry.Zone zone = TaskZoneRegistry.zoneOf(server, owner);
        check("查询：zoneOf(bot) 命中，且 covers 区内格 / 不 covers 区外格（O(1) 区块查表）",
                zone != null && zone.covers(AREA_START) && !zone.covers(AREA2_START));
        check("查询：zoneAt(level, 区内格) 命中 —— 这就是将来「这一格要不要提权」的查表入口",
                TaskZoneRegistry.zoneAt(level, AREA_START) != null);
        check("查询：zoneAt(level, 区外格) = null（不越界覆盖）",
                TaskZoneRegistry.zoneAt(level, OUTSIDE_POS) == null);
        TaskZoneRegistry.Result again = TaskZoneRegistry.declare(server, owner, "task_zone_fixture", area);
        check("幂等：同一作用域 + 同一工作区域 ⇒ ALREADY，且生效任务区条数不变",
                again.status() == TaskZoneRegistry.Declare.ALREADY
                        && TaskZoneRegistry.activeCount(server) == activeBefore + 1);
        TaskZoneRegistry.WorkArea area2 = new TaskZoneRegistry.WorkArea(dimension,
                AREA2_MIN_X, AREA2_MIN_Z, AREA2_MAX_X, AREA2_MAX_Z);
        TaskZoneRegistry.Result replaced = TaskZoneRegistry.declare(server, owner, "task_zone_fixture", area2);
        TaskZoneRegistry.Zone afterReplace = TaskZoneRegistry.zoneOf(server, owner);
        check("换区：同一任务换工作区域 ⇒ REPLACED，新覆盖 1 区块、**旧区块不再被覆盖**"
                        + "（重派生 ≠ 反向裁剪工作区域）",
                replaced.status() == TaskZoneRegistry.Declare.REPLACED
                        && afterReplace != null && afterReplace.chunks().size() == 1
                        && !afterReplace.covers(AREA_START));
        UUID stranger = UUID.nameUUIDFromBytes("task-zone-fixture-stranger".getBytes());
        TaskZoneRegistry.Result noScope = TaskZoneRegistry.declare(server, stranger,
                "task_zone_fixture", area);
        check("⛔没有任务作用域 ⇒ NO_SCOPE 且**不落库**（不许在任务之外造授权封套）",
                noScope.status() == TaskZoneRegistry.Declare.NO_SCOPE
                        && TaskZoneRegistry.zoneOf(server, stranger) == null
                        && TaskZoneRegistry.activeCount(server) == activeBefore + 1);
        phase = Phase.SCOPE_LIFECYCLE;
    }

    /** ③ 生命周期：**随 scopeId 生灭** —— 作用域收尾 ⇒ 权威自动消失（不靠谁记得来关）。 */
    private void scopeLifecyclePhase(ServerLevel level) {
        var server = level.getServer();
        UUID fake = UUID.nameUUIDFromBytes("task-zone-fixture-owner".getBytes());
        ResourceLocation dimension = level.dimension().location();
        TaskZoneRegistry.WorkArea area = new TaskZoneRegistry.WorkArea(dimension,
                AREA_MIN_X, AREA_MIN_Z, AREA_MAX_X, AREA_MAX_Z);
        String scopeA = WorldModLedger.openScope(server, fake, "task_zone_fixture");
        TaskZoneRegistry.Result declared = TaskZoneRegistry.declare(server, fake, "task_zone_fixture", area);
        check("生命周期：作用域内声明成功（独立 owner，不与本步 bot 的作用域互相干扰）",
                declared.status() == TaskZoneRegistry.Declare.DECLARED
                        && TaskZoneRegistry.zoneOf(server, fake) != null);
        WorldModLedger.closeScope(server, fake);
        check("⭐生命周期：**作用域一收尾 ⇒ 任务区权威立即消失**（zoneOf=null；"
                        + "终态/被替换/显式打断都走这条路，不需要任何调用方记得来关）",
                TaskZoneRegistry.zoneOf(server, fake) == null);
        // ⚠️ 顺序有意为之：**先重开作用域、再问 authority**（中间不做任何会 prune 的事）——
        // 否则"新 scope 无授权"这条可能因为**过期条目被 prune 掉了**而通过（对，但是**侥幸通过**：
        // 它证明不了"新作用域不继承旧任务区"这件事本身）。反向对照（`zoneOf` 退回按 owner 找）
        // 会同时打红上面两条 —— 这就是本条存在的原因。
        String scopeB = WorldModLedger.openScope(server, fake, "task_zone_fixture");
        check("生命周期：重开作用域**不继承**上一个作用域的任务区（旧条目还在表里也不给权威）",
                !scopeB.equals(scopeA) && TaskZoneRegistry.zoneOf(server, fake) == null);
        TaskZoneRegistry.Result redeclared = TaskZoneRegistry.declare(server, fake, "task_zone_fixture", area);
        check("生命周期：新作用域里必须**重新声明**（DECLARED，而不是 ALREADY/REPLACED ⇒ 证明旧区没被继承）",
                redeclared.status() == TaskZoneRegistry.Declare.DECLARED);
        check("生命周期：**过期条目被 prune 清掉**（进程内表不会无限长：旧 scope 的条目 + 新 scope 的 1 条"
                        + " + 本 bot 自己的 1 条）",
                TaskZoneRegistry.activeCount(server) == activeBefore + 2);
        WorldModLedger.closeScope(server, fake);
        check("生命周期：收尾后只剩本 bot 自己的那条（fake owner 的两条都已无主 ⇒ 清掉）",
                TaskZoneRegistry.activeCount(server) == activeBefore + 1);
        phase = Phase.OVERLAY;
    }

    /** ④ 覆盖规则：任务区**可以**覆盖保护区**父类**；且**本片不改任何权限行为**。 */
    private void overlayPhase(ServerLevel level, SafeZoneData zones) {
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
        TaskZoneRegistry.release(WorldModLedger.currentScope(server, owner));
        TaskZoneRegistry.WorkArea area = new TaskZoneRegistry.WorkArea(dimension,
                AREA_MIN_X, AREA_MIN_Z, AREA_MAX_X, AREA_MAX_Z);
        TaskZoneRegistry.Result declared = TaskZoneRegistry.declare(server, owner, "task_zone_fixture", area);
        check("⭐覆盖：任务区**可以覆盖保护区父类**（这些区块全在保护区内，声明照旧成功）",
                declared.status() == TaskZoneRegistry.Declare.DECLARED
                        && declared.zone() != null && declared.zone().chunks().size() == 3);
        TaskZoneRegistry.Zone zone = TaskZoneRegistry.zoneOf(server, owner);
        check("⭐覆盖：同一格上「保护区=true」与「任务区覆盖=true」**同时成立**"
                        + "（覆盖 = 对**任务授权**的让步，不是取消保护）",
                zones.isClaimed(level, AREA_START) && zone != null && zone.covers(AREA_START));
        check("⚠本片**不改权限**：破坏闸门对保护区块**照旧拒绝**"
                        + "（protectionReason=protected_area ⇒ 红线一个字没动）",
                "protected_area".equals(zones.protectionReason(level, AREA_START)));
        phase = Phase.CONFLICT;
    }

    /** ⑤ 冲突：与**子类声明（安全区）**有交集 ⇒ 报错 + 拒绝声明 + **不裁剪、不降级**。 */
    private void conflictPhase(ServerLevel level, SafeZoneData zones) {
        var server = level.getServer();
        UUID owner = bot.getUUID();
        ResourceLocation dimension = level.dimension().location();
        TaskZoneRegistry.WorkArea area = new TaskZoneRegistry.WorkArea(dimension,
                AREA_MIN_X, AREA_MIN_Z, AREA_MAX_X, AREA_MAX_Z);
        TaskZoneRegistry.WorkArea area2 = new TaskZoneRegistry.WorkArea(dimension,
                AREA2_MIN_X, AREA2_MIN_Z, AREA2_MAX_X, AREA2_MAX_Z);
        check("冲突前提：中间区块（" + CONFLICT_CHUNK_X + "," + AREA_CHUNK_Z
                        + "）先被声明为**安全区**（保护区的子类）",
                zones.declareSafe(level, CONFLICT_CHUNK_X, AREA_CHUNK_Z)
                        == SafeZoneData.SafeDeclare.DECLARED);
        int safeNow = zones.safeChunkCount();
        int claimedNow = zones.claimedChunkCount();
        TaskZoneRegistry.release(WorldModLedger.currentScope(server, owner));
        TaskZoneRegistry.Result conflict = TaskZoneRegistry.declare(server, owner, "task_zone_fixture", area);
        check("⛔冲突：任务区与安全区有交集 ⇒ CONFLICT_SUBZONE（拒绝声明）",
                conflict.status() == TaskZoneRegistry.Declare.CONFLICT_SUBZONE && !conflict.active());
        check("⛔冲突：报出的冲突区块**恰好**是那一个（" + CONFLICT_CHUNK_X + "," + AREA_CHUNK_Z + "）",
                conflict.conflicts().size() == 1
                        && conflict.conflicts().get(0) == ChunkPos.asLong(CONFLICT_CHUNK_X, AREA_CHUNK_Z));
        check("⛔冲突：**不裁剪** —— 世界没有留下一个「小一号」的任务区（zoneOf=null）",
                TaskZoneRegistry.zoneOf(server, owner) == null);
        check("⛔冲突：**不静默降级** —— 安全区/保护区计数一字未动（系统没替玩家把安全区变成普通保护区）",
                zones.safeChunkCount() == safeNow && zones.claimedChunkCount() == claimedNow);
        check("⛔冲突：**工作区域（玩家意图）一字未动**（blocks=" + area.areaXZ()
                        + " chunks=" + area.chunkCover().size() + " ⇒ 系统不许替他改小或改位置）",
                area.areaXZ() == 41L * 11L && area.chunkCover().size() == 3);
        zones.claim(level, OUTSIDE_CHUNK_X, OUTSIDE_CHUNK_Z);
        zones.declareSafe(level, OUTSIDE_CHUNK_X, OUTSIDE_CHUNK_Z);
        TaskZoneRegistry.Result elsewhere = TaskZoneRegistry.declare(server, owner, "task_zone_fixture", area2);
        check("冲突判据**只看向交集**：区外的安全区（" + OUTSIDE_CHUNK_X + "," + OUTSIDE_CHUNK_Z
                        + "）不影响声明 ⇒ 1 区块的工作区域照旧声明成功",
                elsewhere.status() == TaskZoneRegistry.Declare.DECLARED);
        TaskZoneRegistry.release(WorldModLedger.currentScope(server, owner));
        phase = Phase.DEGRADE;
    }

    /** ⑥ 显式退化：**取消那个安全区声明**之后，同一个工作区域就能声明 —— 报错里指的退路。 */
    private void degradePhase(ServerLevel level, SafeZoneData zones) {
        var server = level.getServer();
        UUID owner = bot.getUUID();
        ResourceLocation dimension = level.dimension().location();
        TaskZoneRegistry.WorkArea area = new TaskZoneRegistry.WorkArea(dimension,
                AREA_MIN_X, AREA_MIN_Z, AREA_MAX_X, AREA_MAX_Z);
        boolean degraded = zones.clearSafe(level, CONFLICT_CHUNK_X, AREA_CHUNK_Z);
        TaskZoneRegistry.Result declared = TaskZoneRegistry.declare(server, owner, "task_zone_fixture", area);
        check("⭐显式退化：取消那个区块的安全区声明之后，**同一个工作区域**就能声明任务区"
                        + "（这正是冲突报错指的退路，玩家一步可做）",
                degraded && declared.status() == TaskZoneRegistry.Declare.DECLARED);
        // 复原成"冲突前提"，供生产 Job 用例使用（并保持"任务未起 ⇒ 无任务区"）
        zones.declareSafe(level, CONFLICT_CHUNK_X, AREA_CHUNK_Z);
        TaskZoneRegistry.release(WorldModLedger.currentScope(server, owner));
        check("退化用例收尾：安全区已复原、本 bot 名下无生效任务区（下面两个用例从干净状态开始）",
                zones.isSafe(level, AREA_START.offset(16, 0, 0))
                        && TaskZoneRegistry.zoneOf(server, owner) == null);
        settle = 0;
        phase = Phase.JOB_SETUP;
    }

    /** ⑦ 生产接线（成功路径）：真跑 `RegionLumberJob` ⇒ 首 tick 解算任务区、终态自动解除。 */
    private void jobOkPhase(ServerLevel level) {
        var server = level.getServer();
        UUID owner = bot.getUUID();
        if (job == null) {
            snapshotRegionState();
            job = new RegionLumberJob(bot, new LumberRegionState.Region(
                    AREA2_MIN_X, AREA2_MIN_Z, AREA2_MAX_X, AREA2_MAX_Z, FOOT_Y, 8),
                    scope, new LumberCandidateSource(), new NearestPolicy(), 20, 4);
            jobStartTick = ticks;
            BotLog.info("[TaskZoneDiag] JOB_OK 起跑：region=[{},{},{},{}] 起点={}",
                    AREA2_MIN_X, AREA2_MIN_Z, AREA2_MAX_X, AREA2_MAX_Z, bot.blockPosition().toShortString());
            return;
        }
        Task.Status status = job.tick();
        if (!observedZoneMidFlight) {
            observedZoneMidFlight = true;
            TaskZoneRegistry.Zone zone = TaskZoneRegistry.zoneOf(server, owner);
            check("⭐生产接线：常驻区域伐木 Job **首 tick 就解算出任务区**"
                            + "（工作区域 4×4 方块 ⇒ 区块最小覆盖 1 个；zone=" + job.taskZoneStatus() + "）",
                    zone != null && zone.chunks().size() == 1
                            && job.taskZoneChunks() == 1
                            && job.taskZoneStatus().startsWith("DECLARED"));
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
                phase = Phase.JOB_CONFLICT_SETUP;
            }
            return;
        }
        check("⭐生产接线：Job 终态后**任务区自动解除**（结束/取消任务 ⇒ 自动解除，玩家无需再点一次；"
                        + "zoneOf=null）",
                TaskZoneRegistry.zoneOf(server, owner) == null);
        check("生产接线：终态是预算耗尽那条（`goal_timeout`）⇒ 上面观察到的解除确实发生在**任务收尾**，"
                        + "不是别的早退路径",
                status == Task.Status.FAILED && "goal_timeout".equals(job.terminalReason()));
        findings.add("job_ok status=" + status + " reason=" + job.terminalReason()
                + " zone=" + job.taskZoneStatus() + " ticks=" + (ticks - jobStartTick));
        job = null;
        observedZoneMidFlight = false;
        settle = 0;
        phase = Phase.JOB_CONFLICT_SETUP;
    }

    /** ⑧ 生产接线（冲突路径）：工作区域压在安全区上 ⇒ Job **如实失败**并报出冲突区块。 */
    private void jobConflictPhase(ServerLevel level) {
        var server = level.getServer();
        UUID owner = bot.getUUID();
        if (job == null) {
            job = new RegionLumberJob(bot, new LumberRegionState.Region(
                    AREA_MIN_X, AREA_MIN_Z, AREA_MAX_X, AREA_MAX_Z, FOOT_Y, 8),
                    scope, new LumberCandidateSource(), new NearestPolicy(), 20, 4);
        }
        Task.Status status = job.tick();
        check("⭐冲突 ⇒ 生产任务**如实失败**（不是静默降级继续跑）：status=FAILED 且 "
                        + "terminalReason=task_zone_conflict（实际 " + status + "/" + job.terminalReason() + "）",
                status == Task.Status.FAILED && "task_zone_conflict".equals(job.terminalReason()));
        check("⭐冲突：失败事实里**带冲突区块**（玩家据此知道该退化哪几个区块）：" + job.failureReason(),
                job.failureReason().contains("task_zone_conflict[safe_zone 1")
                        && job.failureReason().contains(CONFLICT_CHUNK_X + "," + AREA_CHUNK_Z));
        check("⭐冲突：冲突时**没有留下任何任务区**（zoneOf=null ⇒ 不会带着无效封套继续）",
                TaskZoneRegistry.zoneOf(server, owner) == null);
        check("⭐冲突：状态文本可见（`CONFLICT_SUBZONE…`，进 SUMMARY / 失败报告）",
                job.taskZoneStatus().startsWith("CONFLICT_SUBZONE"));
        findings.add("job_conflict status=" + status + " reason=" + job.terminalReason()
                + " failure=" + job.failureReason());
        job = null;
        phase = Phase.CLEANUP;
    }

    /** ⑨ 收尾：按**增量**还原世界与区域状态，并把 bot 送回进入前的脚位。 */
    private void cleanupPhase(ServerLevel level, SafeZoneData zones) {
        var server = level.getServer();
        UUID owner = bot.getUUID();
        TaskZoneRegistry.release(WorldModLedger.currentScope(server, owner));
        zones.clearSafe(level, CONFLICT_CHUNK_X, AREA_CHUNK_Z);
        zones.clearSafe(level, OUTSIDE_CHUNK_X, OUTSIDE_CHUNK_Z);
        for (int chunkX : AREA_CHUNKS_X) {
            zones.unclaim(level, chunkX, AREA_CHUNK_Z);
        }
        zones.unclaim(level, AREA2_CHUNK_X, AREA2_CHUNK_Z);
        zones.unclaim(level, OUTSIDE_CHUNK_X, OUTSIDE_CHUNK_Z);
        restoreRegionState();
        check("收尾：本 bot 名下没有残留任务区（zoneOf=null）", TaskZoneRegistry.zoneOf(server, owner) == null);
        check("收尾：保护区计数回到进入前（增量 0；before=" + chunksBefore + " now="
                        + zones.claimedChunkCount() + "）", zones.claimedChunkCount() == chunksBefore);
        check("收尾：安全区计数回到进入前（增量 0；before=" + safeBefore + " now="
                        + zones.safeChunkCount() + "）", zones.safeChunkCount() == safeBefore);
        check("收尾：生效任务区条数回到进入前（before=" + activeBefore + " now="
                        + TaskZoneRegistry.activeCount(server) + "）",
                TaskZoneRegistry.activeCount(server) == activeBefore);
        if (timedOut) {
            check("收尾：本步没有发生用例超时", false);
        }
        TaskZoneRegistry.clearAll();
        phase = Phase.DONE;
        finish();
    }

    // ==================== 工具 ====================

    /** 区域状态快照（生产 Job 用例把补种/苗/基线挪开，收尾复原 —— 否则会污染别的步）。 */
    private void snapshotRegionState() {
        LumberRegionState state = LumberRegionState.get(bot.getServer());
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
        BotLog.info("[TaskZoneDiag] 区域状态快照：pending={} mySaplings={} baseline={} derived={}"
                        + "（本用例只测任务区 ⇒ 补种相关状态先挪开，收尾复原）",
                pendingBefore.size(), saplingsBefore.size(), baselineBefore, derivedBefore);
    }

    private void restoreRegionState() {
        if (!regionStateSnapshotted) {
            return;
        }
        LumberRegionState state = LumberRegionState.get(bot.getServer());
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
        BotLog.info("[TaskZoneDiag] SUMMARY checks={} failures={} verdict={} → {}｜{}",
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
