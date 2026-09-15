package com.dddgn.alice.job.collect;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.job.GoalSpec;
import com.dddgn.alice.job.Job;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.perception.ScopeBuffer;
import com.dddgn.alice.task.CollectDropsTask;
import com.dddgn.alice.task.Task;
import com.dddgn.alice.task.TaskNode;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * **掉落物搜索 + 捡拾**（用户 2026-09-12 要求进任务候选 / D-137）。
 *
 * <p>为什么单独做成 Job 而不是"顺手收集"：捡拾在**别的任务里**是收尾动作（`CollectDropsTask` 被
 * 伐木/挖掘复用），但**玩家或决策层也可能想单独捡一次**（"把地上那堆东西捡回来"）。
 * 之前只有"收尾"用法，没有"主动去捡"的入口 —— 本 Job 补上：
 * <pre>
 * SCAN（读事实：范围内有哪些掉落物）→ 挑最近的一簇 → COLLECT（复用已验收的 CollectDropsTask）
 *   → 回来继续 SCAN，直到没得捡 / 达配额 / 超时
 * </pre>
 *
 * <p>**安全边界（重要）**：默认**只捡"我方的"掉落物**（`ScopeBuffer` 登记过的：我方破坏事件产生、
 * 或夹具显式收养的）。要捡**任意无主掉落物**必须显式 {@code anyDrops=true} —— 因为它可能包括
 * **玩家自己的东西**；这个开关**不进 LLM 动作词汇表**（等 S3 请示通道：捡玩家物品要走 ASK）。
 */
public final class CollectJob implements Job {

    public static final String NAME = "collect";

    private enum Phase { SCAN, COLLECT, DONE }

    private final BotPlayer bot;
    private final GoalSpec spec;
    private final ScopeBuffer scope;
    private final int radius;
    /** 被策略拦下的候选数（报告/日志用：说明"地上有东西但没资格捡"）。 */
    private int blockedCandidates;

    private Phase phase = Phase.SCAN;
    private CollectDropsTask current;
    private int ticks;
    private int collectedItems;
    private int clusters;
    private String terminalReason = "";
    private String failure = "";
    private boolean terminated;

    public CollectJob(BotPlayer bot, GoalSpec spec, ScopeBuffer scope) {
        this.bot = bot;
        this.spec = spec;
        this.scope = scope;
        this.radius = spec.radius();
    }

    @Override
    public String jobName() {
        return NAME;
    }

    @Override
    public com.dddgn.alice.task.TaskTarget target() {
        BlockPos anchor = current != null ? current.target().blockPos() : bot.blockPosition();
        return com.dddgn.alice.task.TaskTarget.block(anchor);
    }

    /** J-4：捡拾 Job 的失败报告要带上"扫到几簇、被闸门拦了几次"。 */
    @Override
    public com.dddgn.alice.bot.TaskFailureReport failureReport() {
        return new com.dddgn.alice.bot.TaskFailureReport(
                failureReason(), phase.name(), progressSummary()
                + " terminal=" + terminalReason
                + (failure.isBlank() ? "" : " failure=" + failure),
                com.dddgn.alice.bot.RecoveryStage.NONE, java.util.List.of());
    }

    public String progressSummary() {
        return "collected=" + collectedItems + " clusters=" + clusters + " blocked=" + blockedCandidates
                + " phase=" + phase;
    }

    @Override
    public List<TaskNode> subTasks() {
        if (current == null) {
            return finishedChildNode == null ? List.of() : List.of(finishedChildNode);
        }
        return List.of(TaskNode.leaf("CollectDropsTask", current.target().describe(), phase.name(),
                ticks, "collected=" + current.collected()));
    }

    @Override
    public String terminalReason() {
        return terminalReason;
    }

    @Override
    public String failureReason() {
        return failure;
    }

    @Override
    public Task.Status tick() {
        if (terminated) {
            return Task.Status.DONE;
        }
        if (++ticks > spec.maxTicks()) {
            return finish(Task.Status.DONE, "goal_timeout");
        }
        if (current != null) {
            return collect();
        }
        return scan();
    }

    // ==================== SCAN ====================

    private Task.Status scan() {
        List<ItemEntity> drops = dropsInRange();
        if (drops.isEmpty()) {
            return finish(Task.Status.DONE, collectedItems > 0 ? "collected" : "none_found");
        }
        // 取最近的一簇（以最近那一件为锚）
        ItemEntity nearest = null;
        double bestDistance = Double.MAX_VALUE;
        for (ItemEntity drop : drops) {
            double distance = drop.distanceToSqr(bot);
            if (distance < bestDistance) {
                bestDistance = distance;
                nearest = drop;
            }
        }
        if (nearest == null) {
            return finish(Task.Status.DONE, collectedItems > 0 ? "collected" : "none_found");
        }
        BlockPos anchor = nearest.blockPosition();
        List<java.util.UUID> ids = new ArrayList<>();
        for (ItemEntity drop : drops) {
            if (drop.blockPosition().distSqr(anchor) <= 9.0D) {   // 同一簇：锚点周围 3×3×3
                ids.add(drop.getUUID());
                if (ids.size() >= 16) {
                    break;
                }
            }
        }
        BotLog.info("[Job] collect pick cluster@{} drops={} nearest={} 被拦下={}",
                anchor.toShortString(), ids.size(),
                String.format(java.util.Locale.ROOT, "%.1f", Math.sqrt(bestDistance)), blockedCandidates);
        current = new CollectDropsTask(bot, anchor, scope, ids, false,
                Math.max(200, spec.maxTicks() - ticks));
        phase = Phase.COLLECT;
        return Task.Status.RUNNING;
    }

    /**
     * 范围内**有资格捡**的掉落物（S3.5 第二步）：扫世界 → **按 {@link DropPolicy} 过滤**。
     *
     * <p>`anyDrops` 布尔**已退役**（D-138）：能不能捡由**归属 + 策略**决定 ——
     * 我方（直接/间接）与**玩家授权区**默认 `AUTO`；`FOREIGN` 默认 `ASK` ⇒ 这里直接过滤掉
     * （要捡就显式派活 + 先授权）。被拦下的数量记进 `blockedCandidates`，便于"地上有东西但没资格捡"的可观测性。
     */
    private List<ItemEntity> dropsInRange() {
        AABB box = bot.getBoundingBox().inflate(radius);
        List<ItemEntity> result = new ArrayList<>();
        int blocked = 0;
        for (ItemEntity item : bot.serverLevel().getEntitiesOfClass(ItemEntity.class, box,
                ItemEntity::isAlive)) {
            var provenance = com.dddgn.alice.decision.DropPolicy.effectiveProvenance(bot, item);
            if (com.dddgn.alice.decision.DropPolicy.mayCollect(bot, provenance)) {
                result.add(item);
            } else {
                blocked++;
            }
        }
        if (blocked != blockedCandidates) {
            BotLog.info("[Job] collect scan 可捡={} 被策略拦下={}（provenance/policy 决定；要捡需授权）",
                    result.size(), blocked);
        }
        blockedCandidates = blocked;
        return result;
    }

    // ==================== COLLECT ====================

    private Task.Status collect() {
        Task.Status status = current.tick();
        if (status == Task.Status.RUNNING) {
            return Task.Status.RUNNING;
        }
        // inner 的 collected() 是它这一趟的净增量 ⇒ 直接累加（不再自造"差值"口径）
        int gained = Math.max(0, current.collected());
        collectedItems += gained;
        clusters++;
        BotLog.info("[Job] collect cluster@{} done status={} collected={}（累计 {}）",
                current.target().blockPos().toShortString(), status, gained, collectedItems);
        boolean innerFailed = status == Task.Status.FAILED;
        String innerReason = current.failureReason();
        finishedChildNode = TaskNode.finished("CollectDropsTask", current.target().describe(),
                phase.name(), ticks, "collected=" + current.collected(), current, status);
        current = null;
        phase = Phase.SCAN;
        if (innerFailed && collectedItems < spec.quota()) {
            // 一簇捡不动不判死：如实记一笔，继续找下一簇（与伐木"换候选"同一哲学）
            BotLog.warn("[Job] collect cluster_failed reason={} ⇒ 继续扫下一簇", innerReason);
        }
        if (collectedItems >= spec.quota()) {
            return finish(Task.Status.DONE, "quota_met");
        }
        return Task.Status.RUNNING;
    }

    /** **刚结束的子任务节点**（M4b）：`current` 置空后仍让树里看得见"哪个子阶段失败"。 */
    private TaskNode finishedChildNode;

    private Task.Status finish(Task.Status status, String reason) {
        terminated = true;
        phase = Phase.DONE;
        bot.controller().stopMovement();
        terminalReason = reason;
        if (status == Task.Status.FAILED) {
            failure = reason;
        }
        BotLog.info("[Job] collect SUMMARY reason={} collected={} clusters={} ticks={} → {}",
                reason, collectedItems, clusters, ticks, status);
        return status;
    }
}
