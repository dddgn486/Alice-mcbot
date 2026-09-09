package com.dddgn.alice.task;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.pathing.core.MovementType;
import com.dddgn.alice.pathing.core.search.CorePathPlanner;
import com.dddgn.alice.pathing.core.search.PathPlan;
import com.dddgn.alice.pathing.core.search.PathRequest;
import com.dddgn.alice.pathing.core.session.PathSession;
import com.dddgn.alice.pathing.core.session.PathSessionStatus;
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
        PLAN_FIRST_TRAVERSE
    }

    private record SceneCheck(String scene, BlockPos start, BlockPos goal,
                              boolean worldModification, Kind kind) {
    }

    private static final List<SceneCheck> SCENES = List.of(
            new SceneCheck("pathing_course", new BlockPos(0, 64, 46), new BlockPos(0, 62, 44),
                    false, Kind.EXECUTE_COMPLETE),
            new SceneCheck("place_course", new BlockPos(0, 64, 66), new BlockPos(8, 62, 66),
                    true, Kind.EXECUTE_COMPLETE),
            new SceneCheck("break_course", new BlockPos(0, 64, 66), new BlockPos(7, 64, 66),
                    true, Kind.EXECUTE_COMPLETE),
            new SceneCheck("fluid_course", new BlockPos(0, 64, 66), new BlockPos(4, 64, 66),
                    true, Kind.PLAN_REFUSED),
            new SceneCheck("lava_course", new BlockPos(0, 64, 66), new BlockPos(4, 64, 66),
                    true, Kind.PLAN_REFUSED),
            new SceneCheck("dip_course", new BlockPos(0, 64, 66), new BlockPos(-1, 64, 63),
                    false, Kind.PLAN_FIRST_TRAVERSE),
            new SceneCheck("fence_course", new BlockPos(0, 64, 48), new BlockPos(0, 64, 44),
                    false, Kind.PLAN_REFUSED));

    /** 任务级安全上限：7 个场景（3 个执行 + 4 个只规划）正常约 400 tick。 */
    private static final int MAX_TASK_TICKS = 2400;

    private final BotPlayer bot;
    private final ServerPlayer observer;
    private final Map<String, Boolean> results = new LinkedHashMap<>();
    private final Map<String, String> details = new LinkedHashMap<>();
    private int index;
    private int ticks;
    private boolean prepared;
    private PathSession session;
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
            PathRequest request = request(scene);
            PathPlan plan = new CorePathPlanner().plan(bot, bot.serverLevel(), request);
            if (!plan.reached()) {
                record(scene, false, "PLAN_" + plan.status());
                advance();
                return index >= SCENES.size() ? finish() : Status.RUNNING;
            }
            session = new PathSession(bot, bot.serverLevel(), plan, request,
                    "regression-" + scene.scene());
            return Status.RUNNING;
        }
        PathSessionStatus status = session.tick();
        if (status == PathSessionStatus.RUNNING) {
            return Status.RUNNING;
        }
        record(scene, status == PathSessionStatus.COMPLETED, status.name());
        session = null;
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
        server.getCommands().performPrefixedCommand(source,
                "function alice_test:" + scene.scene() + "_terrain");
        bot.teleportTo(bot.serverLevel(), scene.start().getX() + 0.5D, scene.start().getY(),
                scene.start().getZ() + 0.5D, Set.of(), bot.getYRot(), bot.getXRot());
        bot.setDeltaMovement(Vec3.ZERO);
        bot.controller().stopMovement();
        ensureCobblestone(bot, 8);
        ensureStonePickaxe(bot);
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

    private void runPlanCheck(SceneCheck scene) {
        PathRequest request = request(scene);
        PathPlan plan = new CorePathPlanner().plan(bot, bot.serverLevel(), request);
        String first = plan.movements().isEmpty()
                ? "-" : plan.movements().get(0).movementType().name();
        switch (scene.kind()) {
            case PLAN_REFUSED -> record(scene, !plan.reached(), plan.status().name());
            case PLAN_FIRST_TRAVERSE -> record(scene,
                    plan.reached() && MovementType.TRAVERSE.name().equals(first),
                    plan.status().name() + "/first=" + first);
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

    private static void ensureCobblestone(BotPlayer bot, int count) {
        var inventory = bot.getInventory();
        int have = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (inventory.getItem(slot).is(Items.COBBLESTONE)) {
                have += inventory.getItem(slot).getCount();
            }
        }
        if (have < count) {
            inventory.add(new ItemStack(Items.COBBLESTONE, count - have));
        }
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
