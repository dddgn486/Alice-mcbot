package com.dddgn.alice.fixture.check.modules;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.fixture.BreakEnterHeadBlockedCheckTask;
import com.dddgn.alice.fixture.CoarseGoalPrefixCheckTask;
import com.dddgn.alice.fixture.CleanupWrappedTask;
import com.dddgn.alice.fixture.K2AdjacentGoalCheckTask;
import com.dddgn.alice.fixture.EdgeCompletenessCheckTask;
import com.dddgn.alice.fixture.ContrastTimerCheckTask;
import com.dddgn.alice.fixture.FallDiagnosticTask;
import com.dddgn.alice.fixture.PlaceStepDiagonalCheckTask;
import com.dddgn.alice.fixture.TickBudgetBenchTask;
import com.dddgn.alice.fixture.PillarDiagnosticTask;
import com.dddgn.alice.fixture.check.CheckContext;
import com.dddgn.alice.fixture.check.CheckModule;
import com.dddgn.alice.fixture.check.CheckProfile;
import com.dddgn.alice.fixture.check.CheckStep;
import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.Set;
import com.dddgn.alice.fixture.BreakHazardCheckTask;
import com.dddgn.alice.fixture.BreakTraverseFootingCheckTask;
import com.dddgn.alice.fixture.ChannelPlaceGuardCheckTask;
import com.dddgn.alice.fixture.CorridorSkipPredicateCheckTask;
import com.dddgn.alice.fixture.HeadBlockedRouteClosureCheckTask;
import com.dddgn.alice.fixture.PlaceStepDescendClearanceCheckTask;

/**
 * **移动/寻路模块（R-2：第一个"带场景"的模块）**：`fall_execute` + `pillar_execute` + `contrast_timer`。
 *
 * <p>为什么用这三步当**带场景模块**的样板：它们各自需要一块专用地形（落差 / 竖井 / 停表场景 ✓），
 * 正好检验编排器 v1 的"**先热区块、再跑场景**"顺序 ✓ —— 旧电池是"场景→provision"✗，
 * 区块冷时 `/fill` 不落地 ⇒ 判据在虚空里假绿 ✗（`single:craft_table` 单跑必红就是这个坑 ✓）。
 */
public final class PathingModule implements CheckModule {

    @Override
    public String id() {
        return "pathing";
    }

    @Override
    public String title() {
        return "移动执行（落差 / 竖井 / 停表）";
    }

    @Override
    public List<CheckStep> steps(CheckContext ctx) {
        BotPlayer bot = ctx.bot();
        return List.of(
                // D-374（2026-09-21）：⭐ **脚位空、头位实**的目的地必须有入边（规划级；四用例互为对照）。
                // MAIN：便宜（无场景文件、无执行、4 次 1 格远的规划）且守的是**内核图完整性**不变式 ⇒ 进 CORE。
                CheckStep.of("break_enter_head_blocked", CheckProfile.MAIN, List.of(), null,
                        () -> new BreakEnterHeadBlockedCheckTask(bot, ctx.observer()), 120),
                // ⭐⭐ `D-390`（2026-09-22，用户追问"远目标/粗目标+滚动重规划 CORE 有没有证明"）：
                // **没有** —— `far_path_bench`（唯一完整测这件事的基准）是 EXTRA，而 CORE 的搜索最大只 4–5 ms
                // ⇒ 50 ms 收紧后"远目标还走不走得动"在回归保护之外。本步把它的**核心断言**提进 CORE：
                // 粗目标区未加载时不许 GOAL_NOT_LOADED / 不许 UNREACHABLE / **必须给出朝目标推进的前缀** /
                // 跑完未加载区仍未被读（`D-132`）。场景自建 8 格走廊 ⇒ 前缀有确定落脚点（不依赖世界地形）。
                CheckStep.of("coarse_goal_prefix", CheckProfile.MAIN, List.of(), null,
                        () -> new CoarseGoalPrefixCheckTask(bot, ctx.observer()), 300),
                // ⭐⭐ `K2` 第一刀 ＋ `1a`=甲（`D-517`）：**相邻目标**（`GoalAdjacent`，第 3 个 `GoalSpec`
                // 实现）＋ **换脚格排除集**。规划级（4 次 `CorePathPlanner` 规划 + 目标谓词逐条真值），
                // **自建孤立平台**（不依赖电池世界地形）⇒ 便宜、确定性，守的是"**内核目标插件点仍然可用**"
                // 这条图完整性不变式（同 `break_enter_head_blocked` 的理由）⇒ MAIN（CORE 跑）。
                // ⚠️ 它**不执行**路径（不 tick 移动）⇒ 零副作用、不替换正在跑的电池步（`D-254`）。
                CheckStep.of("adjacent_goal_exclusion", CheckProfile.MAIN, List.of(), null,
                        () -> new K2AdjacentGoalCheckTask(bot, ctx.observer()), 240),
                // ⭐ `D-379`（2026-09-21 第八轮真机）：**「破坏通行」破掉的中间列是 bot 要踩过去的一格
                // ⇒ 它必须立得住**（`canWalkOn(mid)`）。真机 = 破掉中间格后从中间列掉进水里 → 沉底 → 溺水。
                // 规划级（边生成层 + 执行工厂准入，两侧同谓词），3 用例（悬空+水 / 悬空+浅坑 / 立在地板上）。
                // EXTRA（自建并还原 3 格场景 + 1 次边生成）；便宜但不守"图完整性"级不变式 ⇒ 不进 CORE。
                CheckStep.of("break_traverse_footing", CheckProfile.EXTRA, List.of(), null,
                        () -> new com.dddgn.alice.fixture.BreakTraverseFootingCheckTask(bot, ctx.observer()),
                        300),
                // ⭐⭐ `G2`（2026-09-21，D-374 事故的结构性补救）：**边集完备性差集** ——
                // 局部谓词（canTraverse/canAscend/canDescend/可破入）给出的「应有边」 vs
                // `SurfaceMovementProvider.appendCandidates` 实际产出的边。零搜索、确定性、覆盖每格×每方向。
                // EXTRA：会自建并还原一整个 13×3×13 场景（约 5 000 次 setBlock），不进 CORE。
                // ⭐ `D-376`（2026-09-21 第八轮真机）：**搭石斜下的「过渡空间」**。规划级（边生成层 +
                // 执行工厂准入），2 用例（过渡被挡 ⇒ 不许生成该边 / 过渡通畅 ⇒ 必须生成）。
                // 它钉的是真机那 222 tick ×2 的"顶在格边界原地走"。
                CheckStep.of("place_step_descend_clearance", CheckProfile.EXTRA, List.of(), null,
                        () -> new com.dddgn.alice.fixture.PlaceStepDescendClearanceCheckTask(bot, ctx.observer()),
                        400),
                // ⭐⭐ `survey/27 §3 #1` 的收口判据（`D-378`）：`D-374` 修的那一行谓词，是不是**真的**
                // 把「深矿直挖路」还回来了 —— 当场自建基岩隧道（5 个「脚位空 + 头位实」夹缝 + 一条 46 格绕远），
                // 同一请求跑三遍：生产 provider（修复后）⇒ ≤7 段；旧谓词边过滤（修复前）⇒ ≥40 段；
                // 旧谓词 + 紧预算 ⇒ 到不了且预算打满（= 复现 `SEARCH_LIMIT` 的机制）。
                // 距离 1 的能力版是 `break_enter_head_blocked`（MAIN）；本步量的是**规模/绕远**。
                // EXTRA：自建并还原一个 10×23×4 基岩盒（约 1 000 次 setBlock）+ 3 次小搜索。
                CheckStep.of("head_blocked_route_closure", CheckProfile.EXTRA, List.of(), null,
                        () -> new com.dddgn.alice.fixture.HeadBlockedRouteClosureCheckTask(bot, ctx.observer()),
                        600),
                CheckStep.of("edge_completeness", CheckProfile.EXTRA, List.of(), null,
                        () -> new EdgeCompletenessCheckTask(bot, ctx.observer()), 200),
                // ⭐ `I5` 放置面（2026-09-25 用户裁定）：**本作业自己的通道层格不许被放方块**
                // （规划期剪枝 + 执行期最后一道闸门共用 `BlockInteraction.placementRefusal`）。
                // 含负对照（撤掉作用域 ⇒ 必须看见"放置格落在通道层格里"的那条边）。
                CheckStep.of("channel_place_guard", CheckProfile.EXTRA, List.of(), null,
                        () -> new com.dddgn.alice.fixture.ChannelPlaceGuardCheckTask(bot, ctx.observer()),
                        400),
                // ⭐ `F1`/`1.4j①`（2026-09-26 真机第五轮 4/4）：鱼骨「这格算不算已经通」= **单格判据**。
                // 三几何 + 旧谓词负对照；真机探针签名（footPassable=true / headPassable=false）逐字复现。
                CheckStep.of("corridor_skip_predicate", CheckProfile.EXTRA, List.of(), null,
                        () -> new com.dddgn.alice.fixture.CorridorSkipPredicateCheckTask(bot, ctx.observer()),
                        300),
                // D-336（2026-09-19）：**斜向上升那一格**（规划级）—— 能力 + 信封两用例，互为反证。
                CheckStep.of("place_step_diagonal", CheckProfile.EXTRA, List.of(), null,
                        () -> new PlaceStepDiagonalCheckTask(bot, ctx.observer()), 400),
                CheckStep.of("fall_execute", CheckProfile.EXTRA,
                        List.of("alice_test:fall_course_terrain"), null,
                        () -> new CleanupWrappedTask(new FallDiagnosticTask(bot, ctx.observer()), bot), 600),
                // ⚠️ 单项预算必须 **≥ 夹具自己的内部上限**（`PillarDiagnosticTask` 的 1200；`PL-1` 的
                // 教训 = 预算比夹具小 ⇒ 夹具自己的判据根本没机会打印，只剩 `TIMEOUT（单项预算用尽）`）。
                // `D-427` 起本步还要跑"水柱准入契约"（自建 4 格灌水竖井 + 1 次规划 + 3 次准入判定）。
                CheckStep.of("pillar_execute", CheckProfile.EXTRA,
                        List.of("alice_test:pillar_course_terrain"), null,
                        () -> new CleanupWrappedTask(new PillarDiagnosticTask(bot, ctx.observer()), bot), 1300),
                CheckStep.of("contrast_timer", CheckProfile.EXTRA,
                        List.of("alice_test:ore_course_terrain"),
                        () -> teleportTo(bot, new BlockPos(56, 63, 132)),
                        () -> new ContrastTimerCheckTask(bot, ctx.observer()), 200),
                // ⭐ `P4′`（2026-09-24）：**每 tick 搜索预算的 A/B 台架**（`TickBudgetBenchTask`）——
                // 一次运行同时量三种配置（`PROD(400/1/32)` · `TIGHTENED(60/1/32)` · `UNGATED`=闸门全关）
                // × 两种负载（13 次全预算搜索 / 30 次廉价链），并**实测出红臂**：
                // `UNGATED` 那格故意让一个 tick 烧 ≥2 s（A1 之前的真机形态）⇒ 电池日志里会出现
                // `Can't keep up!`（原版阈值 = 单 tick 落后 >2000 ms），而闸门开着时在算术上够不到。
                // EXTRA：**故意制造一次 2.4 s 的 tick** ⇒ 绝不进 CORE。预算 400 > 台架自己的 300（PL-1 教训）。
                CheckStep.of("tick_budget_bench", CheckProfile.EXTRA, List.of(), null,
                        () -> new TickBudgetBenchTask(bot, ctx.observer()), 400),
                // ⭐ `1-2`（批次 1，`D-533`）：`D1`（破坏危险邻接 = Baritone `avoidBreaking:68-82`
                // ＋ `avoidAdjacentBreaking:84-108`）＋ `D2`（**正上方**落体 ⇒ **计价累加**，
                // Baritone `getMiningDurationTicks:600-605` 的 `includeFalling`）。
                // ⭐ 它钉的是**分工**那一句：**正上方是"钱"、侧邻是"命"**（把正上方也做成禁止 =
                // 砍掉"拆一格、上面那块自己掉下来"这条正常挖掘流程的下半段）；以及
                // "搜索与执行同一入口"（`SurfaceMovementProvider` 三处都先问 `BlockInteraction.breakable`）。
                // MAIN（进 CORE）：安全相关 ＋ 便宜（自建小场景、零搜索、零移动，约 20 tick）。
                // ⚠️ 按 `D-309` 口径**追加在末位** ⇒ 既有步的次序一个格子都不动。
                CheckStep.of("break_hazard", CheckProfile.MAIN, List.of(), null,
                        () -> new com.dddgn.alice.fixture.BreakHazardCheckTask(bot, ctx.observer()), 200));
    }

    /** 与电池 `teleportBot` **逐字段一致** ✓（模块不能调它的私有方法 ✗ ⇒ 这里复制同一套 ✓）。 */
    private static void teleportTo(BotPlayer bot, BlockPos foot) {
        bot.teleportTo(bot.serverLevel(), foot.getX() + 0.5D, foot.getY(), foot.getZ() + 0.5D,
                Set.of(), bot.getYRot(), bot.getXRot());
        bot.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        bot.controller().stopMovement();
    }
}
