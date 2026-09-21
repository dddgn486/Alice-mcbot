package com.dddgn.alice.task;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.pathing.core.search.CorePathPlanner;
import com.dddgn.alice.pathing.core.search.PathPlan;
import com.dddgn.alice.pathing.core.search.PathRequest;
import com.dddgn.alice.pathing.core.search.PlanningStatus;
import com.dddgn.alice.pathing.core.search.SearchBudget;
import com.dddgn.alice.pathing.core.search.SearchTickBudget;
import com.dddgn.alice.task.mining.MiningBudget;
import com.dddgn.alice.task.mining.StandingPointSelector;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Locale;

/**
 * **B（2026-09-21）：深矿可达性判据探针** —— 回答 `survey/24 §2.4` 第 3 问 与 `survey/25 §2.3` 第 1 问：
 * **「给 100× 预算，这个深矿到底能不能到？」**
 *
 * <p><b>为什么必须做这个实验，而不是继续推理</b>：真机第四轮留下的全部相关事实是
 * `status=SEARCH_LIMIT nodes=20000 ms=185~200 open≈3300`（×52 次）。而 `SEARCH_LIMIT` 的语义
 * **就是"预算耗尽、可达性未知"**（`D-076`：它 **≠** `UNREACHABLE`）⇒ 现有证据
 * **同时兼容两种完全不同的解释**：
 * <ul>
 *   <li><b>解释甲（预算不够）</b>：再给 100 倍节点就能到 ⇒ 病在性能/预算 ⇒ 止血（A1/A2）之后调预算即可；</li>
 *   <li><b>解释乙（根本没有路 / 表示不了）</b>：给 200 万节点也到不了 ⇒ 病在**候选与边的可表示性**
 *       （`D-366b` 放开动作时缺的那套"可采纳性守卫"）⇒ 必须做**结构改动**。</li>
 * </ul>
 * <b>这一条把"要不要做结构改动"从感觉问题变成事实问题</b>（勘测侧点名它是"决定项目根基"的一问）。
 *
 * <p><b>做法（受控实验：只改一个自变量）</b>：把 bot 摆到真机那次的脚位，取**同一个**目标的
 * {@link StandingPointSelector#tunnelCandidates}（真机 `candidates=13` 就是它），对每个候选跑
 * <b>同一个</b> {@link PathRequest#miningApproach}，**只改预算**：
 * <ul>
 *   <li>第 1 遍 {@link #BUDGET_1X} = 与真机同预算（20 000 节点 / 200 ms）—— 这一遍是**实验自己的对照**：
 *       若它不能复现"13/13 全失败"，说明本探针的现场与真机那次不是同一个局面，**100× 的结论就不作数**；</li>
 *   <li>第 2 遍 {@link #BUDGET_100X} = 100 倍节点（2 000 000 / 30 s）—— 这一遍回答"能不能到"。</li>
 * </ul>
 *
 * <p>⚠️ <b>必须跑在真机那次的存档上</b>（`saves/新的世界 (2)`，见 `docs/reviews/2026-09-21-客户端第四轮-深矿搜索卡顿.md`）：
 * 矿区几何是本次实验的自变量之一，换世界 ⇒ 结论无效。
 *
 * <p>⚠️ 本探针**只规划、不执行**（`CorePathPlanner.plan` 不写世界）⇒ 对存档是只读的。
 * 它是 `Profile.EXTRA`（不进 CORE）⇒ 只由 `single:mine_reach_probe` 显式跑。
 */
public final class MineReachProbeTask implements Task {

    /**
     * 真机第四轮的现场：`[PathRetry] plan attempt=0 … feet=433,84,428` / `[MiningPlanner] … startFoot=432,84,428`
     * （2026-09-21 00:03，`latest.log`）。
     */
    public static final BlockPos ROUND4_START_FOOT = new BlockPos(432, 84, 428);

    /** 真机那一波失败搜索的目标之一（`[Search] 超 tick 预算 … goal=431, 80, 433`）。 */
    public static final BlockPos ROUND4_TARGET = new BlockPos(431, 80, 433);

    /**
     * 探针**结束时留在现场脚位**（= {@link #ROUND4_START_FOOT}）——不回任何"场景起点"。
     *
     * <p>为什么违反"夹具结束要复位"的一般纪律还不算错：**本探针跑在真机存档上**，
     * 别的场景坐标（矿石场景 `56,63,132` 之类）在那份存档里**不是场景**，可能是任意地形
     * —— 实测 2026-09-21：battery 把假人生成在真机存档的世界出生点 `6,65,67` 时当场
     * `hazard=SUFFOCATING` ⇒ `RegressionBattery` 被 `SURVIVAL_INTERRUPT` 打断、整步 `no_verdict`。
     * ⇒ 本探针的"复位"= 回到**它自己动手前**的脚位（就是现场脚位，全程不移动）。
     */
    private static final boolean LEAVE_BOT_AT_SCENE = true;

    /** 1× 预算：与 `PathRequest.WALK_BUDGET` 同值（真机那 52 次失败用它的就是这份）。 */
    private static final SearchBudget BUDGET_1X = SearchBudget.of(20_000, 200L);

    /**
     * 100× 预算：节点轴 ×100（2 000 000）。
     *
     * <p>为什么节点轴是主变量：真机那批搜索的 `nodes` 全部**正好停在 20 000**、`ms` 只有 170–200
     * ⇒ 卡住它的是**节点上限**，不是墙钟。毫秒给 30 s 只是为了不让墙钟成为新的绑定点
     * （真机 20 000 节点 ≈ 185 ms ⇒ 2 000 000 节点 ≈ 18.5 s 量级）。
     */
    private static final SearchBudget BUDGET_100X = SearchBudget.of(2_000_000, 30_000L);

    /**
     * 第 2 遍最多试几个候选。
     *
     * <p>为什么不跑满 13 个：这一遍要回答的是"**存在**一条可行路吗"，**第一个成功即停**；
     * 而最坏情况（全失败）4 × 30 s = 约 2 分钟，是这一步能接受的量级。
     * ⚠️ 若 4 个全失败而 1× 那遍确实复现了 13/13 失败 ⇒ 结论记"4 个候选在 100× 下仍不可达"，
     * 不夸大成"13 个都不可达"（**没试过的就是没试过** —— 与 `SEARCH_LIMIT ≠ UNREACHABLE` 同一条纪律）。
     */
    private static final int PASS2_MAX_CANDIDATES = 4;

    private enum Phase { SETUP, PASS_1X, PASS_100X, SUMMARY, DONE }

    private final BotPlayer bot;
    private final ServerPlayer observer;

    private Phase phase = Phase.SETUP;
    private List<BlockPos> candidates = List.of();
    private int index;
    private int reach1x;
    private int reach100x;
    /** 真的扩展过（`nodes > 1`）的搜索次数 —— 读数有效性判据用（0 ⇒ 一次真实搜索都没发生）。 */
    private int realSearches;
    private BlockPos firstSuccess100x;
    /** 硬前提失败的理由（`check-fixture-hygiene` R1：探针必须能**响亮失败**，不能只会 DONE）。 */
    private String failureReason = "";

    public MineReachProbeTask(BotPlayer bot, ServerPlayer observer) {
        this.bot = bot;
        this.observer = observer;
    }

    @Override
    public TaskTarget target() {
        return TaskTarget.block(ROUND4_TARGET);
    }

    @Override
    public String failureReason() {
        return failureReason;
    }

    @Override
    public String terminalReason() {
        return "reach_probe";
    }

    @Override
    public Status tick() {
        return switch (phase) {
            case SETUP -> setup();
            case PASS_1X -> pass(BUDGET_1X, "1x");
            case PASS_100X -> pass(BUDGET_100X, "100x");
            case SUMMARY -> summary();
            case DONE -> Status.DONE;
        };
    }

    private Status setup() {
        // ⚠️ 本探针**故意**要跑满预算的搜索 ⇒ 必须让开 A1 的每 tick 总账
        //（否则第 2 个候选就会被"A1 已用尽"拒掉，量到的会是 A1 而不是搜索本身）。跑完在 SUMMARY 里还原。
        SearchTickBudget.setLimits(0L, 0, 0);
        SearchTickBudget.resetForFixture();
        teleportTo(ROUND4_START_FOOT);
        MiningBudget budget = MiningBudget.collecting(bot, bot.serverLevel(), ROUND4_TARGET);
        candidates = StandingPointSelector.tunnelCandidates(bot, bot.serverLevel(), ROUND4_TARGET,
                bot.getBlockReach(), budget.maxExtraBreakTicks());
        StringBuilder list = new StringBuilder();
        for (BlockPos candidate : candidates) {
            list.append(candidate.toShortString()).append(' ');
        }
        BotLog.info("[ReachProbe] setup start={} target={} reach={} maxExtraBreakTicks={} candidates={} [{}]",
                ROUND4_START_FOOT.toShortString(), ROUND4_TARGET.toShortString(),
                String.format(Locale.ROOT, "%.2f", bot.getBlockReach()),
                String.format(Locale.ROOT, "%.2f", budget.maxExtraBreakTicks()),
                candidates.size(), list.toString().trim());
        if (candidates.isEmpty()) {
            BotLog.warn("[ReachProbe] 候选为空 ⇒ 现场与真机不符（真机 candidates=13）⇒ 本实验不作数");
            return summary();
        }
        phase = Phase.PASS_1X;
        return Status.RUNNING;
    }

    /** 一遍（一个预算档）：**每 tick 只跑一个候选** —— 单次 30 s 的搜索不该再和别的搜索挤在同一个 tick 里。 */
    private Status pass(SearchBudget budget, String label) {
        if (index >= candidates.size()) {
            return nextPhase(label);
        }
        BlockPos foot = candidates.get(index);
        PathRequest request = PathRequest.miningApproach(bot.getUUID().toString(),
                ROUND4_START_FOOT, foot, "mining-planner").withBudget(budget);
        PathPlan plan = new CorePathPlanner().plan(bot, bot.serverLevel(), request);
        boolean reached = plan.reached();
        // ⭐ 2026-09-21：**"这次搜索真的发生了吗"必须自己数**（见 `summary()` 的读数有效性判据）
        if (plan.nodesExpanded() > 1) {
            realSearches++;
        }
        if (reached) {
            if ("1x".equals(label)) {
                reach1x++;
            } else {
                if (reach100x == 0) {
                    firstSuccess100x = foot;
                }
                reach100x++;
            }
        }
        BotLog.info("[ReachProbe] case={} #{} foot={} budget=[nodes={} millis={}] status={} reached={} partial={}"
                        + " nodes={} movements={} ms={} diag={}",
                label, index, foot.toShortString(), budget.maxNodes(), budget.maxMillis(),
                plan.status(), reached, plan.partial(), plan.nodesExpanded(),
                plan.movements().size(), plan.elapsedMillis(), plan.diagnostics());
        index++;
        // 第 2 遍：第一个成功即停（这一遍只需回答"存在一条路吗"）
        if ("100x".equals(label) && reached) {
            return nextPhase(label);
        }
        if ("100x".equals(label) && index >= Math.min(candidates.size(), PASS2_MAX_CANDIDATES)) {
            return nextPhase(label);
        }
        return Status.RUNNING;
    }

    private Status nextPhase(String label) {
        index = 0;
        if ("1x".equals(label)) {
            phase = Phase.PASS_100X;
            return Status.RUNNING;
        }
        return summary();
    }

    private Status summary() {
        // 还原生产默认（探针跑完不许把闸门留在关闭状态）
        SearchTickBudget.restoreDefaults();
        SearchTickBudget.resetForFixture();
        // ⚠️ 不传送回"场景起点"：本探针跑在**真机存档**上，那些坐标在那里不是场景（见 LEAVE_BOT_AT_SCENE）。
        if (!LEAVE_BOT_AT_SCENE) {
            teleportTo(ROUND4_START_FOOT);
        }

        int tried1x = candidates.size();
        int tried100x = Math.min(candidates.size(), PASS2_MAX_CANDIDATES);
        // ⭐⭐ **读数有效性**（2026-09-21 P3 实测踩到，`silent-measurement-failure` 类）：
        // 本探针跑在**真机存档副本**上，而真机存档会被后续测试**覆盖存盘** ⇒ 起点可能已经踩空
        // （实测 `432,83,428 = air`）⇒ 每次搜索都 `nodes=1 / open set exhausted`，
        // **一次真实搜索都没发生**，而旧版仍报 `reproduced=true` —— 这比没有读数更危险。
        // 判据两条，任一不成立 ⇒ **响亮失败**（不许把"没测到"说成"测到了"）：
        //   ① 起点可站（几何前提）；② 至少一次搜索真的扩展过（`nodes > 1`）。
        boolean startStandable = com.dddgn.alice.pathing.MovementHelper
                .canStandCentered(bot.serverLevel(), ROUND4_START_FOOT);
        boolean measurementValid = startStandable && realSearches > 0;
        // ① 复现性对照：1× 那遍**必须**复现真机的"全部失败"，否则现场不对、实验不作数
        boolean reproduced = candidates.size() >= 10 && reach1x == 0;
        // ② 判据结论：100× 下到不到得了
        String verdict;
        if (!reproduced) {
            verdict = "现场不符（1x reachable=" + reach1x + "/" + tried1x + "）⇒ 实验不作数";
        } else if (reach100x > 0) {
            verdict = "解释甲成立：100x 可达（第 " + (candidates.indexOf(firstSuccess100x) + 1)
                    + " 个候选 " + firstSuccess100x.toShortString() + "）⇒ 病在预算/性能";
        } else {
            verdict = "解释乙倾向成立：100x 下 " + tried100x + " 个候选**仍全部不可达**"
                    + " ⇒ 病在候选与边的可表示性（不是预算能填的差距）";
        }
        String line = "[ReachProbe] SUMMARY start=" + ROUND4_START_FOOT.toShortString()
                + " target=" + ROUND4_TARGET.toShortString()
                + " candidates=" + candidates.size()
                + " · 1x(20k/200ms) reachable=" + reach1x + "/" + tried1x
                + " · 100x(2M/30s) reachable=" + reach100x + "/" + tried100x
                + " · reproduced=" + reproduced
                + " · start_standable=" + startStandable + " realSearches=" + realSearches
                + " ⇒ " + verdict;
        BotLog.info("{}", line);
        if (observer != null && !observer.hasDisconnected() && !observer.isRemoved()) {
            observer.sendSystemMessage(net.minecraft.network.chat.Component.literal("[alice] " + line));
        }
        // ⚠️ 本探针**对"结果"不设通过/失败**（同 `FarPathBenchCheckTask` 的口径）：`SEARCH_LIMIT` / `PARTIAL`
        // 都是**要记录的事实**，不是缺陷判据；判读由人（决策层）做。
        //
        // ⭐ 但它**必须对"实验本身是否成立"响亮失败**（`check-fixture-hygiene` R1：只可能返回 DONE 的方法
        // = "内部失败被吞成静默绿"）。判据 = **1× 对照没有复现真机现场** ⇒ 这次 100× 的读数**不能用来下结论**
        // （现场不对的读数比没有读数更危险：它看起来像结论）。
        if (!measurementValid) {
            phase = Phase.DONE;
            failureReason = "读数无效：start_standable=" + startStandable + " realSearches=" + realSearches
                    + "/" + (tried1x + tried100x)
                    + "（起点踩空或一次都没扩展 ⇒ **一次真实搜索都没发生**；"
                    + "真机存档可能已被后续测试覆盖存盘）⇒ 本次结论不作数";
            BotLog.warn("[ReachProbe] FAILED {}", failureReason);
            return Status.FAILED;
        }
        if (!reproduced) {
            phase = Phase.DONE;
            failureReason = "实验前提不成立（1x 对照未复现真机：reachable=" + reach1x + "/" + tried1x
                    + " candidates=" + candidates.size() + "）⇒ 100x 读数不作数";
            BotLog.warn("[ReachProbe] FAILED {}", failureReason);
            return Status.FAILED;
        }
        phase = Phase.DONE;
        return Status.DONE;
    }

    private void teleportTo(BlockPos foot) {
        bot.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        bot.controller().stopMovement();
        bot.teleportTo(bot.serverLevel(), foot.getX() + 0.5D, foot.getY(), foot.getZ() + 0.5D,
                java.util.Set.of(), bot.getYRot(), bot.getXRot());
    }
}
