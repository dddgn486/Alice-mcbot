package com.dddgn.alice.task;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.pathing.MovementHelper;
import com.dddgn.alice.pathing.core.AscendExecutionFactory;
import com.dddgn.alice.pathing.core.CompletionTolerance;
import com.dddgn.alice.pathing.core.DescendExecutionFactory;
import com.dddgn.alice.pathing.core.DiagonalExecutionFactory;
import com.dddgn.alice.pathing.core.IntrinsicReversibility;
import com.dddgn.alice.pathing.core.LiveExecutionContext;
import com.dddgn.alice.pathing.core.MovementCapabilities;
import com.dddgn.alice.pathing.core.MovementExecution;
import com.dddgn.alice.pathing.core.MovementExecutionFactory;
import com.dddgn.alice.pathing.core.MovementSpec;
import com.dddgn.alice.pathing.core.MovementType;
import com.dddgn.alice.pathing.core.PlanningDependency;
import com.dddgn.alice.pathing.core.RecoverabilityLevel;
import com.dddgn.alice.pathing.core.TraverseExecutionFactory;
import com.dddgn.alice.pathing.core.search.CorePathPlanner;
import com.dddgn.alice.pathing.core.search.GoalFoot;
import com.dddgn.alice.pathing.core.search.MovementContext;
import com.dddgn.alice.pathing.core.search.PathPlan;
import com.dddgn.alice.pathing.core.search.PathRequest;
import com.dddgn.alice.pathing.core.search.PlannedMovement;
import com.dddgn.alice.pathing.core.search.PlannedMovementSpecs;
import com.dddgn.alice.pathing.core.search.SearchBudget;
import com.dddgn.alice.pathing.core.search.SurfaceMovementProvider;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * R3 一键自检电池：一次交互覆盖规划与执行全部信息。
 *
 * <ol>
 *   <li><b>规划检查</b>（瞬时）：平地路径、上升路径、预算边界；</li>
 *   <li><b>执行检查</b>（按地形自动挑选）：TRAVERSE / DIAGONAL / ASCEND / DESCEND / 2 段 DESCEND 链。</li>
 * </ol>
 *
 * <p>候选由 {@link SurfaceMovementProvider} 生成，与规划器同源，保证"可规划即可执行"。
 * 地形不支持的项记为 SKIP，不算失败。
 */
public final class PathingBatteryTask implements Task {
    private static final int MAX_TICKS_PER_ITEM = 100;
    private static final List<MovementType> EXEC_ORDER = List.of(
            MovementType.TRAVERSE, MovementType.DIAGONAL, MovementType.ASCEND, MovementType.DESCEND);

    private final BotPlayer bot;
    private final String sessionId = "r3-battery-" + UUID.randomUUID();
    private final Map<String, String> results = new LinkedHashMap<>();
    private final BlockPos hubFoot;

    private boolean initialized;
    private int itemIndex;
    private int itemTicks;
    private String currentLabel;
    private List<PlannedMovement> currentPlan = List.of();
    private int planCursor;
    private MovementExecution execution;
    private String failure = "";

    public PathingBatteryTask(BotPlayer bot, BlockPos hubFoot) {
        this.bot = bot;
        this.hubFoot = hubFoot.immutable();
    }

    @Override
    public TaskTarget target() {
        return TaskTarget.block(bot.blockPosition());
    }

    @Override
    public Status tick() {
        if (!initialized) {
            initialized = true;
            // 先把 bot 锚定到起点（无论它之前在哪儿），保证测试从课程起点开始
            anchorToStart();
            runPlanChecks();
            BotLog.info("[R3 Battery] started session={} bot={} foot={}",
                    sessionId, bot.getName().getString(), bot.blockPosition().toShortString());
        }

        if (execution != null) {
            if (++itemTicks > MAX_TICKS_PER_ITEM) {
                execution.cancel();
                record(currentLabel, "TIMEOUT");
                execution = null;
                planCursor++;
                return advanceOrFinish();
            }
            execution.tick();
            if (execution.phase() == MovementExecution.Phase.SUCCEEDED) {
                recordSegment(currentLabel, planCursor, "PASS");
                execution = null;
                planCursor++;
                itemTicks = 0;
                return advanceOrFinish();
            }
            if (execution.phase() == MovementExecution.Phase.FAILED
                    || execution.phase() == MovementExecution.Phase.CANCELLED) {
                String code = execution.failureCode() == null ? "FAILED" : execution.failureCode();
                record(currentLabel, code);
                execution = null;
                planCursor = currentPlan.size();
                return advanceOrFinish();
            }
            return Status.RUNNING;
        }

        if (planCursor < currentPlan.size()) {
            if (!startSegment(currentPlan.get(planCursor))) {
                planCursor = currentPlan.size();
                return advanceOrFinish();
            }
            return Status.RUNNING;
        }

        if (itemIndex >= EXEC_ORDER.size() + 1) {
            logSummary();
            return Status.DONE;
        }

        if (itemIndex == EXEC_ORDER.size()) {
            // 最后一项：2 段 DESCEND 链
            resetToHub();
            currentLabel = "chain2";
            currentPlan = detectChain();
        } else {
            resetToHub();
            MovementType type = EXEC_ORDER.get(itemIndex);
            currentLabel = type.name().toLowerCase(java.util.Locale.ROOT);
            currentPlan = detect(type);
        }
        planCursor = 0;
        itemTicks = 0;
        if (currentPlan.isEmpty()) {
            record(currentLabel, "SKIP");
            itemIndex++;
            return advanceOrFinish();
        }
        itemIndex++;
        return Status.RUNNING;
    }

    @Override
    public String failureReason() {
        return failure;
    }

    private Status advanceOrFinish() {
        if (itemIndex >= EXEC_ORDER.size() + 1 && planCursor >= currentPlan.size()) {
            logSummary();
            return Status.DONE;
        }
        return Status.RUNNING;
    }

    /** 规划侧检查：平地 / 上升 / 预算边界。 */
    private void runPlanChecks() {
        CorePathPlanner planner = new CorePathPlanner();
        String botId = bot.getUUID().toString();
        BlockPos foot = MovementHelper.footCell(bot.serverLevel(), bot).immutable();

        // 平地 2 格：向南（平台内部无台阶）。注意不要向东——东侧 (1,64,46) 是台阶，
        // 标定后（D-040）规划器会正确地选择"绕行 4 步（≈24 tick）"而不是"上台阶再下来（≈26 tick）"。
        PathPlan flat = planner.planTo(bot, bot.serverLevel(), botId, foot, foot.offset(0, 0, 2), "pathing-battery");
        results.put("plan_flat", flat.status().name() + "(" + flat.movements().size() + ")");

        List<PlannedMovement> upCandidates = detect(MovementType.ASCEND);
        if (upCandidates.isEmpty()) {
            results.put("plan_up", "SKIP");
        } else {
            PathPlan up = planner.planTo(bot, bot.serverLevel(), botId, foot,
                    upCandidates.get(0).toFoot(), "pathing-battery");
            results.put("plan_up", up.status().name() + "(" + up.movements().size() + ")");
        }

        // 预算边界：目标远在平台外（不可达），预算必须**远小于可达分量**才能触发 SEARCH_LIMIT。
        // 标定后启发式一致（D-040），每个节点只展开一次，200 节点已足以穷尽约 195 格平台
        // → 返回 UNREACHABLE（正确但不再验证预算路径），因此收紧到 32 节点。
        PathRequest tight = new PathRequest(botId, foot, new GoalFoot(foot.offset(100, 0, 0)),
                PathRequest.of(botId, foot, foot, "pathing-battery").allowedMovementTypes(),
                SearchBudget.of(32, 200L), "pathing-battery");
        PathPlan budget = planner.plan(bot, bot.serverLevel(), tight);
        results.put("plan_budget", budget.status().name());

        BotLog.info("[R3 Battery] plan_flat={} plan_up={} plan_budget={}",
                results.get("plan_flat"), results.get("plan_up"), results.get("plan_budget"));
    }

    /** 用规划器同源候选检测某一类型的一条可执行 Movement。 */
    private List<PlannedMovement> detect(MovementType type) {
        List<PlannedMovement> candidates = new ArrayList<>();
        MovementContext context = MovementContext.live(bot, bot.serverLevel(),
                PathRequest.of(bot.getUUID().toString(), hubFoot, hubFoot, "pathing-battery"));
        new SurfaceMovementProvider().appendCandidates(context, hubFoot, candidates);
        for (PlannedMovement candidate : candidates) {
            if (candidate.movementType() == type) {
                return List.of(candidate);
            }
        }
        return List.of();
    }

    /** 每项开始前把 bot 放回统一起点，保证各项独立可测（夹具行为，会写日志）。 */
    private void resetToHub() {
        if (MovementHelper.footCell(bot.serverLevel(), bot).equals(hubFoot)) {
            return;
        }
        anchorToStart();
    }

    /** 把 bot 传送到测试起点（夹具行为，允许传送）。 */
    private void anchorToStart() {
        // 夹具重置：允许传送（带头部同步的重载，避免头身不一致）
        bot.teleportTo(bot.serverLevel(), hubFoot.getX() + 0.5D, hubFoot.getY(),
                hubFoot.getZ() + 0.5D, java.util.Set.of(), bot.getYRot(), bot.getXRot());
        bot.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        bot.controller().stopMovement();
        BotLog.info("[R3 Battery] anchor_to_start foot={} actualFoot={} yRot={} yHeadRot={}",
                hubFoot.toShortString(), bot.blockPosition().toShortString(),
                String.format(java.util.Locale.ROOT, "%.2f", bot.getYRot()),
                String.format(java.util.Locale.ROOT, "%.2f", bot.getYHeadRot()));
    }

    /** 检测 2 段连续下降（标准楼梯）。 */
    private List<PlannedMovement> detectChain() {
        List<PlannedMovement> first = detect(MovementType.DESCEND);
        if (first.isEmpty()) {
            return List.of();
        }
        List<PlannedMovement> second = detectFrom(first.get(0).toFoot(), MovementType.DESCEND);
        if (second.isEmpty()) {
            return List.of();
        }
        return List.of(first.get(0), second.get(0));
    }

    private List<PlannedMovement> detectFrom(BlockPos from, MovementType type) {
        List<PlannedMovement> candidates = new ArrayList<>();
        MovementContext context = MovementContext.live(bot, bot.serverLevel(),
                PathRequest.of(bot.getUUID().toString(), from, from, "pathing-battery"));
        new SurfaceMovementProvider().appendCandidates(context, from, candidates);
        for (PlannedMovement candidate : candidates) {
            if (candidate.movementType() == type) {
                return List.of(candidate);
            }
        }
        return List.of();
    }

    private boolean startSegment(PlannedMovement movement) {
        MovementExecutionFactory factory = PlannedMovementSpecs.factoryFor(movement.movementType());
        MovementSpec spec = PlannedMovementSpecs.toSpec(movement,
                List.of("battery_segment", "target_support", "target_body_clear", "target_head_clear"));
        boolean finalSegment = planCursor == currentPlan.size() - 1;
        CompletionTolerance tolerance = finalSegment
                ? CompletionTolerance.EXACT
                : CompletionTolerance.COLUMN;
        LiveExecutionContext context = new LiveExecutionContext(bot, bot.serverLevel(), sessionId,
                0L, 0L, tolerance, "pathing-battery");
        MovementExecutionFactory.ValidationResult validation = factory.validate(spec, context);
        if (!validation.valid()) {
            record(currentLabel, validation.failureCode());
            return false;
        }
        execution = factory.create(spec, context);
        itemTicks = 0;
        BotLog.info("[R3 Battery] item={} seg={}/{} type={} from={} to={} tolerance={}",
                currentLabel, planCursor + 1, currentPlan.size(), movement.movementType(),
                movement.fromFoot().toShortString(), movement.toFoot().toShortString(), tolerance);
        return true;
    }

    private void recordSegment(String label, int cursor, String value) {
        BotLog.info("[R3 Battery] item={} seg={} result={} actualFoot={}",
                label, cursor, value, bot.blockPosition().toShortString());
        if (value.startsWith("PASS")) {
            record(label, "PASS");
        }
    }

    private void record(String label, String value) {
        BotLog.info("[R3 Battery] item={} result={} actualFoot={}", label, value,
                bot.blockPosition().toShortString());
        // 失败必须覆盖 PASS，不能被 putIfAbsent 掩盖（曾出现 chain2 段1 PASS、段2 失败却汇总为 PASS）
        String existing = results.get(label);
        boolean existingIsFailure = existing != null
                && !existing.startsWith("PASS") && !existing.equals("SKIP");
        if (!existingIsFailure) {
            results.put(label, value);
        }
    }

    private void logSummary() {
        StringBuilder builder = new StringBuilder("[R3 Battery] SUMMARY");
        for (Map.Entry<String, String> entry : results.entrySet()) {
            builder.append(' ').append(entry.getKey()).append('=').append(entry.getValue());
        }
        BotLog.info("{} session={}", builder, sessionId);
    }



}
