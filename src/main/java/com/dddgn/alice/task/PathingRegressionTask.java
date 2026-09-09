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

    private record SceneCheck(String scene, BlockPos start, BlockPos goal,
                              boolean worldModification, Kind kind,
                              int wallTick, int disturbTick, int disturbDx, int disturbDz,
                              int minReplans) {
    }

    private static SceneCheck execute(String scene, BlockPos start, BlockPos goal, boolean worldMod) {
        return new SceneCheck(scene, start, goal, worldMod, Kind.EXECUTE_COMPLETE, 0, 0, 0, 0, 0);
    }

    private static SceneCheck refused(String scene, BlockPos start, BlockPos goal, boolean worldMod) {
        return new SceneCheck(scene, start, goal, worldMod, Kind.PLAN_REFUSED, 0, 0, 0, 0, 0);
    }

    /** 允许有路，但路线不得接触岩浆（`lava_course`：新 Movement 打开了合法搭桥绕行）。 */
    private static SceneCheck safeRoute(String scene, BlockPos start, BlockPos goal, boolean worldMod) {
        return new SceneCheck(scene, start, goal, worldMod, Kind.PLAN_SAFE_ROUTE, 0, 0, 0, 0, 0);
    }

    /** 串联回归场景表：一次右键覆盖全部必要复测项（D-054）。 */
    private static final List<SceneCheck> SCENES = List.of(
            execute("pathing_course", new BlockPos(0, 64, 46), new BlockPos(0, 62, 44), false),
            execute("place_course", new BlockPos(0, 64, 66), new BlockPos(8, 62, 66), true),
            execute("break_course", new BlockPos(0, 64, 66), new BlockPos(7, 64, 66), true),
            execute("vertical_course", new BlockPos(0, 64, 45), new BlockPos(0, 63, 45), true),
            execute("pillar_course", new BlockPos(24, 64, 44), new BlockPos(25, 67, 44), true),
            execute("fall_course", new BlockPos(22, 64, 68), new BlockPos(23, 61, 68), true),
            execute("trace_course", new BlockPos(0, 64, 40), new BlockPos(0, 64, 51), false),
            refused("fluid_course", new BlockPos(0, 64, 66), new BlockPos(4, 64, 66), true),
            safeRoute("lava_course", new BlockPos(0, 64, 66), new BlockPos(4, 64, 66), true),
            refused("fence_course", new BlockPos(0, 64, 48), new BlockPos(0, 64, 44), false),
            new SceneCheck("dip_course", new BlockPos(0, 64, 66), new BlockPos(-1, 64, 63),
                    false, Kind.PLAN_FIRST_TRAVERSE, 0, 0, 0, 0, 0),
            // 世界变化 → 任务层重规划（计划前方封路，要求至少 1 次 replan）
            new SceneCheck("place_course+wall", new BlockPos(0, 64, 66), new BlockPos(8, 62, 66),
                    true, Kind.EXECUTE_COMPLETE, 30, 0, 0, 0, 1),
            // 位置漂移 → 段内重同步 / 重规划
            new SceneCheck("place_course+disturb", new BlockPos(0, 64, 66), new BlockPos(8, 62, 66),
                    true, Kind.EXECUTE_COMPLETE, 0, 30, 0, 1, 0));

    /** 任务级安全上限：12 个场景正常约 500 tick。 */
    private static final int MAX_TASK_TICKS = 2400;

    private final BotPlayer bot;
    private final ServerPlayer observer;
    private final Map<String, Boolean> results = new LinkedHashMap<>();
    private final Map<String, String> details = new LinkedHashMap<>();
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
        boolean pass = state == PathRetryRunner.State.DONE && runner.replans() >= scene.minReplans();
        record(scene, pass, result.status()
                + (runner.replans() > 0 ? "/replans=" + runner.replans() : "")
                + (scene.minReplans() > 0 ? "/minReplans=" + scene.minReplans() : ""));
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
                ? PathRequest.withWorldModification(botId, scene.start(), scene.goal())
                : PathRequest.of(botId, scene.start(), scene.goal());
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
    private static void ensureCobblestone(BotPlayer bot, int count) {
        var inventory = bot.getInventory();
        int have = 0;
        for (int slot = 0; slot < 9; slot++) {
            if (inventory.getItem(slot).is(Items.COBBLESTONE)) {
                have += inventory.getItem(slot).getCount();
            }
        }
        if (have >= count) {
            return;
        }
        for (int slot = 0; slot < 9; slot++) {
            if (inventory.getItem(slot).isEmpty()) {
                inventory.setItem(slot, new ItemStack(Items.COBBLESTONE, count - have));
                return;
            }
        }
        inventory.add(new ItemStack(Items.COBBLESTONE, count - have));
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
