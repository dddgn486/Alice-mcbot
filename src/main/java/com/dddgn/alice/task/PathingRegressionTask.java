package com.dddgn.alice.task;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.pathing.MovementHelper;
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
        PLAN_SAFE_ROUTE,
        /**
         * 只规划：**水里的步子必须按水速计价**（D-247 切片 A）。
         *
         * <p>判据**从计划自身推导期望值**（不手抄阈值）：数出终点格含水的步数与陆地步数，
         * 期望 = 陆地步×1.0 + 水步×{@link CostModel#WATER_TRAVERSE_MULTIPLIER}，实际成本必须 ≥ 期望×0.9。
         * 反向对照（把水位乘数改回 1.0）会让 `cost` 掉到期望的 ~29% ⇒ 本判据精确变红。
         */
        PLAN_WATER_COST
    }

    /**
     * 场景检查项。
     *
     * <p>{@code required}（D-061）：本场景**必须实际执行到**的 Movement 类型——
     * Movement 变多后路线会漂移（实测 `place_course` 尾部由 DESCEND×2 变成 FALL），
     * 覆盖断言保证"场景仍然在测它该测的东西"，否则该场景直接判 FAIL。
     *
     * <p><b>{@code wallTick}/{@code disturbTick} 是"本场景开始执行后的第 N tick"</b>（场景局部基准，
     * 与 {@link PathSessionDiagnosticTask} / `PathingDisturberItem` 的 {@code DISTURB_TICK} 同一语义：
     * 走到一半再扰动）。⚠️ 2026-09-15 实测缺陷：这里原本用的是**任务级** {@code ticks}（从不按场景复位），
     * 而进入第 15/16 个场景时它早已 ≈300 ⇒ `>= 30` 恒真 ⇒ 夹具在**第一个执行 tick**就动手
     * （日志 `wall_placed … tick=346` / `disturbed … tick=422`），两个场景**从来没有测过"中途"**
     * （`place_course+disturb` 实测只走到 `TRAVERSE_INVALID_PRECONDITION` + 起点重规划，
     * 从未触发它声称的 `*_STALE_START` 段内自愈）。
     *
     * <p><b>声明了夹具就必须真的动手</b>：场景结束时 {@code wallTick>0} 而未放下石头、
     * 或 {@code disturbTick>0} 而未传送过 ⇒ 该场景判 FAIL（`FIXTURE_NOT_FIRED`），
     * 不允许"夹具没生效"被 PASS 掩盖。
     */
    /**
     * **水里一格相对陆地的倍数 —— 判据侧的独立来源**（D-247 漂移门禁）。
     *
     * <p>刻意**不引用** {@link com.dddgn.alice.pathing.core.search.CostModel#WATER_TRAVERSE_MULTIPLIER}：
     * 引用同一个常量会让判据变成自指（实测踩到：把常量改成 1.0，期望值跟着变成 5.00 ⇒ 判据照样 PASS）。
     * 这里是**第二次独立标定**（夹具 `water_course` 实测：陆地一格 5~7 tick、水里一格 42~45 tick
     * ⇒ 7.25×，2026-09-16）；两处不一致 ⇒ 本判据红（要改就一起改，属有意识动作）。
     */
    private static final double MEASURED_WATER_MULTIPLIER = 7.25D;

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
            // 非满高支撑（D-105）：箱子 0.875 / 底半砖 0.5 —— 运行期脚位格必须与规划层一致，
            // 否则完成契约 `footCell.equals(toFoot)` 永不成立（旧行为：原地弹跳到段超时）
            execute("chest_step_course", new BlockPos(1, 64, 126), new BlockPos(2, 65, 126), false,
                    MovementType.ASCEND),
            execute("slab_step_course", new BlockPos(4, 64, 145), new BlockPos(8, 64, 145), false,
                    MovementType.TRAVERSE),
            refused("fluid_course", new BlockPos(0, 64, 66), new BlockPos(4, 64, 66), true),
            // 蹚水（切片 A 的判别性判据，2026-09-16）：水沟 x=2..3 **横跨整个场景宽度** ⇒ 绕不过去，
            // 到得了目标就**只能**是走水里。纯通行（`worldMod=false`）⇒ 顺带断言"不靠放方块搭桥"。
            execute("water_course", new BlockPos(0, 64, 66), new BlockPos(5, 64, 66), false,
                    MovementType.TRAVERSE),
            // **深水（切片 B）现状登记（D-248，2026-09-16）**：3 格深水池**纯通行过不去**
            // —— 水面格要成为合法位置需要 `canWalkOn` 的水位例外（Baritone `canWalkOnPosition:432-448`），
            // 那一半**实测在电池上下文里引出未解释的回归**（见 D-248：更便宜的"破墙 + 升到水面格"路线，
            // 其最终段在健康检查时刻读到的支撑是 Air ⇒ `SEGMENT_FUTURE_BLOCKED` ⇒ 逃生 FAIL；单跑不复现）
            // ⇒ **已回退，未落地**。这两条断言的是**今天的事实**：谁把那一半做对，它们会变红提醒改文档。
            refused("deep_pond_course", new BlockPos(0, 64, 66), new BlockPos(7, 64, 66), false),
            refused("deep_pond_course+floor", new BlockPos(3, 61, 66), new BlockPos(7, 64, 66), false),
            // 同一份地形，**只规划**：水里的步子必须按水速计价（D-247）。`+cost` 后缀复用 `water_course_terrain`。
            new SceneCheck("water_course+cost", new BlockPos(0, 64, 66), new BlockPos(5, 64, 66),
                    false, Kind.PLAN_WATER_COST, 0, 0, 0, 0, 0, List.of()),
            safeRoute("lava_course", new BlockPos(0, 64, 66), new BlockPos(4, 64, 66), true),
            refused("fence_course", new BlockPos(0, 64, 48), new BlockPos(0, 64, 44), false),
            new SceneCheck("dip_course", new BlockPos(0, 64, 66), new BlockPos(-1, 64, 63),
                    false, Kind.PLAN_FIRST_TRAVERSE, 0, 0, 0, 0, 0, List.of()),
            // **DIAGONAL 的唯一确定性执行来源**（2026-09-15 补）：`dip_course_terrain` 的标定路线就是
            // `TRAVERSE + DIAGONAL + DIAGONAL + TRAVERSE`（西侧绕路），但上面那行是**只规划**，
            // 不执行 ⇒ 不进 `executedUnion`。在此之前 DIAGONAL 是靠 `place_course+disturb` 的
            // **偶发绕行**凑出来的（夹具时机修好后它就没了 ⇒ 全局覆盖断言如实报
            // `coverage=FAIL([DIAGONAL])`）。复用同一份地形（`+` 后缀取 `dip_course_terrain`）。
            execute("dip_course+run", new BlockPos(0, 64, 66), new BlockPos(-1, 64, 63), false,
                    MovementType.TRAVERSE, MovementType.DIAGONAL),
            // 世界变化 → 任务层重规划（走到一半时在计划前方第 2 格封路，要求至少 1 次 replan）
            new SceneCheck("place_course+wall", new BlockPos(0, 64, 66), new BlockPos(8, 62, 66),
                    true, Kind.EXECUTE_COMPLETE, 30, 0, 0, 0, 1,
                    List.of(MovementType.PLACE_STEP_AND_TRAVERSE)),
            // 位置漂移 → 段内重同步 / 重规划（走到一半平移 1 格 ⇒ 段内漂移 ⇒ *_STALE_START 自愈）
            new SceneCheck("place_course+disturb", new BlockPos(0, 64, 66), new BlockPos(8, 62, 66),
                    true, Kind.EXECUTE_COMPLETE, 0, 30, 0, 1, 0,
                    List.of(MovementType.PLACE_STEP_AND_TRAVERSE)));

    /** 全局覆盖断言（D-061）：这 9 种可执行 Movement 必须在本次回归中至少被执行一次。 */
    private static final List<MovementType> REQUIRED_COVERAGE = List.of(
            MovementType.TRAVERSE, MovementType.DIAGONAL, MovementType.ASCEND, MovementType.DESCEND,
            MovementType.DOWNWARD, MovementType.PILLAR, MovementType.FALL,
            MovementType.BREAK_AND_TRAVERSE, MovementType.BREAK_AND_ENTER,
            MovementType.PLACE_STEP_AND_TRAVERSE);

    /** 任务级安全上限：14 个场景正常约 560 tick。 */
    private static final int MAX_TASK_TICKS = 2400;

    private final BotPlayer bot;
    private final ServerPlayer observer;
    private final Map<String, Boolean> results = new LinkedHashMap<>();
    private final Map<String, String> details = new LinkedHashMap<>();
    private final java.util.Set<MovementType> executedUnion = new java.util.LinkedHashSet<>();
    private int index;
    /** 任务级 tick（只用于总预算）。⚠️ **不要**拿它做场景内时机判断——见 {@link #sceneTicks}。 */
    private int ticks;
    /**
     * **本场景"开始执行"后的 tick 数**（场景局部基准）：`prepare()` 建完地形、runner 建好那一刻起算。
     * 夹具的 {@code wallTick}/{@code disturbTick} 以它为准 —— 否则"第 30 tick"会退化成"立刻"。
     */
    private int sceneTicks;
    private boolean prepared;
    /** 已真正放下封路石头（声明了 wallTick 的场景必须为 true，否则判 FAIL）。 */
    private boolean walled;
    /** 已真正把 bot 平移过（声明了 disturbTick 的场景必须为 true，否则判 FAIL）。 */
    private boolean disturbed;
    /** 扰动找不到合法落点 ⇒ 放弃；**响亮失败**，不静默降级成"没扰动也算过"。 */
    private boolean disturbGaveUp;
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
        sceneTicks++;
        PathRetryRunner.State state = runner.tick();
        if (state == PathRetryRunner.State.RUNNING) {
            return Status.RUNNING;
        }
        var result = runner.result();
        java.util.Set<MovementType> executed = runner.executedTypes();
        executedUnion.addAll(executed);
        java.util.List<MovementType> missing = scene.required().stream()
                .filter(type -> !executed.contains(type)).toList();
        // **声明了夹具就必须真的动手**：否则"夹具没生效"会被路线/覆盖断言掩盖成 PASS
        //（实测 2026-09-15：两个自愈场景的夹具时机参数从未生效，见 SceneCheck 注释）。
        java.util.List<String> fixtureMissing = new java.util.ArrayList<>();
        if (scene.wallTick() > 0 && !walled) {
            fixtureMissing.add("wall");
        }
        if (scene.disturbTick() > 0 && !disturbed) {
            fixtureMissing.add(disturbGaveUp ? "disturb(no_valid_cell)" : "disturb");
        }
        boolean pass = state == PathRetryRunner.State.DONE && runner.replans() >= scene.minReplans()
                && missing.isEmpty() && fixtureMissing.isEmpty();
        record(scene, pass, result.status()
                + (runner.replans() > 0 ? "/replans=" + runner.replans() : "")
                + (scene.minReplans() > 0 ? "/minReplans=" + scene.minReplans() : "")
                + "/route=" + routeOf(executed)
                + (sceneTicks > 0 ? "/sceneTicks=" + sceneTicks : "")
                + (missing.isEmpty() ? "" : "/MISSING=" + missing)
                + FixtureScript.notFired(fixtureMissing));
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
        // **夹具自己清场**（D-153）：场景跑完回收本场景期间我方放置的 TEMP 方块。
        // 2026-09-12 实测：pillar 场景在基岩竖井里留了 cobblestone，事后回收 UNREACHABLE ⇒ 跨会话残留 4 块。
        // 注意**不能**用"重跑场景地形函数"清场（那会把站在区域里的 bot 埋进方块 —— 见 cleanupScene 注释）。
        if (index < SCENES.size()) {
            cleanupScene(SCENES.get(index));
        }
        index++;
        prepared = false;
    }

    /**
     * **夹具清场：只回收我方账本里的 TEMP 方块**（绝不重建地形）。
     *
     * <p>⚠️ 2026-09-12 实测的教训（我自己的 bug）：最初这里用"重跑本场景的 `_terrain` 函数"清场 ——
     * 而 `*_terrain` 函数首行就是 `fill <区域> air` 再回填，**而 bot 正站在该区域里** ⇒ 它被回填埋进方块，
     * 立刻 `hazard=SUFFOCATING`，随后**每一次**启动（回归、自检）都在 1~3 tick 内 `SURVIVAL_INTERRUPTED`，
     * 连 `SurvivalExitTask` 都 `walk_stale` 失败。教训：**夹具清场只动自己放的东西，不动地形**。
     *
     * <p>做法：取当前作用域下本 bot 的 TEMP 记录，逐条核对"世界里的方块还是不是我方放的那个"，
     * 是 ⇒ `setblock … air` + 销账；不是 ⇒ 交给 `dropStale` 按世界事实销账。既不碰地形、也不会埋 bot。
     */
    private void cleanupScene(SceneCheck scene) {
        var server = bot.serverLevel().getServer();
        var level = bot.serverLevel();
        var source = server.createCommandSourceStack().withSuppressedOutput();
        String scope = com.dddgn.alice.ledger.WorldModLedger.currentScope(server, bot.getUUID());
        int removed = 0;
        int foreign = 0;
        for (com.dddgn.alice.ledger.WorldModLedger.Entry entry
                : com.dddgn.alice.ledger.WorldModLedger.pendingTemporary(server, scope)) {
            BlockPos pos = entry.pos();
            String nowId = String.valueOf(net.minecraft.core.registries.BuiltInRegistries.BLOCK
                    .getKey(level.getBlockState(pos).getBlock()));
            if (!nowId.equals(entry.placed())) {
                foreign++;   // 已不是我方方块 ⇒ 交给 dropStale 销账，不去动它
                continue;
            }
            if (pos.equals(bot.blockPosition()) || pos.equals(bot.blockPosition().above())) {
                continue;    // 保险：不拆 bot 所在格/头位格
            }
            server.getCommands().performPrefixedCommand(source,
                    "setblock " + pos.getX() + " " + pos.getY() + " " + pos.getZ() + " minecraft:air");
            com.dddgn.alice.ledger.WorldModLedger.forget(level, pos);
            removed++;
        }
        int stale = com.dddgn.alice.ledger.WorldModLedger.dropStale(level);
        BotLog.info("[Regression] scene={} cleanup=ledger 回收我方临时方块={} 非我方={} 销账={} 剩余={}",
                scene.scene(), removed, foreign, stale,
                com.dddgn.alice.ledger.WorldModLedger.pendingTemporary(server, scope).size());
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
        disturbGaveUp = false;
        // **场景局部基准从这里起算**（每个场景都跑 `prepare()`；只规划的场景不会自增，
        //   但必须归零 —— 否则会带着上一个场景的读数被误读成"本场景时长"）
        sceneTicks = 0;
        BotLog.info("[Regression] scene={} start={} goal={} expect={} worldMod={} wallTick={} disturbTick={}",
                scene.scene(), scene.start().toShortString(), scene.goal().toShortString(),
                scene.kind(), scene.worldModification(), scene.wallTick(), scene.disturbTick());
    }

    private PathRequest request(SceneCheck scene) {
        String botId = bot.getUUID().toString();
        return scene.worldModification()
                ? PathRequest.withWorldModification(botId, scene.start(), scene.goal(), "pathing-regression")
                : PathRequest.of(botId, scene.start(), scene.goal(), "pathing-regression");
    }

    /**
     * 场景夹具：计划前方封路 / 位置漂移（与 `alice:pathing_waller` / `pathing_disturber` 等价）。
     *
     * <p>⚠️ **时机基准是 {@link #sceneTicks}（场景局部），不是任务级 {@code ticks}** —— 用后者会让
     * "第 30 tick"退化成"第一个执行 tick"（实测 `wall_placed … tick=346`），两个自愈场景就等于没测"中途"。
     */
    private void tickFixtures(SceneCheck scene) {
        if (runner == null || runner.session() == null) {
            return;
        }
        if (scene.wallTick() > 0 && !walled && sceneTicks >= scene.wallTick()) {
            // 目标格计算**与物品侧共用**（`FixtureScript`）—— 这两份实现分叉过一次（footCell vs blockPosition）
            FixtureScript.WallPlan plan = FixtureScript.wallPlan(bot,
                    runner.session().projectedFootPath());
            if (plan != null) {
                bot.serverLevel().setBlock(plan.target(),
                        net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), 3);
                walled = true;
                BotLog.info("[Regression] wall_placed scene={} at={} sceneTick={} pathIndex={}/{}",
                        scene.scene(), plan.target().toShortString(), sceneTicks,
                        plan.pathIndex(), plan.pathLength());
            }
        }
        if (scene.disturbTick() > 0 && !disturbed && !disturbGaveUp
                && sceneTicks >= scene.disturbTick()) {
            BlockPos from = MovementHelper.footCell(bot.serverLevel(), bot);
            BlockPos to = from.offset(scene.disturbDx(), 0, scene.disturbDz());
            if (com.dddgn.alice.pathing.MovementHelper.canWalkOn(bot.serverLevel(), to)
                    && com.dddgn.alice.pathing.MovementHelper.canWalkThrough(bot.serverLevel(), to)
                    && com.dddgn.alice.pathing.MovementHelper.canWalkThrough(bot.serverLevel(), to.above())) {
                disturbed = true;
                bot.teleportTo(bot.serverLevel(), to.getX() + 0.5D, to.getY(), to.getZ() + 0.5D,
                        Set.of(), bot.getYRot(), bot.getXRot());
                bot.setDeltaMovement(Vec3.ZERO);
                BotLog.info("[Regression] disturbed scene={} from={} to={} sceneTick={}",
                        scene.scene(), from.toShortString(), to.toShortString(), sceneTicks);
            } else if (sceneTicks >= scene.disturbTick() + 40) {
                // **不静默降级**（旧行为：`disturbed = true` 却不传送 ⇒ 场景退化成普通 place_course
                //   却照样报 PASS）。这里如实记下，并在本场景判据里判 FAIL。
                disturbGaveUp = true;
                BotLog.warn("[Regression] disturb_not_applicable scene={} at={} sceneTick={}"
                                + "（找不到可站立落点 ⇒ 本场景判 FAIL，不掩盖）",
                        scene.scene(), to.toShortString(), sceneTicks);
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
            case PLAN_WATER_COST -> {
                // D-247：水步按水速计价 —— 期望值**从计划自身推导**（陆地步×1.0 + 水步×WATER_TRAVERSE_MULTIPLIER）。
                var level = bot.serverLevel();
                int waterSteps = 0;
                int landSteps = 0;
                for (var move : plan.movements()) {
                    if (com.dddgn.alice.pathing.MovementHelper.isWater(level, move.toFoot())
                            || com.dddgn.alice.pathing.MovementHelper.isWater(level, move.toFoot().above())) {
                        waterSteps++;
                    } else {
                        landSteps++;
                    }
                }
                double expected = landSteps * com.dddgn.alice.pathing.core.search.CostModel.TRAVERSE_COST
                        + waterSteps * com.dddgn.alice.pathing.core.search.CostModel.TRAVERSE_COST
                        * MEASURED_WATER_MULTIPLIER;
                record(scene, plan.reached() && waterSteps > 0 && plan.totalCost() >= expected * 0.9D,
                        shape + "/water_steps=" + waterSteps + "/land_steps=" + landSteps
                                + "/cost=" + String.format("%.2f", plan.totalCost())
                                + "/expected=" + String.format("%.2f", expected));
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
        // 夹具级无头断言（D-105）：运行期脚位格规则（箱子/底半砖/灵魂沙/地毯/整格）。
        // 纯读方块形状 + 固定坐标，不依赖 bot 物理；放在夹具自建区域（z=300，远离所有场景）。
        boolean footCellRule = com.dddgn.alice.pathing.FootCellRuleCheck
                .assertFootCellRule(bot.serverLevel(), new BlockPos(0, 64, 300));
        results.put("foot_cell_rule", footCellRule);
        BotLog.info("[Regression] scene=foot_cell_rule result={} detail=headless/footCell-vs-canWalkOn",
                footCellRule ? "PASS" : "FAIL");

        StringBuilder summary = new StringBuilder();
        boolean allPass = true;
        for (SceneCheck scene : SCENES) {
            Boolean pass = results.get(scene.scene());
            boolean ok = Boolean.TRUE.equals(pass);
            allPass &= ok;
            summary.append(scene.scene()).append('=').append(ok ? "PASS" : "FAIL").append(' ');
        }
        allPass &= footCellRule;
        summary.append("foot_cell_rule=").append(footCellRule ? "PASS" : "FAIL").append(' ');
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
        com.dddgn.alice.item.FixtureToolKit.ensureHotbarTool(bot,
                () -> new ItemStack(Items.STONE_PICKAXE),
                stack -> stack.is(net.minecraft.tags.ItemTags.PICKAXES), "stone_pickaxe");
    }
}
