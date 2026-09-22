package com.dddgn.alice.task.check.modules;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.task.BreakEnterHeadBlockedCheckTask;
import com.dddgn.alice.task.CoarseGoalPrefixCheckTask;
import com.dddgn.alice.task.CleanupWrappedTask;
import com.dddgn.alice.task.EdgeCompletenessCheckTask;
import com.dddgn.alice.task.ContrastTimerCheckTask;
import com.dddgn.alice.task.FallDiagnosticTask;
import com.dddgn.alice.task.PlaceStepDiagonalCheckTask;
import com.dddgn.alice.task.PillarDiagnosticTask;
import com.dddgn.alice.task.check.CheckContext;
import com.dddgn.alice.task.check.CheckModule;
import com.dddgn.alice.task.check.CheckProfile;
import com.dddgn.alice.task.check.CheckStep;
import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.Set;

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
                // ⭐ `D-379`（2026-09-21 第八轮真机）：**「破坏通行」破掉的中间列是 bot 要踩过去的一格
                // ⇒ 它必须立得住**（`canWalkOn(mid)`）。真机 = 破掉中间格后从中间列掉进水里 → 沉底 → 溺水。
                // 规划级（边生成层 + 执行工厂准入，两侧同谓词），3 用例（悬空+水 / 悬空+浅坑 / 立在地板上）。
                // EXTRA（自建并还原 3 格场景 + 1 次边生成）；便宜但不守"图完整性"级不变式 ⇒ 不进 CORE。
                CheckStep.of("break_traverse_footing", CheckProfile.EXTRA, List.of(), null,
                        () -> new com.dddgn.alice.task.BreakTraverseFootingCheckTask(bot, ctx.observer()),
                        300),
                // ⭐⭐ `G2`（2026-09-21，D-374 事故的结构性补救）：**边集完备性差集** ——
                // 局部谓词（canTraverse/canAscend/canDescend/可破入）给出的「应有边」 vs
                // `SurfaceMovementProvider.appendCandidates` 实际产出的边。零搜索、确定性、覆盖每格×每方向。
                // EXTRA：会自建并还原一整个 13×3×13 场景（约 5 000 次 setBlock），不进 CORE。
                // ⭐ `D-376`（2026-09-21 第八轮真机）：**搭石斜下的「过渡空间」**。规划级（边生成层 +
                // 执行工厂准入），2 用例（过渡被挡 ⇒ 不许生成该边 / 过渡通畅 ⇒ 必须生成）。
                // 它钉的是真机那 222 tick ×2 的"顶在格边界原地走"。
                CheckStep.of("place_step_descend_clearance", CheckProfile.EXTRA, List.of(), null,
                        () -> new com.dddgn.alice.task.PlaceStepDescendClearanceCheckTask(bot, ctx.observer()),
                        400),
                // ⭐⭐ `survey/27 §3 #1` 的收口判据（`D-378`）：`D-374` 修的那一行谓词，是不是**真的**
                // 把「深矿直挖路」还回来了 —— 当场自建基岩隧道（5 个「脚位空 + 头位实」夹缝 + 一条 46 格绕远），
                // 同一请求跑三遍：生产 provider（修复后）⇒ ≤7 段；旧谓词边过滤（修复前）⇒ ≥40 段；
                // 旧谓词 + 紧预算 ⇒ 到不了且预算打满（= 复现 `SEARCH_LIMIT` 的机制）。
                // 距离 1 的能力版是 `break_enter_head_blocked`（MAIN）；本步量的是**规模/绕远**。
                // EXTRA：自建并还原一个 10×23×4 基岩盒（约 1 000 次 setBlock）+ 3 次小搜索。
                CheckStep.of("head_blocked_route_closure", CheckProfile.EXTRA, List.of(), null,
                        () -> new com.dddgn.alice.task.HeadBlockedRouteClosureCheckTask(bot, ctx.observer()),
                        600),
                CheckStep.of("edge_completeness", CheckProfile.EXTRA, List.of(), null,
                        () -> new EdgeCompletenessCheckTask(bot, ctx.observer()), 200),
                // D-336（2026-09-19）：**斜向上升那一格**（规划级）—— 能力 + 信封两用例，互为反证。
                CheckStep.of("place_step_diagonal", CheckProfile.EXTRA, List.of(), null,
                        () -> new PlaceStepDiagonalCheckTask(bot, ctx.observer()), 400),
                CheckStep.of("fall_execute", CheckProfile.EXTRA,
                        List.of("alice_test:fall_course_terrain"), null,
                        () -> new CleanupWrappedTask(new FallDiagnosticTask(bot, ctx.observer()), bot), 600),
                CheckStep.of("pillar_execute", CheckProfile.EXTRA,
                        List.of("alice_test:pillar_course_terrain"), null,
                        () -> new CleanupWrappedTask(new PillarDiagnosticTask(bot, ctx.observer()), bot), 900),
                CheckStep.of("contrast_timer", CheckProfile.EXTRA,
                        List.of("alice_test:ore_course_terrain"),
                        () -> teleportTo(bot, new BlockPos(56, 63, 132)),
                        () -> new ContrastTimerCheckTask(bot, ctx.observer()), 200));
    }

    /** 与电池 `teleportBot` **逐字段一致** ✓（模块不能调它的私有方法 ✗ ⇒ 这里复制同一套 ✓）。 */
    private static void teleportTo(BotPlayer bot, BlockPos foot) {
        bot.teleportTo(bot.serverLevel(), foot.getX() + 0.5D, foot.getY(), foot.getZ() + 0.5D,
                Set.of(), bot.getYRot(), bot.getXRot());
        bot.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        bot.controller().stopMovement();
    }
}
