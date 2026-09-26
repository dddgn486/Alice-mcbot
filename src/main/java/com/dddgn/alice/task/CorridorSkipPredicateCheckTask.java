package com.dddgn.alice.task;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.job.fishbone.FishboneJob;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.pathing.MovementHelper;
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
import java.util.Set;

/**
 * **`F1` / `1.4j①` / `D-443` 片 C `P1`**：鱼骨"这一格算不算已经通"必须是**单格判据**。
 *
 * <h3>它钉的两行真机事实（2026-09-26 第五轮，4/4）</h3>
 * <pre>
 * [Fishbone] cell unit=1/404 cell=2/2 target=-75, 72, 238     ← 每次都死在单元的**头位格**
 * [MiningPlanner探针] no_valid_standing_point target=-75, 72, 233
 *                     faceStandable=0/6 footPassable=true headPassable=false belowSolid=false
 *                     ↑ footPassable=true = **目标格自己已经是空气**（作业在"挖空气"）
 * </pre>
 * 旧判据 {@code bodyPassable(cell)} = `cell ∧ cell.above()`：被问的是**头位格**时，它实际在问
 * "头位 + **天花板**"，而天花板恒为实心（本轮任务只挖 `cells()`）⇒ 头位格**永远不被判成"已通"**
 * ⇒ 造出"挖空气"的请求 ⇒ `no_valid_standing_point` ⇒ 主巷 ⇒ **整作业失败**。
 *
 * <h3>三种几何（本夹具逐个钉）</h3>
 * <pre>
 *   FRESH  脚位石 + 头位石        ⇒ 两格都要挖（**反证：修复不许把正常挖禁掉**）
 *   DUG    脚位空 + 头位空        ⇒ 两格都跳过（**真机形态**；旧判据在此处造出"挖空气"）
 *   HALF   脚位空 + 头位石        ⇒ 脚位跳过、**头位格照常挖**（`I3`：半成品格不许被放过）
 * </pre>
 * 三处几何都在**整块实心石头**里凿出来（⇒ 场景天然孤立，且"头位之上是天花板"成立）。
 *
 * <h3>为什么必须有负对照（防"永远绿"）</h3>
 * 只断言"新判据说 true"是不够的 —— 那可能只证明我把断言写成了同义反复。
 * 所以本夹具**同时**算旧判据 `MovementHelper.bodyPassable`，并断言它在 `DUG 的头位格` 上**必须为 false**：
 * 这正是真机 23:17 / 09:33 那条"挖空气"的请求来源。两边同真假 ⇒ 红的是**夹具**（看不出这个机制）。
 *
 * <p>⚠️ 本夹具**只判谓词**（零 bot 物理、不跑 Job）；"整作业能不能跑完"由集成夹具
 * `fishbone_slice1/2` + `core` 回答。
 */
public final class CorridorSkipPredicateCheckTask implements Task {

    /** 与同族夹具同一片专用孤立区（不同 z 偏移，互不重叠）。 */
    private static final BlockPos START = new BlockPos(3000, -60, 4000);

    /** 三处单元几何的基准格（= 第 1 个单元的**脚位格**）。 */
    private static final BlockPos ORIGIN = START.offset(0, 0, 120);

    /** 三个单元沿 x 排开，间距 4（每个单元自带 ≥2 格实心围岩）。 */
    private static final int UNIT_STRIDE = 4;

    private static final int SETTLE_TICKS = 20;
    private static final int BUDGET_TICKS = 200;

    private enum Kind { FRESH, DUG, HALF }

    private final BotPlayer bot;
    private final ServerPlayer observer;
    private final List<String> failures = new ArrayList<>();
    private final List<String> findings = new ArrayList<>();
    private final Map<BlockPos, BlockState> before = new LinkedHashMap<>();

    private int ticks;
    private int checks;
    private int step;
    private boolean done;

    public CorridorSkipPredicateCheckTask(BotPlayer bot, ServerPlayer observer) {
        this.bot = bot;
        this.observer = observer;
    }

    @Override
    public String taskName() {
        return "corridor_skip_predicate";
    }

    @Override
    public TaskTarget target() {
        return TaskTarget.block(ORIGIN);
    }

    @Override
    public String failureReason() {
        return failures.isEmpty() ? "" : failures.get(0);
    }

    @Override
    public String terminalReason() {
        return failures.isEmpty() ? "ok" : "failed:" + failures.get(0);
    }

    @Override
    public Task.Status tick() {
        if (done) {
            return failures.isEmpty() ? Task.Status.DONE : Task.Status.FAILED;
        }
        if (++ticks > BUDGET_TICKS) {
            check("谓词判据必须在预算内跑完（" + BUDGET_TICKS + " tick）", false);
            return finish();
        }
        if (step == 0) {
            buildScene(bot.serverLevel());
            step = 1;
            return Task.Status.RUNNING;
        }
        if (step <= SETTLE_TICKS) {
            step++;
            return Task.Status.RUNNING;
        }
        judge(bot.serverLevel());
        return finish();
    }

    // ==================== 判据 ====================

    private void judge(ServerLevel level) {
        for (int i = 0; i < Kind.values().length; i++) {
            Kind kind = Kind.values()[i];
            BlockPos foot = ORIGIN.offset(i * UNIT_STRIDE, 0, 0);
            BlockPos head = foot.above();

            // 生产判据（同一处出处）vs 旧判据（负对照用）
            boolean newFoot = FishboneJob.isAlreadyPassable(level, foot);
            boolean newHead = FishboneJob.isAlreadyPassable(level, head);
            boolean oldFoot = MovementHelper.bodyPassable(level, foot);
            boolean oldHead = MovementHelper.bodyPassable(level, head);
            // 真机探针那两个标志（`MiningPlanner:249-254` 的同名读法）
            boolean probeFootPassable = MovementHelper.canWalkThrough(level, foot);
            boolean probeHeadPassable = MovementHelper.canWalkThrough(level, head);

            findings.add(kind + ":new=[foot=" + newFoot + " head=" + newHead + "]"
                    + " old=[foot=" + oldFoot + " head=" + oldHead + "]"
                    + " probe=[footPassable=" + probeFootPassable
                    + " headPassable=" + probeHeadPassable + "]");
            BotLog.info("[CorridorSkip] {} foot={} head={} new=[{}/{}] old=[{}/{}] probe=[{}/{}]",
                    kind, foot.toShortString(), head.toShortString(), newFoot, newHead,
                    oldFoot, oldHead, probeFootPassable, probeHeadPassable);

            // 前提：几何建对了（三处各不相同，逐条自证）
            check("前提（" + kind + "）：几何 = " + describe(kind)
                            + "（实际 foot 可穿=" + probeFootPassable + "、头位可穿=" + probeHeadPassable
                            + "）",
                    switch (kind) {
                        case FRESH -> !probeFootPassable && !probeHeadPassable;
                        case DUG -> probeFootPassable && probeHeadPassable;
                        case HALF -> probeFootPassable && !probeHeadPassable;
                    });

            switch (kind) {
                case FRESH -> {
                    // 反证：修复不许把"正常挖"一起禁掉（否则整条主巷都不挖了）
                    check("⭐ FRESH（脚位石 + 头位石）：**两格都不许跳过**（实际 "
                                    + newFoot + "/" + newHead + "）—— 反证：换判据不许改成"
                                    + "「看到石头就跳过」或「一律跳过」",
                            !newFoot && !newHead);
                }
                case DUG -> {
                    // ⭐ 本轮真机 4/4 的形态
                    check("⭐⭐ DUG（脚位空 + 头位空，**天花板实心**）：**两格都必须跳过**"
                                    + "（实际 " + newFoot + "/" + newHead + "）—— 旧判据在这里对**头位格**返回 false"
                                    + "⇒ 造出「挖空气」的请求 ⇒ `no_valid_standing_point` ⇒ 主巷整作业失败"
                                    + "（真机原文 `cell=2/2` + `footPassable=true headPassable=false`）",
                            newFoot && newHead);
                    check("⭐ DUG 负对照（防永远绿）：**旧判据必须对头位格说 false**"
                                    + "（实际 oldHead=" + oldHead + "）—— 这正是那条不可能完成的请求的来源；"
                                    + "若两边同值 ⇒ 红的是**夹具**（看不出这个机制），不是缺陷",
                            !oldHead);
                    check("⭐ DUG 因果锚点：旧判据拒绝头位格的唯一原因是**天花板**"
                                    + "（头位自己可穿=" + probeHeadPassable
                                    + " 而头位之上可穿=" + MovementHelper.canWalkThrough(level, head.above()) + "）"
                                    + "—— 与真机探针 `footPassable=true headPassable=false` 逐字同形",
                            probeHeadPassable
                                    && !MovementHelper.canWalkThrough(level, head.above())
                                    && !oldHead);
                }
                case HALF -> {
                    // `I3`：半成品格（脚位空 + 头位实）不许被"整格放过"
                    check("⭐⭐ HALF（脚位空 + 头位实）：脚位跳过、**头位格照常挖**（实际 "
                                    + newFoot + "/" + newHead + "）—— 这是 `I3`（不留半成品格）在单格判据下的"
                                    + "落地形态：半成品格必须**补齐**，不许被当成合格格放过",
                            newFoot && !newHead);
                }
            }
        }
    }

    private static String describe(Kind kind) {
        return switch (kind) {
            case FRESH -> "脚位石 + 头位石";
            case DUG -> "脚位空 + 头位空（天花板实心）";
            case HALF -> "脚位空 + 头位石";
        };
    }

    // ==================== 场景（自建 + 原样还原） ====================

    /**
     * 整块**实心石头**里凿出三处单元几何（⇒ 孤立性是构造保证，且"头位之上恒为天花板"成立）。
     *
     * <p>⚠️ 先 `teleport` 到区域中心再动方块：未加载区块上的 `setBlock` 会**静默 0 改动**
     *（`D-244` 实测：三个 `fill` 全 0、判据一起红），而玩家 ticket 会同步加载区块。
     */
    private void buildScene(ServerLevel level) {
        int span = Kind.values().length * UNIT_STRIDE + 2;
        for (int dx = -2; dx <= span; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = -2; dy <= 3; dy++) {
                    setBlock(level, ORIGIN.offset(dx, dy, dz), Blocks.STONE);
                }
            }
        }
        for (int i = 0; i < Kind.values().length; i++) {
            Kind kind = Kind.values()[i];
            BlockPos foot = ORIGIN.offset(i * UNIT_STRIDE, 0, 0);
            // 脚位格：FRESH 保持实心；DUG/HALF 凿空
            if (kind != Kind.FRESH) {
                setBlock(level, foot, Blocks.AIR);
            }
            // 头位格：只有 DUG 凿空（HALF 刻意留实心 = 半成品格）
            if (kind == Kind.DUG) {
                setBlock(level, foot.above(), Blocks.AIR);
            }
        }
        // ⚠️ **最后才把 bot 挪进来、且挪进一个凿出来的两格高空气位**：首版把 bot 直接传送到
        // `ORIGIN`（= FRESH 单元的**脚位石**）⇒ 它被塞进实心方块里 ⇒ 维生监测判 `SUFFOCATING`
        // 把整台电池打断（`verdict=no_verdict exit=3`）。**夹具自己的前提不许被自己破坏。**
        BlockPos botSpot = ORIGIN.offset(0, 0, 2);
        setBlock(level, botSpot, Blocks.AIR);
        setBlock(level, botSpot.above(), Blocks.AIR);
        teleport(bot, botSpot);
        check("前提：bot 站位已凿空且可站（" + botSpot.toShortString() + "）—— 否则维生监测会打断电池",
                MovementHelper.bodyPassable(level, botSpot) && MovementHelper.canWalkOn(level, botSpot));
    }

    private void setBlock(ServerLevel level, BlockPos pos, net.minecraft.world.level.block.Block block) {
        BlockPos key = pos.immutable();
        before.putIfAbsent(key, level.getBlockState(key));
        level.setBlockAndUpdate(key, block.defaultBlockState());
    }

    private void teleport(BotPlayer player, BlockPos foot) {
        ServerLevel level = player.serverLevel();
        player.teleportTo(level, foot.getX() + 0.5D, foot.getY(), foot.getZ() + 0.5D,
                Set.of(), player.getYRot(), player.getXRot());
        player.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        player.controller().stopMovement();
    }

    // ==================== 收尾 ====================

    private Task.Status finish() {
        done = true;
        ServerLevel level = bot.serverLevel();
        for (Map.Entry<BlockPos, BlockState> entry : before.entrySet()) {
            level.setBlockAndUpdate(entry.getKey(), entry.getValue());
        }
        teleport(bot, START);
        bot.controller().stopMovement();

        boolean pass = failures.isEmpty();
        BotLog.info("[CorridorSkip] SUMMARY checks={} failures={} units={} → {}｜发现：{}｜失败项：{}",
                checks, failures.size(), Kind.values().length, pass ? "PASS" : "FAIL", findings, failures);
        if (observer != null && !observer.isRemoved()) {
            observer.sendSystemMessage(Component.literal("[alice] 鱼骨「这格算不算通」单格判据取证 "
                    + (pass ? "PASS" : "FAIL") + "（3 几何；详见日志 [CorridorSkip]）"));
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
