package com.dddgn.alice.task;

import com.dddgn.alice.bot.BotManager;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.survival.HazardType;
import com.dddgn.alice.survival.SurvivalSystem;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ⭐ **「浮起来之后自己走上岸」**（电池步 `survival_shore_escape`；用户 2026-09-21 第十二轮裁定）。
 *
 * <h2>它钉住的是什么（一句话）</h2>
 * <b>没有任务的 bot 掉进水里时，除了"浮起来"还必须"走上岸"：先浮（`D-380`）→ 再用**纯通行**
 * 走到 8 格内已有的无液体站位；8 格内没有 ⇒ 如实登记、**不动**（不造站位、不改世界）。</b>
 *
 * <h2>为什么它是单独一条（用户之问的答案，逐条见计划 §6.1）</h2>
 * <ol>
 *   <li>窒息时那套"逃到岸边"（`SurvivalSystem.plannableRefuge` + `SurvivalExitTask`）**已经**接在
 *       无任务档里（`BotManager` 的 `case INTERRUPT`）—— 一般状态够不着它，是因为
 *       `WATER_CONTACT`（只要 `isInWater()`）**不是软危险** ⇒ 空闲在水里判决恒 `IGNORE`；</li>
 *   <li>而浮面档（`D-380`）在判决之前就 `return` ⇒ 浮完眼一出水又回到 `IGNORE`
 *       ⇒ bot 浮在水面不动（= 用户看到的现状）；</li>
 *   <li>顺序**不能**颠倒：完全浸没时规划器一条边都生成不出来 ⇒ `plannableRefuge` 必然
 *       `UNREACHABLE` ⇒ 必须先浮、再走。本夹具的两个前提（`ROUND_TRIP` 的 `plannableRefuge==null`
 *       与几何落点存在）正是这条事实的判据。</li>
 * </ol>
 *
 * <h2>两个案例（判据 + 反证）</h2>
 * <table border="1">
 *   <tr><th>案例</th><th>场景</th><th>期望</th></tr>
 *   <tr><td>{@code SHORE_IN_RANGE}</td><td>小水池（半径 2）× 3 格深，四周是天然干地（岸在 8 格内）</td>
 *       <td>探针（**无任务**）① 先起 `SurvivalFloatTask`（浮）；② **再**起 `SurvivalExitTask`；
 *       ③ 最终**真的离开水**站到干格上；④ 全程**世界零改动**（纯通行 ⇒ 不放不挖）</td></tr>
 *   <tr><td>{@code NO_SHORE_IN_RANGE}（反证：**没有岸就不许造岸**）</td>
 *       <td>大水池（21×21）× 3 格深 ⇒ 17³ 搜索盒里**一个无液体落点都没有**</td>
 *       <td>探针照样浮起来，但**永远不起** `SurvivalExitTask`、**永远不上岸**、**世界零改动**
 *       （用户口径③：没有现成站位 ⇒ 如实失败，不做"自己制造站位"）</td></tr>
 * </table>
 *
 * <h2>怎么跑（与 `survival_idle_drown` 同一手法）</h2>
 * 用**第二个假人**（天然无任务）跑生产路径，夹具只观察并断言读数：
 * `BotManager.currentTaskKind`（区分"浮"与"走上岸"）、`isInWater`、`plannableRefuge`、
 * `nearestSafeRefuge`、以及夹具自己建的那些格的快照（世界零改动）。
 *
 * <h2>场景几何（自断言；`§6.9.1 ①`）</h2>
 * 无头超平坦测试世界地面只有 3~4 层（`y=-61..-63`，`y=-64` 以下放不下方块）⇒ 水池按 `DEPTH=3` 挖，
 * 池底（`ORIGIN.below(DEPTH+1)`）**自己补一块实心**（与 `survival_idle_drown` 同一个坑：
 * 不补的话 bot 会掉进虚空，把"上不了岸"搅成"掉出去了"）。脚下层 `ORIGIN` 的 y 就是天然地面之上那一层
 * ⇒ 池边的天然地面格天生就是**干、可站、无液体**的岸。
 *
 * <h2>副作用边界（§6.9.2）</h2>
 * 不调 `assign*`/`beginTask`（探针的任务由生产维生路径自己起）；收尾：拆除探针假人、把所有改过的格
 * **还原成原状**、观察者 bot 回原位。
 */
public final class SurvivalShoreEscapeCheckTask implements Task {

    /** 案例 A 的水池中心（天然地面之上那一层；`ORIGIN.below(1..3)` 灌水）。 */
    private static final BlockPos ORIGIN = new BlockPos(3000, -60, 4900);
    /** 案例 B 的水池中心（**远离** A：大水池 21×21，两片水必须互不相连）。 */
    private static final BlockPos FAR_ORIGIN = new BlockPos(3000, -60, 4960);

    /** 水池深度（格）。 */
    private static final int DEPTH = 3;
    /**
     * 案例 A 的水池"半径"（`dx,dz ∈ [-R,R]` ⇒ 5×5）。岸在 2 格外 ⇒ 必落在 `REFUGE_RADIUS=8` 内。
     */
    private static final int SMALL_R = 2;
    /**
     * 案例 B 的水池半径：**必须大于** `REFUGE_RADIUS=8` ⇒ 17³ 搜索盒里一个干格都没有。
     * 取 10（21×21）留一格余量（`10 > 8`）。
     */
    private static final int FAR_R = 10;

    private static final String PROBE_NAME = "shore_probe";

    /** 每案例的观察窗口（tick）。浮起来约 15~30 tick，走上岸再几十 tick。 */
    private static final int WATCH_TICKS = 240;
    /** 生成探针之前的静置（等区块/地形就绪）。 */
    private static final int SETTLE_TICKS = 20;
    private static final int BUDGET_TICKS = 1400;

    private final BotPlayer bot;
    private final ServerPlayer observer;
    private final List<String> failures = new ArrayList<>();
    private final List<String> findings = new ArrayList<>();

    /** 夹具**亲手写过**的格（收尾还原 + "世界零改动"的快照集合）。 */
    private final Map<BlockPos, BlockState> built = new LinkedHashMap<>();
    private BlockPos entryFoot;

    private int ticks;
    private int checks;
    private int phase;
    private int settle;
    private int watch;
    private int caseIndex;
    private boolean done;
    private BotPlayer probe;

    // ---- 逐案例观测 ----
    private boolean sawFloatTask;
    private boolean sawExitTask;
    private boolean sawEyeOut;
    private int firstEyeOutTick;
    private int firstExitTick;
    private boolean everDry;
    private int dryTick;
    private boolean worldChanged;
    private Map<BlockPos, BlockState> worldBefore = Map.of();

    public SurvivalShoreEscapeCheckTask(BotPlayer bot, ServerPlayer observer) {
        this.bot = bot;
        this.observer = observer;
    }

    @Override
    public String taskName() {
        return "SurvivalShoreEscapeCheck";
    }

    @Override
    public TaskTarget target() {
        return TaskTarget.block(ORIGIN);
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
            check("整步必须在预算内跑完（" + BUDGET_TICKS + " tick）", false);
            return finish();
        }
        ServerLevel level = bot.serverLevel();
        switch (phase) {
            case 0 -> {
                // 观察者（跑夹具的这只 bot）站在**干地**上（离水池足够远），别把两件事搅在一起
                entryFoot = bot.blockPosition();
                teleport(bot, caseOrigin().offset(SMALL_R + 4, 0, 0));
                forceload(level, true);
                buildPool(level, caseOrigin(), caseIndex == 0 ? SMALL_R : FAR_R);
                worldBefore = snapshot(level);
                BotLog.info("[ShoreEscape] CHECK case={} 水池半径={} 深度={} built={}",
                        caseName(), caseIndex == 0 ? SMALL_R : FAR_R, DEPTH, built.size());
                settle = 0;
                phase = 1;
                return Task.Status.RUNNING;
            }
            case 1 -> {
                if (settle++ < SETTLE_TICKS) {
                    return Task.Status.RUNNING;
                }
                resetObservations();
                probe = BotManager.spawn(level, caseOrigin().below(DEPTH), PROBE_NAME);
                if (probe == null) {
                    check("前提：能生成探针假人（" + PROBE_NAME + "）", false);
                    return finish();
                }
                // 别让决策层（LLM）给探针派活 —— 那会让它**有任务**，本夹具测的东西就没了
                com.dddgn.alice.decision.GoalDirector.suspend(probe, BUDGET_TICKS + 200);
                runPremises(level);
                watch = 0;
                phase = 2;
                return Task.Status.RUNNING;
            }
            case 2 -> {
                watch++;
                if (probe.isRemoved() || probe.getHealth() <= 0.0F) {
                    check("⭐ 探针 bot 在观察窗口内**死亡/被移除**（watch=" + watch + " air="
                            + probe.getAirSupply() + "）⇒ 本案例判据不成立", false);
                    return nextCaseOrFinish();
                }
                String kind = BotManager.currentTaskKind(probe);
                boolean eyeOut = !probe.isEyeInFluid(FluidTags.WATER);
                boolean dry = !probe.isInWater() && probe.onGround();
                if (kind != null && !sawFloatTask && kind.startsWith("SurvivalFloatTask")) {
                    sawFloatTask = true;
                }
                if (kind != null && kind.startsWith("SurvivalExitTask")) {
                    sawExitTask = true;
                    if (firstExitTick == 0) {
                        firstExitTick = watch;
                    }
                }
                if (eyeOut) {
                    sawEyeOut = true;
                    if (firstEyeOutTick == 0) {
                        firstEyeOutTick = watch;
                    }
                }
                if (dry && !everDry) {
                    everDry = true;
                    dryTick = watch;
                }
                if (watch % 20 == 1) {
                    BotLog.info("[ShoreEscape] watch={} case={} kind={} inWater={} eyeOut={} dry={} y={} pos={}",
                            watch, caseName(), kind, probe.isInWater(), eyeOut, dry,
                            String.format(java.util.Locale.ROOT, "%.2f", probe.getY()),
                            probe.blockPosition().toShortString());
                }
                worldChanged |= countChanges(level) > 0;
                if (watch >= WATCH_TICKS) {
                    runJudgement(level);
                    return nextCaseOrFinish();
                }
                return Task.Status.RUNNING;
            }
            default -> {
                return finish();
            }
        }
    }

    // ==================== 前提与判据 ====================

    private void runPremises(ServerLevel level) {
        BlockPos foot = probe.blockPosition().immutable();
        check("前提：探针被生成在**池底**（foot=" + foot.toShortString() + "，应为 "
                        + caseOrigin().below(DEPTH).toShortString() + "）—— 位置在 spawn 当场就是可靠读数",
                foot.equals(caseOrigin().below(DEPTH)));
        check("前提：探针**没有任务**（`hasTask`=false）—— 这正是被测的那条路径",
                !BotManager.hasTask(probe));
        boolean geometricNear = SurvivalSystem.nearestSafeRefuge(
                probe, SurvivalSystem.REFUGE_RADIUS, foot) != null;
        // ⭐ 机制前提（"必须先浮"的**图论事实**）：探针**浸没**时，从它脚下出发到那个落点**没有路径**。
        //
        // ⚠️ **不能用生产的 `plannableRefuge` 来断言这件事**（2026-09-21 红对照实测踩到）：
        // 它内部走 `isPlannable`，而后者用**时间预算**（`PRECHECK_MAX_MILLIS`）且口径是
        // 「`SEARCH_LIMIT` 也算可尝试」⇒ **同一格、同一代码、两次跑给出不同答案**（`true`/`false`）
        // ⇒ 拿它当前提 = 拿到一个**会随机红**的夹具。
        // 这里改用**节点预算 + 慷慨的时间预算**（确定性）直接问同一张图：
        var dryRun = new com.dddgn.alice.pathing.core.search.CorePathPlanner().plan(probe,
                bot.serverLevel(), com.dddgn.alice.pathing.core.search.PathRequest
                        .of(probe.getUUID().toString(), foot, geometricNear
                                        ? SurvivalSystem.nearestSafeRefuge(probe, SurvivalSystem.REFUGE_RADIUS, foot)
                                        : foot.above(),
                                "shore-premise")
                        .withBudget(com.dddgn.alice.pathing.core.search.SearchBudget.of(500, 60_000L)));
        boolean submergedUnreachable = geometricNear
                && dryRun.status() == com.dddgn.alice.pathing.core.search.PlanningStatus.UNREACHABLE;
        // 生产预检的读数**只记录、不断言**（它就是上面那条不稳的东西）
        boolean plannableNow = SurvivalSystem.plannableRefuge(probe, HazardType.WATER_CONTACT, false) != null;
        findings.add("premise:case=" + caseName() + " foot=" + foot.toShortString()
                + " 浸没时到落点=" + dryRun.status() + " geometricRefugeIn8=" + geometricNear
                + " 生产预检(时间预算,只记录)=" + plannableNow);
        BotLog.info("[ShoreEscape] 前提 case={} foot={} 浸没时规划状态={} 8格内几何落点={}"
                        + " 生产预检(不稳,仅记录)={}",
                caseName(), foot.toShortString(), dryRun.status(), geometricNear, plannableNow);
        if (caseIndex == 0) {
            check("前提：⭐ 浸没时从脚下**没有**到干落点的路径（节点预算内必须 `UNREACHABLE`，实测 "
                            + dryRun.status() + "）—— 这就是「必须先浮、再走」的图论事实",
                    submergedUnreachable);
        }
        if (caseIndex == 0) {
            check("前提：⭐ 案例 A 的 8 格内**存在**几何落点（岸）—— 否则这不是「有岸」的分支",
                    geometricNear);
            check("前提：池边的岸格确实**干且可站**（" + shoreCell().toShortString() + "，"
                            + "流体脚/头=" + isFluid(level, shoreCell()) + "）",
                    !isFluid(level, shoreCell()) && canStand(level, shoreCell()));
        } else {
            check("前提：⭐ 案例 B 的 8 格内**没有**任何无液体落点（21×21 全是水）—— 这是「没有岸」的分支",
                    !geometricNear);
        }
    }

    private void runJudgement(ServerLevel level) {
        if (caseIndex == 0) {
            check("⭐ `SHORE_IN_RANGE`：探针**先浮起来**（眼第一次出水在 watch=" + firstEyeOutTick
                            + "，且真的起过 `SurvivalFloatTask`=" + sawFloatTask + "）",
                    sawEyeOut && sawFloatTask);
            check("⭐ `SHORE_IN_RANGE`：浮起来之后**必须起 **`SurvivalExitTask`（走上岸）——"
                            + "这正是「把窒息逃生那套也用在一般状态」的落地点（实际 sawExit=" + sawExitTask
                            + "，firstExitTick=" + firstExitTick + "）", sawExitTask);
            check("⭐ 顺序必须是**先浮、再走**（眼出水 watch=" + firstEyeOutTick + " ≤ 起逃生任务 watch="
                            + firstExitTick + "）",
                    firstEyeOutTick > 0 && firstExitTick >= firstEyeOutTick);
            check("⭐ `SHORE_IN_RANGE`：探针最终**真的离开水**站到干格上（dry=" + everDry
                            + "，watch=" + dryTick + "，pos=" + probe.blockPosition().toShortString() + "）",
                    everDry);
        } else {
            check("⭐ `NO_SHORE_IN_RANGE`：探针照样**浮起来**（眼出水 watch=" + firstEyeOutTick + "）——"
                            + "没有岸也仍然要救它不被淹死", sawEyeOut);
            check("⭐ `NO_SHORE_IN_RANGE`：**永远不许**起 `SurvivalExitTask`（实际 sawExit=" + sawExitTask
                            + "）—— 8 格内没有落点时，「逃到岸边」这条本来就该如实失败（`exit=none`），"
                            + "而不是让 bot 去撞一堵 10 格厚的水墙", !sawExitTask);
            check("⭐ `NO_SHORE_IN_RANGE`：**不许上岸**（dry=" + everDry + "；大水池四周 8 格内全是水）",
                    !everDry);
        }
        check("⭐ 全程**世界零改动**（纯通行 ⇒ 不放方块、不挖方块；实际 changed="
                        + countChanges(level) + "）—— 这就是「不许自己造站位」的可执行判据",
                countChanges(level) == 0);
        BotLog.info("[ShoreEscape] CASE {} sawFloat={} sawExit={} eyeOut@{} exit@{} dry={}@{} changed={}",
                caseName(), sawFloatTask, sawExitTask, firstEyeOutTick, firstExitTick, everDry, dryTick,
                countChanges(level));
    }

    private Task.Status nextCaseOrFinish() {
        if (probe != null && !probe.isRemoved()) {
            BotManager.remove(probe);
            probe = null;
        }
        ServerLevel level = bot.serverLevel();
        restore(level);
        built.clear();
        worldBefore = Map.of();
        if (caseIndex == 0) {
            caseIndex = 1;
            phase = 0;
            return Task.Status.RUNNING;
        }
        return finish();
    }

    // ==================== 场景 ====================

    private BlockPos caseOrigin() {
        return caseIndex == 0 ? ORIGIN : FAR_ORIGIN;
    }

    private String caseName() {
        return caseIndex == 0 ? "SHORE_IN_RANGE" : "NO_SHORE_IN_RANGE";
    }

    /** 案例 A 的岸：水池 +x 方向紧邻的那一列（它就是天然地面格）。 */
    private BlockPos shoreCell() {
        return caseOrigin().east(SMALL_R + 1);
    }

    /**
     * 挖水池并灌水（`caseOrigin().below(1..DEPTH)` 灌水、池底补实心）。
     *
     * <p>⚠️ 池底必须**自己补一块实心**（与 `survival_idle_drown` 同一个坑）：平坦测试世界地面只有
     * 3~4 层 ⇒ 挖穿之后 bot 会掉进虚空，把"上不了岸"搅成"掉出去了"。
     */
    private void buildPool(ServerLevel level, BlockPos origin, int radius) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int d = 1; d <= DEPTH; d++) {
                    set(level, origin.offset(dx, -d, dz), Blocks.WATER);
                }
                set(level, origin.offset(dx, -(DEPTH + 1), dz), Blocks.STONE);
            }
        }
    }

    private void set(ServerLevel level, BlockPos pos, net.minecraft.world.level.block.Block block) {
        built.putIfAbsent(pos, level.getBlockState(pos));
        level.setBlockAndUpdate(pos, block.defaultBlockState());
    }

    private Map<BlockPos, BlockState> snapshot(ServerLevel level) {
        Map<BlockPos, BlockState> snapshot = new LinkedHashMap<>();
        for (BlockPos pos : built.keySet()) {
            snapshot.put(pos, level.getBlockState(pos));
        }
        return snapshot;
    }

    private int countChanges(ServerLevel level) {
        if (worldBefore.isEmpty()) {
            return 0;
        }
        int changed = 0;
        for (Map.Entry<BlockPos, BlockState> entry : worldBefore.entrySet()) {
            if (!level.getBlockState(entry.getKey()).equals(entry.getValue())) {
                changed++;
            }
        }
        return changed;
    }

    private void restore(ServerLevel level) {
        for (Map.Entry<BlockPos, BlockState> entry : built.entrySet()) {
            level.setBlockAndUpdate(entry.getKey(), entry.getValue());
        }
    }

    private boolean isFluid(ServerLevel level, BlockPos pos) {
        return !level.getBlockState(pos).getFluidState().isEmpty()
                || !level.getBlockState(pos.above()).getFluidState().isEmpty();
    }

    private boolean canStand(ServerLevel level, BlockPos pos) {
        return com.dddgn.alice.pathing.MovementHelper.canStandCentered(level, pos);
    }

    private void forceload(ServerLevel level, boolean on) {
        // 覆盖两片水池（z 从 4900-10 到 4960+10）
        for (int cx = (ORIGIN.getX() - FAR_R) >> 4; cx <= (ORIGIN.getX() + FAR_R) >> 4; cx++) {
            for (int cz = (ORIGIN.getZ() - FAR_R) >> 4; cz <= (FAR_ORIGIN.getZ() + FAR_R) >> 4; cz++) {
                level.setChunkForced(cx, cz, on);
            }
        }
    }

    private void resetObservations() {
        sawFloatTask = false;
        sawExitTask = false;
        sawEyeOut = false;
        firstEyeOutTick = 0;
        firstExitTick = 0;
        everDry = false;
        dryTick = 0;
        worldChanged = false;
        worldBefore = snapshot(bot.serverLevel());
    }

    private Task.Status finish() {
        done = true;
        ServerLevel level = bot.serverLevel();
        if (probe != null && !probe.isRemoved()) {
            BotLog.info("[ShoreEscape] 拆除探针假人 {}（y={}）", PROBE_NAME,
                    String.format(java.util.Locale.ROOT, "%.2f", probe.getY()));
            BotManager.remove(probe);
        }
        restore(level);
        forceload(level, false);
        if (entryFoot != null) {
            teleport(bot, entryFoot);
        }
        bot.controller().stopMovement();

        boolean pass = failures.isEmpty();
        BotLog.info("[ShoreEscape] SUMMARY checks={} failures={} 观察={} tick → {}｜发现：{}｜失败项：{}",
                checks, failures.size(), watch, pass ? "PASS" : "FAIL", findings, failures);
        if (observer != null && !observer.isRemoved()) {
            observer.sendSystemMessage(Component.literal("[alice] 「浮起来后自己上岸」取证 "
                    + (pass ? "PASS" : "FAIL") + "（详见日志 [ShoreEscape]）"));
        }
        return pass ? Task.Status.DONE : Task.Status.FAILED;
    }

    private void teleport(BotPlayer target, BlockPos foot) {
        ServerLevel level = target.serverLevel();
        target.teleportTo(level, foot.getX() + 0.5D, foot.getY(), foot.getZ() + 0.5D,
                Set.of(), target.getYRot(), target.getXRot());
        target.setDeltaMovement(Vec3.ZERO);
        target.controller().stopMovement();
    }

    private void check(String what, boolean ok) {
        checks++;
        if (!ok) {
            failures.add(what);
        }
    }
}
