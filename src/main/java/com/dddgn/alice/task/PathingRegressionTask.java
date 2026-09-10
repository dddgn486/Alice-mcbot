package com.dddgn.alice.task;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.pathing.core.MovementType;
import com.dddgn.alice.pathing.core.search.CorePathPlanner;
import com.dddgn.alice.pathing.core.search.PathPlan;
import com.dddgn.alice.pathing.core.search.PathRequest;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 串联回归任务（{@code alice:pathing_regression}）：一次启动按顺序跑完所有寻路场景。
 *
 * <p>每个场景：调用 {@code alice_test:<scene>_terrain} 建地形 → 把 bot 传送到统一起点 →
 * 按场景类型做检查（规划断言 / 规划+执行断言）→ 记录 PASS/FAIL，最后输出 `SUMMARY`。
 *
 * <p>与 {@link PathingBatteryTask} 的分工：电池在**单一场景**内逐项检查 Movement；
 * 本任务跨**多个场景**检查端到端结果（含拒绝行为）。
 */
public final class PathingRegressionTask implements Task {

    private enum Kind {
        /** 规划 + 执行到目标，要求 COMPLETED。 */
        EXECUTE_COMPLETE,
        /** 只规划，要求不是 REACHED（拒绝行为）。 */
        PLAN_REFUSED,
        /** 只规划，要求 REACHED 且首步为 TRAVERSE（路线偏好）。 */
        PLAN_FIRST_TRAVERSE,
        /** 只规划：允许 REACHED / UNREACHABLE，但路线不得把身体放进岩浆或站在岩浆上（D-057）。 */
        PLAN_SAFE_ROUTE
    }

    /**
     * 场景检查项。
     *
     * <p>{@code required}（D-061）：本场景**必须实际执行到**的 Movement 类型——
     * Movement 变多后路线会漂移（实测 `place_course` 尾部由 DESCEND×2 变成 FALL），
     * 覆盖断言保证"场景仍然在测它该测的东西"，否则该场景直接判 FAIL。
     */
    private record SceneCheck(String scene, BlockPos start, BlockPos goal,
                              boolean worldModification, Kind kind,
                              int wallTick, int disturbTick, int disturbDx, int disturbDz,
                              int minReplans, List<MovementType> required) {
    }

    private static SceneCheck execute(String scene, BlockPos start, BlockPos goal, boolean worldMod,
                                      MovementType... required) {
        return new SceneCheck(scene, start, goal, worldMod, Kind.EXECUTE_COMPLETE, 0, 0, 0, 0, 0,
                List.of(required));
    }

    private static SceneCheck refused(String scene, BlockPos start, BlockPos goal, boolean worldMod) {
        return new SceneCheck(scene, start, goal, worldMod, Kind.PLAN_REFUSED, 0, 0, 0, 0, 0, List.of());
    }

    /** 允许有路，但路线不得接触岩浆（`lava_course`：新 Movement 打开了合法搭桥绕行）。 */
    private static SceneCheck safeRoute(String scene, BlockPos start, BlockPos goal, boolean worldMod) {
        return new SceneCheck(scene, start, goal, worldMod, Kind.PLAN_SAFE_ROUTE, 0, 0, 0, 0, 0, List.of());
    }

    /**
     * 串联回归场景表：一次右键覆盖全部必要复测项（D-054；覆盖断言 D-061）。
     *
     * <p>每个执行场景声明它**必须执行到**的 Movement 类型；结束时另有**全局覆盖断言**
     * （9 种可执行 Movement 必须至少被执行一次）。
     */
    private static final List<SceneCheck> SCENES = List.of(
            execute("pathing_course", new BlockPos(0, 64, 46), new BlockPos(0, 62, 44), false,
                    MovementType.DESCEND),
            execute("place_course", new BlockPos(0, 64, 66), new BlockPos(8, 62, 66), true,
                    MovementType.PLACE_STEP_AND_TRAVERSE),
            execute("break_course", new BlockPos(0, 64, 66), new BlockPos(7, 64, 66), true,
                    MovementType.BREAK_AND_TRAVERSE),
            execute("vertical_course", new BlockPos(0, 64, 45), new BlockPos(0, 63, 45), true,
                    MovementType.DOWNWARD),
            execute("pillar_course", new BlockPos(24, 64, 44), new BlockPos(25, 67, 44), true,
                    MovementType.PILLAR),
            execute("fall_course", new BlockPos(22, 64, 68), new BlockPos(23, 61, 68), true,
                    MovementType.FALL),
            execute("break_enter_course", new BlockPos(22, 64, 100), new BlockPos(23, 64, 100), true,
                    MovementType.BREAK_AND_ENTER),
            execute("trace_course", new BlockPos(0, 64, 40), new BlockPos(0, 64, 51), false,
                    MovementType.TRAVERSE),
            refused("fluid_course", new BlockPos(0, 64, 66), new BlockPos(4, 64, 66), true),
            safeRoute("lava_course", new BlockPos(0, 64, 66), new BlockPos(4, 64, 66), true),
            refused("fence_course", new BlockPos(0, 64, 48), new BlockPos(0, 64, 44), false),
            new SceneCheck("dip_course", new BlockPos(0, 64, 66), new BlockPos(-1, 64, 63),
                    false, Kind.PLAN_FIRST_TRAVERSE, 0, 0, 0, 0, 0, List.of()),
            // 世界变化 → 任务层重规划（计划前方封路，要求至少 1 次 replan）
            new SceneCheck("place_course+wall", new BlockPos(0, 64, 66), new BlockPos(8, 62, 66),
                    true, Kind.EXECUTE_COMPLETE, 30, 0, 0, 0, 1,
                    List.of(MovementType.PLACE_STEP_AND_TRAVERSE)),
            // 位置漂移 → 段内重同步 / 重规划
            new SceneCheck("place_course+disturb", new BlockPos(0, 64, 66), new BlockPos(8, 62, 66),
                    true, Kind.EXECUTE_COMPLETE, 0, 30, 0, 1, 0,
                    List.of(MovementType.PLACE_STEP_AND_TRAVERSE)));

    /** 全局覆盖断言（D-061）：这 9 种可执行 Movement 必须在本次回归中至少被执行一次。 */
    private static final List<MovementType> REQUIRED_COVERAGE = List.of(
            MovementType.TRAVERSE, MovementType.DIAGONAL, MovementType.ASCEND, MovementType.DESCEND,
            MovementType.DOWNWARD, MovementType.PILLAR, MovementType.FALL,
            MovementType.BREAK_AND_TRAVERSE, MovementType.BREAK_AND_ENTER,
            MovementType.PLACE_STEP_AND_TRAVERSE);

    /** 任务级安全上限：12 个场景正常约 500 tick。 */
    private static final int MAX_TASK_TICKS = 2400;

    private final BotPlayer bot;
    private final ServerPlayer observer;
    private final Map<String, Boolean> results = new LinkedHashMap<>();
    private final Map<String, String> details = new LinkedHashMap<>();
    private final java.util.Set<MovementType> executedUnion = new java.util.LinkedHashSet<>();
    private int index;
    private int ticks;
    private boolean prepared;
    private boolean walled;
    private boolean disturbed;
    private PathRetryRunner runner;
    private String failure = "";

    public PathingRegressionTask(BotPlayer bot, ServerPlayer observer) {
        this.bot = bot;
        this.observer = observer;
    }

    @Override
    public TaskTarget target() {
        return TaskTarget.block(SCENES.get(0).goal());
    }

    @Override
    public Status tick() {
        if (++ticks > MAX_TASK_TICKS) {
            failure = "REGRESSION_TIMEOUT";
            BotLog.warn("[Regression] task_timeout ticks={} scene={}", ticks, currentScene());
            return finish();
        }
        if (index >= SCENES.size()) {
            return finish();
        }
        SceneCheck scene = SCENES.get(index);
        if (!prepared) {
            prepare(scene);
            prepared = true;
            if (scene.kind() != Kind.EXECUTE_COMPLETE) {
                runPlanCheck(scene);
                advance();
                return index >= SCENES.size() ? finish() : Status.RUNNING;
            }
            runner = new PathRetryRunner(bot, request(scene), PathRetryRunner.DEFAULT_MAX_REPLANS,
                    "regression-" + scene.scene());
            return Status.RUNNING;
        }
        tickFixtures(scene);
        PathRetryRunner.State state = runner.tick();
        if (state == PathRetryRunner.State.RUNNING) {
            return Status.RUNNING;
        }
        var result = runner.result();
        java.util.Set<MovementType> executed = runner.executedTypes();
        executedUnion.addAll(executed);
        java.util.List<MovementType> missing = scene.required().stream()
                .filter(type -> !executed.contains(type)).toList();
        boolean pass = state == PathRetryRunner.State.DONE && runner.replans() >= scene.minReplans()
                && missing.isEmpty();
        record(scene, pass, result.status()
                + (runner.replans() > 0 ? "/replans=" + runner.replans() : "")
                + (scene.minReplans() > 0 ? "/minReplans=" + scene.minReplans() : "")
                + "/route=" + routeOf(executed)
                + (missing.isEmpty() ? "" : "/MISSING=" + missing));
        runner = null;
        advance();
        return index >= SCENES.size() ? finish() : Status.RUNNING;
    }

    @Override
    public String failureReason() {
        return failure;
    }

    private String currentScene() {
        return index < SCENES.size() ? SCENES.get(index).scene() : "-";
    }

    private void advance() {
        index++;
        prepared = false;
    }

    /** 建地形 + 传送 + 装备（放置/破坏场景需要圆石与石镐）。 */
    private void prepare(SceneCheck scene) {
        var server = bot.serverLevel().getServer();
        var source = server.createCommandSourceStack().withSuppressedOutput();
        // 场景标签允许带后缀（如 place_course+wall），地形函数取 '+' 之前的部分
        String terrain = scene.scene().split("\\+")[0];
        server.getCommands().performPrefixedCommand(source,
                "function alice_test:" + terrain + "_terrain");
        bot.teleportTo(bot.serverLevel(), scene.start().getX() + 0.5D, scene.start().getY(),
                scene.start().getZ() + 0.5D, Set.of(), bot.getYRot(), bot.getXRot());
        bot.setDeltaMovement(Vec3.ZERO);
        bot.controller().stopMovement();
        ensureCobblestone(bot, 8);
        ensureStonePickaxe(bot);
        walled = false;
        disturbed = false;
        BotLog.info("[Regression] scene={} start={} goal={} expect={} worldMod={}",
                scene.scene(), scene.start().toShortString(), scene.goal().toShortString(),
                scene.kind(), scene.worldModification());
    }

    private PathRequest request(SceneCheck scene) {
        String botId = bot.getUUID().toString();
        return scene.worldModification()
                ? PathRequest.withWorldModification(botId, scene.start(), scene.goal(), "pathing-regression")
                : PathRequest.of(botId, scene.start(), scene.goal(), "pathing-regression");
    }

    /** 场景夹具：计划前方封路 / 位置漂移（与 alice:pathing_waller / pathing_disturber 等价）。 */
    private void tickFixtures(SceneCheck scene) {
        if (runner == null || runner.session() == null) {
            return;
        }
        if (scene.wallTick() > 0 && !walled && ticks >= scene.wallTick()) {
            List<BlockPos> path = runner.session().projectedFootPath();
            int position = path.indexOf(bot.blockPosition());
            int target = position >= 0 ? position + 2 : -1;
            if (target > 0 && target < path.size()) {
                BlockPos wall = path.get(target);
                if (bot.serverLevel().getBlockState(wall).isAir()) {
                    bot.serverLevel().setBlock(wall,
                            net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), 3);
                    walled = true;
                    BotLog.info("[Regression] wall_placed scene={} at={} tick={}",
                            scene.scene(), wall.toShortString(), ticks);
                }
            }
        }
        if (scene.disturbTick() > 0 && !disturbed && ticks >= scene.disturbTick()) {
            BlockPos from = bot.blockPosition();
            BlockPos to = from.offset(scene.disturbDx(), 0, scene.disturbDz());
            if (com.dddgn.alice.pathing.MovementHelper.canWalkOn(bot.serverLevel(), to)
                    && com.dddgn.alice.pathing.MovementHelper.canWalkThrough(bot.serverLevel(), to)
                    && com.dddgn.alice.pathing.MovementHelper.canWalkThrough(bot.serverLevel(), to.above())) {
                disturbed = true;
                bot.teleportTo(bot.serverLevel(), to.getX() + 0.5D, to.getY(), to.getZ() + 0.5D,
                        Set.of(), bot.getYRot(), bot.getXRot());
                bot.setDeltaMovement(Vec3.ZERO);
                BotLog.info("[Regression] disturbed scene={} from={} to={} tick={}",
                        scene.scene(), from.toShortString(), to.toShortString(), ticks);
            } else if (ticks >= scene.disturbTick() + 40) {
                disturbed = true;
            }
        }
    }

    private void runPlanCheck(SceneCheck scene) {
        PathRequest request = request(scene);
        PathPlan plan = new CorePathPlanner().plan(bot, bot.serverLevel(), request);
        String first = plan.movements().isEmpty()
                ? "-" : plan.movements().get(0).movementType().name();
        String shape = plan.status().name() + "/first=" + first + "/movements=" + plan.movements().size();
        switch (scene.kind()) {
            case PLAN_REFUSED -> record(scene, !plan.reached(), shape);
            case PLAN_FIRST_TRAVERSE -> record(scene,
                    plan.reached() && MovementType.TRAVERSE.name().equals(first), shape);
            case PLAN_SAFE_ROUTE -> {
                java.util.List<BlockPos> contacts = com.dddgn.alice.pathing.core.search.PlanRouteSafety
                        .lavaContacts(plan, bot.serverLevel());
                record(scene, contacts.isEmpty(),
                        shape + "/lava_contacts=" + contacts.size()
                                + (contacts.isEmpty() ? "" : "/at=" + contacts.get(0).toShortString()));
            }
            default -> record(scene, false, "KIND_MISMATCH");
        }
    }

    /** 已执行类型集合的稳定字符串（按 REQUIRED_COVERAGE 顺序，便于日志对比）。 */
    private static String routeOf(java.util.Set<MovementType> executed) {
        if (executed.isEmpty()) {
            return "-";
        }
        return REQUIRED_COVERAGE.stream().filter(executed::contains)
                .map(Enum::name).reduce((a, b) -> a + "," + b).orElse("-");
    }

    private void record(SceneCheck scene, boolean pass, String detail) {
        results.put(scene.scene(), pass);
        details.put(scene.scene(), detail);
        BotLog.info("[Regression] scene={} result={} detail={}",
                scene.scene(), pass ? "PASS" : "FAIL", detail);
    }

    private Status finish() {
        StringBuilder summary = new StringBuilder();
        boolean allPass = true;
        for (SceneCheck scene : SCENES) {
            Boolean pass = results.get(scene.scene());
            boolean ok = Boolean.TRUE.equals(pass);
            allPass &= ok;
            summary.append(scene.scene()).append('=').append(ok ? "PASS" : "FAIL").append(' ');
        }
        java.util.List<MovementType> coverageMissing = REQUIRED_COVERAGE.stream()
                .filter(type -> !executedUnion.contains(type)).toList();
        boolean coveragePass = coverageMissing.isEmpty();
        allPass &= coveragePass;
        summary.append("coverage=").append(coveragePass ? "PASS" : "FAIL");
        if (!coveragePass) {
            summary.append('(').append(coverageMissing).append(')');
        }
        summary.append(" executed=").append(routeOf(executedUnion));
        String line = summary.toString().trim();
        BotLog.info("[Regression] SUMMARY {}", line);
        if (observer != null) {
            observer.sendSystemMessage(Component.literal("[alice] 串联回归 " + line)
                    .withStyle(allPass ? net.minecraft.ChatFormatting.GREEN
                            : net.minecraft.ChatFormatting.RED));
        }
        if (!allPass) {
            failure = "REGRESSION_FAILED " + line;
            return Status.FAILED;
        }
        return Status.DONE;
    }

    /** 确保快捷栏里有圆石（放置类 Movement 只从快捷栏取一次性方块）。 */
    /**
     * 保证快捷栏里有 {@code count} 个圆石（**一次性方块**：`PILLAR` / `PLACE_STEP_AND_TRAVERSE` /
     * `FALL` 的生成都要求能拿到它）。
     *
     * <p>**2026-09-11 修正（回归大面积失败）**：原实现只填空格，快捷栏满时退化到
     * `inventory.add(...)` → 落进主背包 → `BlockInteraction.findPlaceableSlot`（**只扫快捷栏**）
     * 找不到 → 三个需要放置的场景搜索直接 `UNREACHABLE`、覆盖率断言缺三种 Movement。
     * 现改用 {@code FixtureToolKit.ensureHotbarStack}（与 D-089 斧子问题**同一病灶**，已收敛一处）。
     */
    private static void ensureCobblestone(BotPlayer bot, int count) {
        com.dddgn.alice.item.FixtureToolKit.ensureHotbarStack(bot,
                () -> new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.COBBLESTONE),
                stack -> stack.is(net.minecraft.world.item.Items.COBBLESTONE),
                count, "cobblestone");
    }

    private static void ensureStonePickaxe(BotPlayer bot) {
        var inventory = bot.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (inventory.getItem(slot).is(Items.STONE_PICKAXE)) {
                return;
            }
        }
        inventory.add(new ItemStack(Items.STONE_PICKAXE));
    }
}
