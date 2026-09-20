package com.dddgn.alice.task;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.job.GoalSpec;
import com.dddgn.alice.job.lumber.LumberCandidateSource;
import com.dddgn.alice.job.lumber.LumberJob;
import com.dddgn.alice.job.policy.NearestPolicy;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.perception.ScopeBuffer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 伐木失败语义自检（切片 J4，入口 {@code alice:lumber_failure_check}）。
 *
 * <p>设计目标（{@code docs/JOB_LAYER_DESIGN.md} §6.2c）：**五条终止路径各有场景**，且理由必须机器可读。
 * 本任务是那五条路的夹具——**每条都用真实 {@link LumberJob} 跑出来**，不是一个字符串匹配：
 *
 * <ol>
 *   <li>{@code no_candidates}：扫描半径内没有树 → 终止理由 `no_reachable_candidate`；</li>
 *   <li>{@code all_rejected}：范围内只有一棵超大树（2×2 = 77 原木）→ 全部被拒 + 理由码 `too_large`；</li>
 *   <li>{@code inventory_full}：背包塞满 → **不动世界**直接 `DONE inventory_full`；</li>
 *   <li>{@code goal_timeout}：`maxTicks=40` → `FAILED goal_timeout`，且**不空转**（tick 数有界）；</li>
 *   <li>{@code log_replaced}：Job 选完树后（队列已建）**把队列首格换成圆石** → bot 必须**不挖它**、
 *       放弃该树、改砍下一棵并达成配额。断言是"那一格仍是圆石"——比字符串匹配更硬。</li>
 * </ol>
 *
 * <p>每个用例开始前**重放场景**（`lumber_course_terrain` + `lumber_course_trees`）并复位 bot，
 * 保证用例之间互不影响（含第 4、5 条会真的改动世界）。
 */
public final class LumberFailureCheckTask implements Task {

    /** 单用例 tick 上限（自检自身不得空转）。 */
    private static final int CASE_TICKS = 900;

    private enum Case {
        NO_CANDIDATES, ALL_REJECTED, INVENTORY_FULL, GOAL_TIMEOUT, LOG_REPLACED,
        /** J7 Step 4：**没有斧头** ⇒ 前置检查直接 FAILED `tool_missing`（不拿徒手去撞预算）。 */
        TOOL_MISSING,
        // ⭐ `D-329` ⑤.3（`M4` 先于 `M3`）：下面 5 条是**归因分类器**的纯函数判据 ——
        // 直接喂合成事实给 `LumberJob.deriveTopLevelReason(base, failures, treesDone)`，
        // 不造世界、不跑 Job、不依赖时机（`M4` 要的正是"理由码可机器读、可逐条归因"）。
        /** 全是工具码 ⇒ `tool_missing`（退回用的正例：retrofit 不许把**该归因的**弄丢）。 */
        TAXONOMY_ALL_TOOL,
        /** ⭐ 混合原因（同一棵树既有缺镐又有方块被换）⇒ **不许**报 `tool_missing`；并当场对照旧写法。 */
        TAXONOMY_MIXED,
        /** 全是"爬了但没砍完" ⇒ `climb_incomplete`。 */
        TAXONOMY_CLIMB,
        /** 有失败但**一条逐原木证据都没有** ⇒ 不许甩锅工具（保持总括码）。 */
        TAXONOMY_NO_EVIDENCE,
        /** 已经砍成过树（`treesDone > 0`）⇒ 工具/攀爬本身可用 ⇒ 不许归因、保持原码。 */
        TAXONOMY_SUCCESS_GUARD
    }

    private final BotPlayer bot;
    private final ScopeBuffer scope;
    private final LumberCandidateSource source = new LumberCandidateSource();
    private final List<String> results = new ArrayList<>();

    private int index;
    private Case current;
    private LumberJob job;
    private int caseTicks;
    private String failure = "";
    private boolean terminated;

    // 用例私有状态
    private List<ItemStack> savedInventory;
    private BlockPos replacedPos;
    private boolean replacedArmed;

    public LumberFailureCheckTask(BotPlayer bot, ScopeBuffer scope) {
        this.bot = bot;
        this.scope = scope;
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
    public String taskName() {
        return "LumberFailureCheck";
    }

    @Override
    public Task.Status tick() {
        if (terminated) {
            return Task.Status.DONE;
        }
        if (current == null) {
            if (index >= Case.values().length) {
                return finish();
            }
            current = Case.values()[index];
            prepare();
            return Task.Status.RUNNING;
        }

        if (job != null) {
            if (++caseTicks > CASE_TICKS) {
                record(current, false, "case_timeout ticks=" + caseTicks);
                endCase();
                return Task.Status.RUNNING;
            }
            Task.Status status = job.tick();
            // 用例 5：**必须等第一次 tick 之后**才注入——那时 SELECT 才完成，
            // `job.target()` 才是"队列首格"；在此之前它走 fallback 返回 `spec.center()`（=起点），
            // 曾因此把 bot 自己脚下那格换成圆石（2026-09-10 首测 FAIL 的原因）。
            if (current == Case.LOG_REPLACED && !replacedArmed) {
                armReplacedLog();
            }
            if (status == Task.Status.RUNNING) {
                return Task.Status.RUNNING;
            }
            assertCase(status);
            endCase();
            return Task.Status.RUNNING;
        }
        return Task.Status.RUNNING;
    }

    // ==================== 用例装配 ====================

    private void prepare() {
        if (isTaxonomy(current)) {
            // ⭐ 纯函数判据：**不碰世界、不发料、不传送、不建 Job**（判据只喂合成事实）
            caseTicks = 0;
            runTaxonomyCase(current);
            endCase();          // 记完即翻页（`tick()` 下一拍会取下一个用例）
            return;
        }
        ServerLevel level = bot.serverLevel();
        var server = level.getServer();
        var commandSource = server.createCommandSourceStack().withSuppressedOutput();
        if (current != Case.NO_CANDIDATES && current != Case.ALL_REJECTED) {
            // 这两条只做决策（无候选/全被拒），不需要世界；其余用例重放场景保证独立
            server.getCommands().performPrefixedCommand(commandSource,
                    "function alice_test:lumber_course_terrain");
            server.getCommands().performPrefixedCommand(commandSource,
                    "function alice_test:lumber_course_trees");
        }
        bot.teleportTo(level, LumberCourseAnchor.START_FOOT.getX() + 0.5D,
                LumberCourseAnchor.START_FOOT.getY(), LumberCourseAnchor.START_FOOT.getZ() + 0.5D,
                Set.of(), bot.getYRot(), bot.getXRot());
        bot.setDeltaMovement(Vec3.ZERO);
        bot.controller().stopMovement();
        // D-122 附注：**任务自己发工具**（生产 MineTask 自 D-119 起不再兜底发工具）。
        // 此前只有物品入口（`LumberFailureCheckItem`）发料，任何直接跑本任务的调用者
        // （例如串联回归电池）都会徒手砍树 —— 实测 61 tick/根（有斧 6~8 tick/根），
        // 令 `LOG_REPLACED`（预算 600）超时成 `goal_timeout`。故放到任务里，调用者无关。
        if (current == Case.TOOL_MISSING) {
            // J7 Step 4：本用例故意**清空背包**（无斧）——生产侧应先置检查并如实 tool_missing
            com.dddgn.alice.item.FixtureToolKit.resetInventory(bot);
        } else {
            com.dddgn.alice.item.FixtureToolKit.ensureAxe(bot);
            com.dddgn.alice.item.FixtureToolKit.ensurePickaxe(bot);
        }
        caseTicks = 0;
        replacedArmed = false;
        replacedPos = null;

        GoalSpec spec = switch (current) {
            case NO_CANDIDATES -> GoalSpec.harvestUnits(LumberCourseAnchor.START_FOOT, 2, 1, 300);
            case ALL_REJECTED -> GoalSpec.harvestUnits(new BlockPos(22, 64, 218), 4, 1, 300);
            case INVENTORY_FULL -> GoalSpec.harvestUnits(LumberCourseAnchor.START_FOOT, 16, 1, 400);
            case GOAL_TIMEOUT -> GoalSpec.harvestUnits(LumberCourseAnchor.START_FOOT, 16, 1, 40);
            case LOG_REPLACED -> GoalSpec.harvestUnits(LumberCourseAnchor.START_FOOT, 16, 1, 600);
            case TOOL_MISSING -> GoalSpec.harvestUnits(LumberCourseAnchor.START_FOOT, 16, 1, 300);
            // 纯函数用例在上面 `prepare()` 已 `return` ⇒ 走到这里说明"枚举加了成员却没接线"
            case TAXONOMY_ALL_TOOL, TAXONOMY_MIXED, TAXONOMY_CLIMB, TAXONOMY_NO_EVIDENCE,
                 TAXONOMY_SUCCESS_GUARD ->
                    throw new IllegalStateException("纯函数用例不该走到建 Job 这一步：" + current);
        };
        if (current == Case.INVENTORY_FULL) {
            fillInventory();
        }
        job = new LumberJob(bot, spec, scope, source, new NearestPolicy());
        BotLog.info("[FailCheck] case={} start spec(quota={}, maxTicks={})",
                current, spec.quota(), spec.maxTicks());
    }

    // ==================== 归因分类器（纯函数，`D-329` ⑤.3 / `M4`） ====================

    private static boolean isTaxonomy(Case c) {
        return switch (c) {
            case TAXONOMY_ALL_TOOL, TAXONOMY_MIXED, TAXONOMY_CLIMB, TAXONOMY_NO_EVIDENCE,
                 TAXONOMY_SUCCESS_GUARD -> true;
            default -> false;
        };
    }

    /** 合成一条树的失败事实（坐标是假的：分类器**不看世界**，只看码字段）。 */
    private static LumberJob.TreeFailure tree(int idx, String code, List<String> logCodes) {
        return new LumberJob.TreeFailure(new BlockPos(idx * 16, 64, 0), code, 0, 7, logCodes);
    }

    /**
     * ⭐ **旧写法的原样对照**（`LumberJob` retrofit 之前的那一行）：
     * `attemptFailures.stream().allMatch(f -> f.contains("no_suitable_tool") || f.contains("tool_missing"))`，
     * 其中 `f` 是 `describe()` 拼出来的**串**。夹具把它在**同一输入**上跑一遍 —— 这样"新写法为什么必须存在"
     * 就不是一句主张，而是一个**当场可判**的对照（`TAXONOMY_MIXED` 里旧的会撒谎）。
     */
    private static boolean legacySubstringRule(List<LumberJob.TreeFailure> failures) {
        return failures.stream().allMatch(f -> f.describe().contains("no_suitable_tool")
                || f.describe().contains("tool_missing"));
    }

    private void runTaxonomyCase(Case c) {
        switch (c) {
            case TAXONOMY_ALL_TOOL -> {
                List<LumberJob.TreeFailure> failures = List.of(
                        tree(1, "partial_tree", List.of("no_suitable_tool")),
                        tree(2, "partial_tree", List.of("tool_missing")));
                String got = LumberJob.deriveTopLevelReason("partial_quota", failures, 0);
                record(c, "tool_missing".equals(got), "全部逐原木码都是工具类 ⇒ " + got
                        + "（期望 tool_missing；旧写法=" + legacySubstringRule(failures) + " 也必须为 true）");
            }
            case TAXONOMY_MIXED -> {
                // ⭐ 混合原因：同一棵树里既有"缺镐"又有"那格被换成别的方块"
                List<LumberJob.TreeFailure> failures = List.of(
                        tree(1, "partial_tree", List.of("no_suitable_tool", "log_replaced")));
                String got = LumberJob.deriveTopLevelReason("partial_quota", failures, 0);
                boolean legacy = legacySubstringRule(failures);
                record(c, "partial_quota".equals(got) && legacy,
                        "混合原因 ⇒ 新写法=" + got + "（期望 partial_quota，不甩锅工具）· "
                                + "旧子串写法=" + (legacy ? "误报 tool_missing（撒谎）" : "?!") + " ⇒ 对照成立");
            }
            case TAXONOMY_CLIMB -> {
                List<LumberJob.TreeFailure> failures = List.of(
                        tree(1, "climb_incomplete", List.of()),
                        tree(2, "climb_incomplete", List.of()));
                String got = LumberJob.deriveTopLevelReason("partial_quota", failures, 0);
                record(c, "climb_incomplete".equals(got), "全是爬了没砍完 ⇒ " + got + "（期望 climb_incomplete）");
            }
            case TAXONOMY_NO_EVIDENCE -> {
                List<LumberJob.TreeFailure> failures = List.of(
                        tree(1, "partial_tree", List.of()),
                        tree(2, "partial_tree", List.of()));
                String got = LumberJob.deriveTopLevelReason("partial_quota", failures, 0);
                record(c, "partial_quota".equals(got), "有失败但零逐原木证据 ⇒ " + got
                        + "（期望保持 partial_quota：没有证据不许甩锅工具）");
            }
            case TAXONOMY_SUCCESS_GUARD -> {
                // 已经砍成过树 ⇒ 工具与攀爬都被证明可用 ⇒ 不许归因（这条是"别把成功也归因成缺镐"的守卫）
                List<LumberJob.TreeFailure> failures = List.of(
                        tree(1, "partial_tree", List.of("no_suitable_tool")));
                String kept = LumberJob.deriveTopLevelReason("partial_quota", failures, 1);
                String other = LumberJob.deriveTopLevelReason("goal_timeout", failures, 0);
                record(c, "partial_quota".equals(kept) && "goal_timeout".equals(other),
                        "treesDone>0 ⇒ 保持 " + kept + "（期望 partial_quota）· 非总括码 ⇒ 保持 "
                                + other + "（期望 goal_timeout，超时不许被理由盖掉）");
            }
            default -> throw new IllegalStateException("不是纯函数用例：" + c);
        }
    }

    /** 把背包塞满圆石（保存原状以便恢复）——用于 `inventory_full`。 */
    private void fillInventory() {
        var inventory = bot.getInventory();
        savedInventory = new ArrayList<>(inventory.getContainerSize());
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            savedInventory.add(inventory.getItem(slot).copy());
            inventory.setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
        }
    }

    private void restoreInventory() {
        if (savedInventory == null) {
            return;
        }
        var inventory = bot.getInventory();
        for (int slot = 0; slot < savedInventory.size(); slot++) {
            inventory.setItem(slot, savedInventory.get(slot));
        }
        savedInventory = null;
    }

    /**
     * 用例 5：把 Job 已排队的首格换成圆石（模拟"砍到一半被玩家改掉"）。
     *
     * <p>**注入前置校验**：只有该格现在**确实是原木**才注入——否则说明 `target()` 还不是队列格
     * （例如仍处于 SELECT 的 fallback），此时注入会误伤无关方块（首测就误伤了 bot 自己脚下那格）。
     */
    private void armReplacedLog() {
        BlockPos planned = job.target() == null ? null : job.target().blockPos();
        replacedArmed = true;
        if (planned == null) {
            record(Case.LOG_REPLACED, false, "注入失败：Job 尚未给出队列格");
            return;
        }
        if (!LumberJob.isStillLog(bot.serverLevel(), planned)) {
            record(Case.LOG_REPLACED, false,
                    "注入失败：待注入格 " + planned.toShortString() + " 不是原木（时机过早）");
            return;
        }
        replacedPos = planned.immutable();
        bot.serverLevel().setBlock(replacedPos, Blocks.COBBLESTONE.defaultBlockState(), 3);
        BotLog.info("[FailCheck] log_replaced 注入：队列首格 {} 已换成圆石（等待 bot 复检）",
                replacedPos.toShortString());
    }

    // ==================== 断言 ====================

    private void assertCase(Task.Status status) {
        String terminal = job.terminalReason();
        switch (current) {
            case NO_CANDIDATES -> record(current,
                    status == Task.Status.FAILED && "no_reachable_candidate".equals(terminal),
                    "status=" + status + " reason=" + terminal);
            case ALL_REJECTED -> record(current,
                    status == Task.Status.FAILED && "no_reachable_candidate".equals(terminal)
                            && job.failureReason().contains("trunk_too_tall"),
                    "status=" + status + " reason=" + terminal + " failure=" + job.failureReason());
            case INVENTORY_FULL -> record(current,
                    status == Task.Status.DONE && "inventory_full".equals(terminal),
                    "status=" + status + " reason=" + terminal);
            case GOAL_TIMEOUT -> record(current,
                    status == Task.Status.FAILED && "goal_timeout".equals(terminal)
                            && caseTicks <= 120,
                    "status=" + status + " reason=" + terminal + " ticks=" + caseTicks
                            + "（不空转要求 ≤120）");
            case TOOL_MISSING -> record(current,
                    status == Task.Status.FAILED && "tool_missing".equals(terminal),
                    "status=" + status + " reason=" + terminal
                            + "（前置检查：快捷栏无斧 ⇒ 不去徒手撞预算）"
                            // M4b（D-234）：本夹具六个用例**没有一个**以"子任务结束且失败"收场
                            // （无候选/全被拒 = 没建子任务；背包满/换块 = DONE；超时 = 子任务还在跑；
                            //  缺斧 = 前置检查在建子任务之前就拒）⇒ 这里**不断言**树里的 lastFailure，
                            // 免得把合法行为判成失败。端到端覆盖缺口已登记（台账 §5.7 M4b 尾注）。
                            + " tree=" + job.subTasks().stream()
                                    .map(com.dddgn.alice.task.TaskNode::describe).toList());
            case LOG_REPLACED -> {
                BlockState now = replacedPos == null
                        ? null : bot.serverLevel().getBlockState(replacedPos);
                boolean untouched = now != null && now.is(Blocks.COBBLESTONE);
                record(current,
                        replacedPos != null && untouched
                                && status == Task.Status.DONE && "quota_met".equals(terminal),
                        "status=" + status + " reason=" + terminal
                                + " replacedPos=" + (replacedPos == null ? "-" : replacedPos.toShortString())
                                + " 仍为圆石=" + untouched + "（必须为 true：bot 不得挖非原木）");
            }
        }
    }

    private void endCase() {
        restoreInventory();
        job = null;
        current = null;
        index++;
    }

    private void record(Case whose, boolean pass, String detail) {
        results.add(whose.name().toLowerCase() + "=" + (pass ? "PASS" : "FAIL") + "(" + detail + ")");
        if (!pass) {
            BotLog.warn("[FailCheck] {} FAIL {}", whose, detail);
        } else {
            BotLog.info("[FailCheck] {} PASS {}", whose, detail);
        }
    }

    private Task.Status finish() {
        terminated = true;
        boolean pass = results.size() == Case.values().length
                && results.stream().allMatch(r -> r.contains("=PASS"));
        if (!pass) {
            failure = "LUMBER_FAILURE_CHECK_FAILED " + String.join(" ", results);
        }
        BotLog.info("[FailCheck] SUMMARY {} → {}", String.join(" ", results), pass ? "PASS" : "FAIL");
        return pass ? Task.Status.DONE : Task.Status.FAILED;
    }
}
