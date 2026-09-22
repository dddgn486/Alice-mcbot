package com.dddgn.alice.task.check.modules;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.item.FixtureToolKit;
import com.dddgn.alice.job.GoalSpec;
import com.dddgn.alice.job.mine.MineCandidateSource;
import com.dddgn.alice.job.mine.MineJob;
import com.dddgn.alice.job.policy.NearestPolicy;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.task.MineCourseDiagnosticTask;
import com.dddgn.alice.task.MineMenuCheckTask;
import com.dddgn.alice.task.MineReachProbeTask;
import com.dddgn.alice.task.MineRegressionTask;
import com.dddgn.alice.task.MineRunMetricsCheckTask;
import com.dddgn.alice.task.MineVeinPropagationCheckTask;
import com.dddgn.alice.task.MiningSearchLimitHonestyCheckTask;
import com.dddgn.alice.task.MiningWaterBreakCostCheckTask;
import com.dddgn.alice.task.NoProgressCheckTask;
import com.dddgn.alice.task.OreCourseAnchor;
import com.dddgn.alice.task.check.CheckContext;
import com.dddgn.alice.task.MineDropRangeCheckTask;
import com.dddgn.alice.task.MineInventoryCheckTask;
import com.dddgn.alice.task.check.CheckModule;
import com.dddgn.alice.task.check.CheckProfile;
import com.dddgn.alice.task.check.CheckStep;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

import java.util.List;
import java.util.Set;

/**
 * **挖掘模块（R-2 第五片，10 步）**：`mine_regression` · `no_progress` · `mine_menu` · `mine_job`
 * · `mine_inventory` · `mine_no_tool` · `mine_stale` · `mine_budget` · ⭐ `mine_far_drop`（`D-346`：
 * 收集的追取上限必须覆盖本作业自己的作用域 —— 旧上限 32 < `MineJob` 作用域直径 48）· ⭐ `mine_run_metrics`
 * （`D-347`：运行账的现场取证 —— 连续 3 次真作业 + 1 次反向对照 ⇒ 到达率/世界改动数是量出来的）。
 *
 * <p>为什么这七步归一类：它们是**同一条挖掘链路的不同环节**，而且**共享同一个场景**（`ore_course_terrain`）
 * 与同一批"必须不猜"的红线：
 * <ul>
 *   <li>`mine_regression`：挖掘执行器全量回归（它**自带地形**：逐个用例跑 `alice_test:<terrain>_terrain` ✓）；</li>
 *   <li>`no_progress`：长作业周期复评（停滞 ⇒ 报一次 `NO_PROGRESS`）；</li>
 *   <li>`mine_menu`：候选菜单契约（**`mine` 不许猜位置**）；</li>
 *   <li>`mine_job`：生产入口真挖（`MineJob` + 配额）；</li>
 *   <li>`mine_no_tool`：缺工具必须报 `tool_missing`，**不许被总括码盖掉**；</li>
 *   <li>`mine_stale`：身份复检失败必须报 `stale_target`；</li>
 *   <li>`mine_budget`：预算耗尽必须报 `write_budget_exhausted`。</li>
 * </ul>
 *
 * <p>**后三步是"归因"判据**（G3）：它们都用 `doneWhen` 把"终态理由码恰好是那一个"变成判据
 * ⇒ 归因一旦退化成总括码，`doneWhen` 永不成立 ⇒ 预算耗尽 ⇒ 判红 ✓（不是"看起来跑通了"）。
 * ⚠️ 它们同时是**编排器必须支持 `doneWhen` 的动因**（见 D-300）✓。
 *
 * <p>**本模块自带的前提**：`mine_regression` 由模块**先传送到课程起点热区块**（它自己再跑地形函数 ——
 * 顺序反了就会让 `/fill` 落在冷区块上 ✗，D-296 记过这个坑）；其余六步各自传送到 `ore_course` 起点发料。
 */
public final class MiningModule implements CheckModule {

    /** 与 `MineJobItem` 对齐的挖掘 Job 配额（原电池私有常量，搬过来时**逐字保留** ✓）。 */
    private static final int MINE_QUOTA = 4;

    @Override
    public String id() {
        return "mining";
    }

    @Override
    public String title() {
        return "挖掘（执行器回归 / 周期复评 / 候选契约 / 生产入口 / 归因三连）";
    }

    @Override
    public List<CheckStep> steps(CheckContext ctx) {
        BotPlayer bot = ctx.bot();
        var observer = ctx.observer();
        var scope = ctx.scope();
        List<String> ore = List.of("alice_test:ore_course_terrain");
        Runnable toOre = () -> to(bot, OreCourseAnchor.START_FOOT);
        Runnable staged = () -> {          // 传送到起点 + 清背包 + 发镐（`mine_job`/`mine_stale`/`mine_budget` 共用）
            toOre.run();
            FixtureToolKit.resetInventory(bot);
            FixtureToolKit.ensurePickaxe(bot);
        };
        return List.of(
                // 挖掘执行器**全量回归**：自带地形（逐个用例跑 `alice_test:<terrain>_terrain`）⇒ 本模块只负责
                // **先把区块热起来**（模块自身的传送在前，夹具的地形函数在后 ⇒ 顺序正确 ✓）
                CheckStep.of("mine_regression", CheckProfile.BASELINE, List.of(),
                        () -> to(bot, MineCourseDiagnosticTask.START_FOOT),
                        () -> new MineRegressionTask(bot, observer, scope), 3200),
                // M2（G2）：长作业周期复评（窗口 40 tick，夹具自己造停滞与"重新武装"）
                CheckStep.of("no_progress", CheckProfile.MAIN, ore, toOre,
                        () -> new NoProgressCheckTask(bot, observer), 400),
                // M1（G1）：挖矿候选菜单契约自检（纯逻辑，不改世界、不调 LLM）—— 矿石场景保证"附近有矿"
                CheckStep.of("mine_menu", CheckProfile.MAIN, ore, toOre,
                        () -> new MineMenuCheckTask(bot, observer), 300),
                // 挖掘 Job：同上（ore_course + 复刻 MineJobItem 的发料）
                CheckStep.of("mine_job", CheckProfile.BASELINE, ore, staged,
                        () -> new MineJob(bot,
                                GoalSpec.mineBlocks(OreCourseAnchor.START_FOOT,
                                        MineCandidateSource.SCAN_RADIUS, MINE_QUOTA, 3600),
                                scope,
                                new MineCandidateSource(MineCandidateSource.Target.ofBlock(Blocks.IRON_ORE),
                                        MineCandidateSource.SCAN_RADIUS),
                                new NearestPolicy()),
                        2200),
                // D-335（2026-09-19）：**容量守卫**（前置 + 作业中）—— 对際伐木 `LumberFailureCheckTask`。
                // 判据钉在"世界有没有被白改"上：前置满包 ⇒ 一格不动；作业中满包 ⇒ 恰好只少 1 格。
                // ⭐ `D-360`（2026-09-20）：**真机实测测试工具的自检** —— 就地开采 + 阻断 LLM 接手 + 统计口径。
                // 三件事都会**静默失败**（锁没生效 / 没打点 / 口径算错）⇒ 必须有判据，不能"手动试一下"。
                CheckStep.of("mine_survey", CheckProfile.EXTRA, ore, staged,
                        () -> new com.dddgn.alice.task.MineSurveyCheckTask(bot, scope), 1400),
                CheckStep.of("mine_inventory", CheckProfile.EXTRA, ore, staged,
                        () -> new MineInventoryCheckTask(bot, scope), 1400),
                // M3（G3 归因）：**缺工具**必须如实报 `tool_missing`，不许被总括码 `no_reachable_candidate` 盖掉。
                // 与上一步**同一场景、同一 Job**，唯一差别 = **不发镐** ⇒ 每个候选都 `no_suitable_tool`。
                // 判据挂在 `doneWhen`：终态理由一旦成为 `tool_missing` 即记 PASS（`MineJob` 会 FAILED，
                // 这是**预期**的失败 —— 该步验的是**归因**，不是"挖到了"）。
                CheckStep.of("mine_no_tool", CheckProfile.MAIN, ore, () -> {
                    toOre.run();
                    FixtureToolKit.resetInventory(bot);   // 有意不发任何工具
                    BotLog.info("[Mining] mine_no_tool 夹具：已清空背包且**不发镐**（验归因，不是验挖掘）");
                }, () -> new MineJob(bot,
                        GoalSpec.mineBlocks(OreCourseAnchor.START_FOOT,
                                MineCandidateSource.SCAN_RADIUS, 2, 1200),
                        scope,
                        new MineCandidateSource(MineCandidateSource.Target.ofBlock(Blocks.IRON_ORE),
                                MineCandidateSource.SCAN_RADIUS),
                        new NearestPolicy()),
                        600)
                        // 归因对了就判过；一直是总括码 ⇒ doneWhen 永不成立 ⇒ 预算耗尽记 TIMEOUT（判红）。
                        // M4b：**顺带把"任务树里必须带子阶段的失败事实"变成判据** —— 这是"必然失败"的步骤，
                        // 正好用来断言 `tree[].lastFailure` 不再永远是空。
                        .withDoneWhen(task -> {
                            if (!(task instanceof MineJob job) || !"tool_missing".equals(job.terminalReason())) {
                                return false;
                            }
                            String line = job.subTasks().stream()
                                    .map(com.dddgn.alice.task.TaskNode::lastFailure)
                                    .filter(failure -> !failure.isBlank())
                                    .findFirst().orElse("");
                            if (line.isBlank()) {
                                BotLog.warn("[Mining] M4b 判据：mine_no_tool 已 tool_missing，但任务树里"
                                        + "**没有**任何子阶段的 lastFailure ⇒ 判红（树={}）",
                                        job.subTasks().stream()
                                                .map(com.dddgn.alice.task.TaskNode::describe).toList());
                                return false;
                            }
                            BotLog.info("[Mining] M4b 判据通过：任务树带子阶段失败事实 lastFailure={}", line);
                            return true;
                        }),
                // M3b ①（G3 归因）：`stale_target` —— 每个候选的身份复检都失败（决策后被改动）。
                // 夹具只替掉**那一次判定**（恒 false），理由码与真实竞态完全一样（`target_replaced`）。
                CheckStep.of("mine_stale", CheckProfile.MAIN, ore, () -> {
                    staged.run();
                    BotLog.info("[Mining] mine_stale 夹具：身份复检恒 false（构造 target_replaced 竞态）");
                }, () -> new MineJob(bot,
                        GoalSpec.mineBlocks(OreCourseAnchor.START_FOOT,
                                MineCandidateSource.SCAN_RADIUS, 1, 600),
                        scope,
                        new MineCandidateSource(MineCandidateSource.Target.ofBlock(Blocks.IRON_ORE),
                                MineCandidateSource.SCAN_RADIUS),
                        new NearestPolicy(),
                        pos -> false),
                        400)
                        .withDoneWhen(task -> task instanceof MineJob job
                                && "stale_target".equals(job.terminalReason())),
                // M3b ②（G3 归因）：`write_budget_exhausted` —— **本步作用域**的破坏预算压到 0
                // （`WriteBudget.setCaps` 是既有夹具专用缝，不接玩家命令）⇒ 每次破坏都被拒 ⇒ 全预算码。
                // ⚠️ 这条**依赖"编排器在 provision 之前就把本步作用域开好"**（与电池 `setup` 同序 ✓）：
                // 否则 `WriteBudget.scopeOf(bot)` 会指到孤儿作用域 ⇒ 预算没生效 ⇒ 本步以超时红 ✗（D-301）。
                CheckStep.of("mine_budget", CheckProfile.MAIN, ore, () -> {
                    staged.run();
                    com.dddgn.alice.action.WriteBudget.setCaps(
                            com.dddgn.alice.action.WriteBudget.scopeOf(bot),
                            new com.dddgn.alice.action.WriteBudget.Caps(0, 0));
                    BotLog.info("[Mining] mine_budget 夹具：本作用域写入预算压到 0 破坏 / 0 放置（scope={}）",
                            com.dddgn.alice.action.WriteBudget.scopeOf(bot));
                }, () -> new MineJob(bot,
                        GoalSpec.mineBlocks(OreCourseAnchor.START_FOOT,
                                MineCandidateSource.SCAN_RADIUS, 1, 600),
                        scope,
                        new MineCandidateSource(MineCandidateSource.Target.ofBlock(Blocks.IRON_ORE),
                                MineCandidateSource.SCAN_RADIUS),
                        new NearestPolicy()),
                        400)
                        .withDoneWhen(task -> task instanceof MineJob job
                                && "write_budget_exhausted".equals(job.terminalReason())),
                // ⭐ `D-346`（2026-09-20）：**收集的追取上限必须覆盖本作业自己的作用域** —— 旧实现把
                // `MAX_CHASE_DISTANCE` 写死 32，比 `MineJob` 自己的作用域直径（`2 × SCAN_RADIUS(24)` = 48）
                // 还小 ⇒ **自己挖出来的产物被自己的上限永久退休**（`retire reason=too_far`）⇒
                // `gained < minedCount` ⇒ `FAILED product_not_collected`。
                // 夹具自带孤立空中走廊（原点 3200,100,2000）+ 近件 8 格 / 远件 40 格 + 两件登记成
                // `OURS_DIRECT` ⇒ 判据 = "两件都进背包 + 地上不许剩"（**修复前是红的**，取证过程见 `D-345`）。
                CheckStep.of("mine_far_drop", CheckProfile.EXTRA, List.of(), null,
                        () -> new MineDropRangeCheckTask(bot, observer, scope), 900),
                // ⭐ `D-347`（2026-09-20）：**运行账取证**（`survey/22 §5.2③` 的"可测量判据"）——
                // 孤立空中矿道里连跑 3 次真 `MineJob`（配额 2）＋ 1 次**反向对照**（目标类型不存在），
                // 断言：每次到达都自报 1 次、每次世界改动恰好 2、反向对照那两次增量都是 0
                // ⇒ **到达率 3/4 是"量出来的"而不是恒等式**。EXTRA（自建地形 + 4 次运行）⇒ 不进 CORE。
                CheckStep.of("mine_run_metrics", CheckProfile.EXTRA, List.of(), null,
                        () -> new MineRunMetricsCheckTask(bot, observer, scope), 4800),
                // ⭐ **B（2026-09-21）：深矿可达性判据探针** —— 回答 `survey/24 §2.4` 第 3 问 /
                // `survey/25 §2.3` 第 1 问「给 100× 预算，这个深矿到底能不能到？」。
                // 真机第四轮只留下 `SEARCH_LIMIT`（= 预算耗尽、**可达性未知**）⇒ 证据同时兼容
                // 「预算不够」与「根本没有路/表示不了」两种解释，而这两种解释指向**完全不同的修法**。
                // ⚠️ **必须跑在真机那次的存档上**（`saves/新的世界 (2)`）；EXTRA（不进 CORE）⇒
                // 只由 `single:mine_reach_probe` 显式跑。本探针**不设通过/失败**（记事实，判读由人做）。
                CheckStep.of("mine_reach_probe", CheckProfile.EXTRA, List.of(), null,
                        () -> new MineReachProbeTask(bot, observer), 400),
                // ⭐ `D-385`（2026-09-21）：**规划期的挖掘成本必须等于执行侧真值** ——
                // 执行侧走 vanilla，而 vanilla 有"眼在水里 ÷5""离地 ÷5"两项状态惩罚，
                // 旧 `estimateBreakTicks` 只等于「陆地 + 在地面」那一档 ⇒ 水下/离地乐观 5×~25×。
                // 本步在自建水池 + 石壁上量四个状态组合（估计 vs `1.0F/getDestroyProgress`），
                // 并用生产边生成器断言"水里那一步的破坏项确实贵了 (est_湿−est_干)/6"⇒ 规划器自然偏向放置。
                CheckStep.of("mining_water_break_cost", CheckProfile.EXTRA, List.of(), null,
                        () -> new MiningWaterBreakCostCheckTask(bot, observer), 600),
                // `P1-b`（2026-09-22 真机根因）：A1 每 tick 搜索总账拒一次搜索时，规划器**不许**把
                // 「本轮没评价完」写成「不可挖/不可达」——真机 377 次 `found_but_unminable` 就是这么来的。
                // 本步同 tick 内先把额度占满（前提断言），再规划 ⇒ 理由必须是瞬时的 `search_incomplete`；
                // 等 tick 边界后对**同一目标**再规划 ⇒ 必须成功（`SEARCH_LIMIT ≠ UNREACHABLE` 的行为级证明）。
                CheckStep.of("mining_search_limit_honesty", CheckProfile.EXTRA, List.of(), null,
                        () -> new MiningSearchLimitHonestyCheckTask(bot, observer), 400),
                // ⭐⭐ `D-389`（2026-09-22）：**沿脉传播**的行为级判据 —— 石壳 + 3×2×3 铁矿脉（26 邻接），
                // bot 只能挖进去；进脉之后每个下一个目标都在 1 格内 ⇒ 便宜。
                // 断言 ① 传播真的触发（veinPropagations > 0）② 矿簇被挖穿（mined ≥ 12/18）。
                // 红臂：注释掉 `MineJob.enqueueVeinNeighbours(...)` ⇒ ① 必红。EXTRA（跑一个真实作业）。
                CheckStep.of("mine_vein_propagation", CheckProfile.EXTRA, List.of(), null,
                        () -> new MineVeinPropagationCheckTask(bot, observer), 2800));
    }

    /** 传送到统一起点（与电池 `teleportBot` 逐字段一致 ✓；顺带起"先热区块再 fill"的作用 ✓）。 */
    private static void to(BotPlayer bot, BlockPos foot) {
        bot.teleportTo(bot.serverLevel(), foot.getX() + 0.5D, foot.getY(), foot.getZ() + 0.5D,
                Set.of(), bot.getYRot(), bot.getXRot());
        bot.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        bot.controller().stopMovement();
    }
}
