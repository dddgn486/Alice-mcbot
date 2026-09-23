package com.dddgn.alice.task;

import com.dddgn.alice.action.WriteBudget;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.pathing.core.search.PathRequest;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import java.util.Set;

/**
 * 执行期写入预算自检（D-106）：**预算用满后绝不再改世界，并如实失败**。
 *
 * <p>场景复用 `break_course`（x=3 两格高石墙挡住 (0,64,66) → (7,64,66)），
 * 但把本次任务的破坏上限压到 **1 格**（夹具专用 {@link WriteBudget#setCaps}，不接玩家命令）。
 *
 * <p>断言（不变式，与"计划先破坏还是先放置"无关）：
 * <ol>
 *   <li>破坏次数 ≤ 上限、放置次数 = 0（上限 0）——**没有任何一类写入越界**；</li>
 *   <li>世界事实与计数一致：墙区（x=3, y=64..65, z=62..70）变空气的格子数 ≤ 上限；</li>
 *   <li>预算确实被触发过（`exhausted`）；</li>
 *   <li>bot 没有穿过墙、且这次通行没有被判成功 —— "没路了就如实失败"，不是"想办法继续拆"；</li>
 *   <li>**预算用满后不再规划任何写入**（`refusedPlaces == 0`，D-106 Slice B）——
 *       曾经的行为是"换个写入方式继续试"（破坏被拒后改规划放置绕行），日志里就是 `[WRITE-REFUSED] place`。</li>
 * </ol>
 *
 * <p>输出：`[WriteBudget] CHECK breaks=?/1 exhausted=? wall_broken=? passed_wall=? status=? → PASS|FAIL`。
 *
 * <p>⭐ `Z3`（2026-09-23）追加**"区"那一臂**（{@link #assertZoneArms}）：`write_budget` 原先只判
 * "额度用满就不再改世界"；现在还要判「**区外无格数额度**」（无装订时连做 70 次破坏/放置都不许被拒 ——
 * 70 > `Caps.DEFAULT` 的 64/32，所以这臂对"兜底又变回 64"有鉴别力）与「**显式装订照旧强制**」
 * （`capForEscape(1,1)` ⇒ 第 2 次必被拒）。⚠️ 判据的静态那一半（"默认回退 = 不限"）在门禁
 * `rule_write_budget_zone_and_container_exception` 里 —— **两个半张一起才叫判据**。
 */
public final class WriteBudgetCheckTask implements Task {

    /** 与 `break_course_terrain` 对齐：起点 / 目标 / 墙位置。 */
    public static final BlockPos START_FOOT = new BlockPos(0, 64, 66);
    private static final BlockPos GOAL_FOOT = new BlockPos(7, 64, 66);
    private static final int WALL_X = 3;
    private static final int WALL_Y_MIN = 64;
    private static final int WALL_Y_MAX = 65;
    private static final int WALL_Z_MIN = 62;
    private static final int WALL_Z_MAX = 70;
    /** 夹具把预算压到 1 格（正常任务默认 64）。 */
    private static final int CAP_BREAKS = 1;
    /** 单次自检的 tick 上限。 */
    private static final int RUN_BUDGET_TICKS = 600;
    /** `Z3` 的"区"那一臂：无装订时连做多少次破坏/放置都必须放行（> `Caps.DEFAULT` 的 64/32 才有鉴别力）。 */
    private static final int ZONE_PROBES = 70;

    private enum Phase { SETUP, RUN, ASSERT, ZONE, DONE }

    private final BotPlayer bot;
    private final com.dddgn.alice.perception.ScopeBuffer scope;
    private Phase phase = Phase.SETUP;
    private com.dddgn.alice.task.PathRetryRunner runner;
    private int ticks;
    private String failure = "";
    private String resultStatus = "-";
    /** `Z3` 的"区"那一臂的读数（进 SUMMARY，便于读日志的人核对）。 */
    private String zoneDetail = "-";

    public WriteBudgetCheckTask(BotPlayer bot, com.dddgn.alice.perception.ScopeBuffer scope) {
        this.bot = bot;
        this.scope = scope;
    }

    @Override
    public String taskName() {
        return "WriteBudgetCheck";
    }

    @Override
    public TaskTarget target() {
        return TaskTarget.block(GOAL_FOOT);
    }

    @Override
    public String failureReason() {
        return failure;
    }

    @Override
    public Task.Status tick() {
        return switch (phase) {
            case SETUP -> setup();
            case RUN -> run();
            case ASSERT -> assertResult();
            case ZONE -> assertZoneArms();
            case DONE -> Task.Status.DONE;
        };
    }

    private Task.Status setup() {
        ServerLevel level = bot.serverLevel();
        var server = level.getServer();
        server.getCommands().performPrefixedCommand(
                server.createCommandSourceStack().withSuppressedOutput(),
                "function alice_test:break_course_terrain");
        bot.teleportTo(level, START_FOOT.getX() + 0.5D, START_FOOT.getY(), START_FOOT.getZ() + 0.5D,
                Set.of(), bot.getYRot(), bot.getXRot());
        bot.setDeltaMovement(Vec3.ZERO);
        bot.controller().stopMovement();
        scope.begin(START_FOOT, 24, bot.getUUID());
        com.dddgn.alice.item.FixtureToolKit.ensureHotbarTool(bot,
                () -> new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND_PICKAXE),
                stack -> stack.is(net.minecraft.tags.ItemTags.PICKAXES), "pickaxe");
        // 夹具职责：把本次任务的破坏上限压到 1 格（不暴露为玩家命令）
        WriteBudget.setCaps(WriteBudget.scopeOf(bot), new WriteBudget.Caps(CAP_BREAKS, 0));
        BotLog.info("[WriteBudget] CHECK setup start={} goal={} caps={} {}",
                START_FOOT.toShortString(), GOAL_FOOT.toShortString(), CAP_BREAKS,
                WriteBudget.describe(bot));
        runner = new com.dddgn.alice.task.PathRetryRunner(bot,
                PathRequest.withWorldModification(bot.getUUID().toString(), START_FOOT, GOAL_FOOT,
                        "write-budget-check"),
                com.dddgn.alice.task.PathRetryRunner.DEFAULT_MAX_REPLANS, "writebudget");
        phase = Phase.RUN;
        return Task.Status.RUNNING;
    }

    private Task.Status run() {
        if (++ticks > RUN_BUDGET_TICKS) {
            resultStatus = "TASK_TIMEOUT";
            BotLog.warn("[WriteBudget] CHECK 运行超时（{} tick）→ 断言", ticks);
            runner = null;
            phase = Phase.ASSERT;
            return Task.Status.RUNNING;
        }
        var state = runner.tick();
        if (state == com.dddgn.alice.task.PathRetryRunner.State.RUNNING) {
            return Task.Status.RUNNING;
        }
        var result = runner.result();
        resultStatus = result == null ? state.name() : result.status().name();
        BotLog.info("[WriteBudget] CHECK run_end state={} status={} replans={} feet={}",
                state, resultStatus, runner.replans(),
                com.dddgn.alice.pathing.MovementHelper.footCell(bot.serverLevel(), bot).toShortString());
        runner = null;
        phase = Phase.ASSERT;
        return Task.Status.RUNNING;
    }

    private Task.Status assertResult() {
        ServerLevel level = bot.serverLevel();
        int breaks = WriteBudget.breaks(bot);
        int places = WriteBudget.places(bot);
        int refusedBreaks = WriteBudget.refusedBreaks(bot);
        int refusedPlaces = WriteBudget.refusedPlaces(bot);
        boolean triggered = WriteBudget.breakExhausted(bot) || WriteBudget.placeExhausted(bot);
        // 世界事实复核：墙区里变成空气的格子数（只信世界，不只信计数器）
        int wallBroken = 0;
        for (int y = WALL_Y_MIN; y <= WALL_Y_MAX; y++) {
            for (int z = WALL_Z_MIN; z <= WALL_Z_MAX; z++) {
                if (level.getBlockState(new BlockPos(WALL_X, y, z)).isAir()) {
                    wallBroken++;
                }
            }
        }
        boolean passedWall = com.dddgn.alice.pathing.MovementHelper
                .footCell(level, bot).getX() >= WALL_X + 1;
        // 判据（不变式，不依赖"计划先破坏还是先放置"）：
        // ① 没有任何一类写入越过上限；② 世界事实与计数一致（墙区空气格 ≤ 上限）；
        // ③ 预算确实被触发过；④ 没穿过墙 + 没被当成成功
        boolean noOverrun = breaks <= CAP_BREAKS && places <= 0 && wallBroken <= CAP_BREAKS;
        boolean stopped = !passedWall && !"COMPLETED".equals(resultStatus);
        // D-106 Slice B（计划期剪枝）：预算用满后**搜索不该再规划任何写入**——
        // 曾经的行为是"换个写入方式继续试"（改规划放置绕行），证据就是 refusedPlaces>0
        boolean noDoomedWrites = refusedPlaces == 0;
        boolean pass = noOverrun && stopped && triggered && noDoomedWrites;
        BotLog.info("[WriteBudget] CHECK breaks={}/{} places={}/0 refusedBreaks={} refusedPlaces={}"
                        + " exhausted={} wall_broken={} passed_wall={} status={} → {}",
                breaks, CAP_BREAKS, places, refusedBreaks, refusedPlaces, triggered, wallBroken,
                passedWall, resultStatus, pass ? "PASS" : "FAIL");
        if (!pass) {
            failure = "WRITE_BUDGET_CHECK_FAILED breaks=" + breaks + " places=" + places
                    + " triggered=" + triggered + " refusedPlaces=" + refusedPlaces
                    + " wallBroken=" + wallBroken + " passedWall=" + passedWall
                    + " status=" + resultStatus;
            phase = Phase.DONE;
            return Task.Status.FAILED;
        }
        phase = Phase.ZONE;   // ⭐ `Z3`：预算的不变式成立之后，再判"区外无额度 / 显式装订照旧强制"
        return Task.Status.RUNNING;
    }

    /**
     * ⭐ `Z3`（2026-09-23）：**"区外无格数额度" + "显式装订照旧强制"** 两半的判据。
     *
     * <p><b>前提自证</b>：本臂要在**区外**（无主区域）成立 —— 样本格必须 `ProtectionZones.isWild`；
     * 不是区外就**如实判红**（不许默默换个说法继续，`Z2` 的教训：夹具得自己声明前提）。
     *
     * <p><b>臂①「无装订 ⇒ 不设格数额度」</b>：把生效上限装成 {@link WriteBudget.Caps#UNBOUNDED}
     * （= 默认派生的那个值，见门禁 `rule_write_budget_zone_and_container_exception`）
     * ⇒ 连做 {@link #ZONE_PROBES} 次破坏 + 放置**一次都不许被拒**（`D-398` R4：区外无限制修改，
     * 闸门改为时间预算防空转）。
     *
     * <p><b>臂②「显式装订照旧强制」</b>：{@link WriteBudget#capForEscape}（`D-241` 逃生准备金）
     * ⇒ 第 1 次放行、第 2 次**必须被拒**（`Z3`：保留显式装订）。
     * ⚠️ 额度按**作用域累计**读 ⇒ 这里装的是"**再给 1 次**"（`当前计数 + 1`），
     * 不是绝对值 1（那会在本阶段第一次调用就被拒 —— 判据看着对、前提是假的）。
     * ⚠️ 这条拒必须是**瞬时**语义 —— 它跨边界时用 {@link WriteBudget#EXHAUSTED_CODE}，
     * 由门禁钉"只有一个拼法"；本臂负责"真的会拒"。
     */
    private Task.Status assertZoneArms() {
        ServerLevel level = bot.serverLevel();
        String scope = WriteBudget.scopeOf(bot);
        boolean wild = com.dddgn.alice.protection.ProtectionZones.isWild(level, START_FOOT);
        if (!wild) {
            failure = "ZONE_PREMISE_NOT_WILD start=" + START_FOOT.toShortString()
                    + "（本臂只在区外成立；场景/认领变了就要先修前提）";
            BotLog.warn("[WriteBudget] ZONE premise=FAIL {} ⇒ 本臂不作数", failure);
            zoneDetail = "premise=FAIL";
            phase = Phase.DONE;
            return Task.Status.FAILED;
        }
        // ---- 臂①：无装订（生效上限 = UNBOUNDED）⇒ 一次都不被拒 ----
        WriteBudget.setCaps(scope, WriteBudget.Caps.UNBOUNDED);
        int b0 = WriteBudget.breaks(bot);
        int p0 = WriteBudget.places(bot);
        int rb0 = WriteBudget.refusedBreaks(bot);
        int rp0 = WriteBudget.refusedPlaces(bot);
        for (int i = 0; i < ZONE_PROBES; i++) {
            BlockPos probe = START_FOOT.offset(i - ZONE_PROBES / 2, 0, 0);
            WriteBudget.consumeBreak(bot, level, probe, null);
            WriteBudget.consumePlace(bot, level, probe, null);
        }
        int dBreaks = WriteBudget.breaks(bot) - b0;
        int dPlaces = WriteBudget.places(bot) - p0;
        int dRefusedBreaks = WriteBudget.refusedBreaks(bot) - rb0;
        int dRefusedPlaces = WriteBudget.refusedPlaces(bot) - rp0;
        // 读数也必须同源：剩余额度不能因为 counters 涨了就说"没额度了"（P1-a 的那类副本）
        int remainBreaks = WriteBudget.remainingBreaks(bot);
        boolean armA = dBreaks == ZONE_PROBES && dPlaces == ZONE_PROBES
                && dRefusedBreaks == 0 && dRefusedPlaces == 0 && remainBreaks > ZONE_PROBES;
        // ---- 臂②：显式装订 ⇒ 第 2 次必被拒 ----
        // ⚠️ 额度是**本作用域累计**的（RUN 阶段已经用掉过若干次）⇒ 这里要的是"**再给 1 次**"，
        // 写成绝对值 1 会让**第一次**就被拒（本夹具第一版就这么错过：判据看着对、前提是假的）。
        WriteBudget.capForEscape(scope, WriteBudget.breaks(bot) + 1, WriteBudget.places(bot) + 1);
        int rb1 = WriteBudget.refusedBreaks(bot);
        int rp1 = WriteBudget.refusedPlaces(bot);
        var firstBreak = WriteBudget.consumeBreak(bot, level, START_FOOT, null);
        var secondBreak = WriteBudget.consumeBreak(bot, level, START_FOOT, null);
        var firstPlace = WriteBudget.consumePlace(bot, level, START_FOOT, null);
        var secondPlace = WriteBudget.consumePlace(bot, level, START_FOOT, null);
        boolean armB = firstBreak != WriteBudget.Verdict.REFUSED
                && secondBreak == WriteBudget.Verdict.REFUSED
                && firstPlace != WriteBudget.Verdict.REFUSED
                && secondPlace == WriteBudget.Verdict.REFUSED
                && WriteBudget.refusedBreaks(bot) - rb1 == 1
                && WriteBudget.refusedPlaces(bot) - rp1 == 1;
        zoneDetail = "wild=" + wild + " unbound[breaks=+" + dBreaks + " places=+" + dPlaces
                + " refused=+" + dRefusedBreaks + "/+" + dRefusedPlaces + " remainBreaks=" + remainBreaks
                + "] capForEscape[2ndBreak=" + secondBreak + " 2ndPlace=" + secondPlace + "]";
        BotLog.info("[WriteBudget] ZONE 野外={} 臂①无装订不设额度={} 臂②显式装订照旧强制={}｜{}",
                wild, armA ? "PASS" : "FAIL", armB ? "PASS" : "FAIL", zoneDetail);
        phase = Phase.DONE;
        if (!(armA && armB)) {
            failure = "WRITE_BUDGET_ZONE_FAILED armA(no-binding-unbounded)=" + armA
                    + " armB(explicit-binding-enforced)=" + armB + " " + zoneDetail;
            return Task.Status.FAILED;
        }
        return Task.Status.DONE;
    }
}
