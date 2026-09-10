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
        NO_CANDIDATES, ALL_REJECTED, INVENTORY_FULL, GOAL_TIMEOUT, LOG_REPLACED
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

        // 用例 5：等 Job 选完树（队列已建）后，把队列首格换成圆石
        if (current == Case.LOG_REPLACED && !replacedArmed && job != null) {
            armReplacedLog();
        }

        if (job != null) {
            if (++caseTicks > CASE_TICKS) {
                record(current, false, "case_timeout ticks=" + caseTicks);
                endCase();
                return Task.Status.RUNNING;
            }
            Task.Status status = job.tick();
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
        caseTicks = 0;
        replacedArmed = false;
        replacedPos = null;

        GoalSpec spec = switch (current) {
            case NO_CANDIDATES -> GoalSpec.harvestUnits(LumberCourseAnchor.START_FOOT, 2, 1, 300);
            case ALL_REJECTED -> GoalSpec.harvestUnits(new BlockPos(22, 64, 218), 4, 1, 300);
            case INVENTORY_FULL -> GoalSpec.harvestUnits(LumberCourseAnchor.START_FOOT, 16, 1, 400);
            case GOAL_TIMEOUT -> GoalSpec.harvestUnits(LumberCourseAnchor.START_FOOT, 16, 1, 40);
            case LOG_REPLACED -> GoalSpec.harvestUnits(LumberCourseAnchor.START_FOOT, 16, 1, 600);
        };
        if (current == Case.INVENTORY_FULL) {
            fillInventory();
        }
        job = new LumberJob(bot, spec, scope, source, new NearestPolicy());
        BotLog.info("[FailCheck] case={} start spec(quota={}, maxTicks={})",
                current, spec.quota(), spec.maxTicks());
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

    /** 用例 5：把 Job 已排队的首格换成圆石（模拟"砍到一半被玩家改掉"）。 */
    private void armReplacedLog() {
        BlockPos planned = job.target() == null ? null : job.target().blockPos();
        replacedArmed = true;
        if (planned == null) {
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
                            && job.failureReason().contains("too_large"),
                    "status=" + status + " reason=" + terminal + " failure=" + job.failureReason());
            case INVENTORY_FULL -> record(current,
                    status == Task.Status.DONE && "inventory_full".equals(terminal),
                    "status=" + status + " reason=" + terminal);
            case GOAL_TIMEOUT -> record(current,
                    status == Task.Status.FAILED && "goal_timeout".equals(terminal)
                            && caseTicks <= 120,
                    "status=" + status + " reason=" + terminal + " ticks=" + caseTicks
                            + "（不空转要求 ≤120）");
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
