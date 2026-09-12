package com.dddgn.alice.task;

import com.dddgn.alice.bot.BotManager;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.decision.BotEventLog;
import com.dddgn.alice.decision.EventThresholds;
import com.dddgn.alice.item.FixtureToolKit;
import com.dddgn.alice.log.BotLog;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * **事件阈值自检**（S4 事件层 / D-150）：验"该开口的病症开口、且只开口一次"。
 *
 * <pre>
 * A 工具见底（所有斧镐压到 15%）⇒ 60 tick 内出现 **恰好一条** TOOL_LOW
 * B 换新工具（修满）再压到 15% ⇒ 再出现 **恰好一条**（滞回复位有效，不是一次性开关）
 * C 有移动意图（顶着竖井壁长按前进）而脚位 200+ tick 不动 ⇒ 出现 **恰好一条** STUCK
 * D 继续顶 100 tick ⇒ **不再增加**（同一病症不刷屏）
 * </pre>
 *
 * <p>为什么 A/B 必须成对：只测 A 无法区分"阈值检测"和"启动时喊一次的一次性开关"；
 * 只测 B 无法证明第一次真的报了。为什么 C/D 必须成对：只测 C 无法证明没有每 tick 重复上报
 * （2026-09-12 实测 `[Pickup] blocked` 刷了 584 行，正是这个坑）。
 *
 * <p>C/D 用 `alice_test:pillar_course` 的 **1×1 基岩竖井**：长按前进 = 真的有移动意图却一格挪不动
 * （`controller.hasActiveMovement()==true`），这是"卡住"的定义；反过来，等待态（没意图）**不该**报 ——
 * 这条由检测器实现保证（见 `EventThresholds`），本任务只验"该报时报、只报一次"。
 *
 * <p>夹具前提自带断言：找不到斧/镐、bot 不在地面、竖井未封闭、bot 挤出竖井 ⇒ 直接 FAIL 报夹具问题，
 * 而不是把夹具故障算成"阈值没触发"（D-135 附注的教训）。
 */
public class EventThresholdCheckTask implements Task {

    /** 压到 15%（&lt; 20% 阈值，且离滞回复位线 35% 有距离）。 */
    private static final float LOW_RATIO = 0.15F;
    /** 每段观察窗口：远大于"1 tick 内就该发"的实际需要，又不至于拖长电池。 */
    private static final int OBSERVE_TICKS = 60;
    /** 落地结算等待（teleport 后 `onGround` 需要下一 tick 的 `move()` 才更新）。 */
    private static final int SETTLE_TICKS = 10;
    /** 归一化等待：修满后要等检测器看到"≥35%"才会复位滞回（见 D-150：可重复运行的前提）。 */
    private static final int NORMALIZE_TICKS = 30;
    /** 传送时抬高多少再落下（见 setup 注释：让落地那次位移带截断，`onGround` 才会被写成 true）。 */
    private static final double SETTLE_DROP = 0.25D;
    /** STUCK 观察窗口：阈值 200 + 余量（含"时钟复零后立刻计时"的几 tick）。 */
    private static final int STUCK_WINDOW = EventThresholds.STUCK_WINDOW_TICKS + 60;
    /** STUCK 之后继续不动的观察窗口（验"不重复上报"）。 */
    private static final int QUIET_TICKS = 100;

    private enum Phase {
        SETUP, SETTLE, NORMALIZE, LOW_FIRST, LOW_REARM, STUCK_FIRE, STUCK_QUIET, DONE
    }

    private final BotPlayer bot;
    private final ServerPlayer observer;

    private Phase phase = Phase.SETUP;
    private int ticks;
    private int phaseTicks;
    private int toolBase;
    private int stuckBase;
    private BlockPos stuckFoot;
    private final List<String> failures = new ArrayList<>();
    /** 真的断言过的用例（用于区分 PASS 与 NOT_RUN）。 */
    private final java.util.Set<String> completed = new java.util.HashSet<>();
    private String note = "-";

    public EventThresholdCheckTask(BotPlayer bot, ServerPlayer observer) {
        this.bot = bot;
        this.observer = observer;
    }

    @Override
    public String taskName() {
        return "EventThresholdCheck";
    }

    @Override
    public TaskTarget target() {
        return TaskTarget.block(PillarDiagnosticTask.SHAFT_START);
    }

    @Override
    public String failureReason() {
        return failures.isEmpty() ? "" : String.join(",", failures);
    }

    @Override
    public String terminalReason() {
        return phase == Phase.DONE ? (failures.isEmpty() ? "passed" : "failed") : "";
    }

    @Override
    public Status tick() {
        if (++ticks > 2000) {
            return finish("timeout");
        }
        phaseTicks++;
        return switch (phase) {
            case SETUP -> setup();
            case SETTLE -> settle();
            case NORMALIZE -> normalize();
            case LOW_FIRST -> lowFirst();
            case LOW_REARM -> lowRearm();
            case STUCK_FIRE -> stuckFire();
            case STUCK_QUIET -> stuckQuiet();
            case DONE -> done();
        };
    }

    // ==================== 用例 ====================

    private Status setup() {
        ServerLevel level = bot.serverLevel();
        var server = level.getServer();
        var source = server.createCommandSourceStack().withSuppressedOutput();
        // **先重建场景**（函数自带 reset：中心列 `fill … air` + 基岩壁 + y=63 石台）：
        // 2026-09-12 实测：上一轮 pillar 测试残留的方块把竖井堵死 ⇒ bot 一进去就 SUFFOCATING，
        // 维生系统在第 1 tick 就打断任务，夹具根本没机会断言。
        server.getCommands().performPrefixedCommand(source, "function alice_test:pillar_course");
        BlockPos shaft = PillarDiagnosticTask.SHAFT_START;
        // **抬高一点点再落下**（0.25）：`onGround` 在 vanilla 里是**粘滞**的 —— 只有真的发生一次
        // "被截断的位移"才会被写成 true；传送到**刚好贴地**再每 tick 清零速度 ⇒ 每次移动都被
        // 完全挡住（截断后位移=0）⇒ 那一支被跳过 ⇒ `onGround` 永远停在 teleport 后的 false。
        // 2026-09-12 探针实测：pos 恒为 64.000、velocity.y 恒为 -0.078、onGround 恒 false。
        bot.teleportTo(level, shaft.getX() + 0.5D, shaft.getY() + SETTLE_DROP, shaft.getZ() + 0.5D,
                java.util.Set.of(), bot.getYRot(), bot.getXRot());
        bot.setDeltaMovement(Vec3.ZERO);
        bot.controller().stopMovement();
        if (BotManager.sessionOf(bot) == null) {
            return finish("no_session");
        }
        // **自检期间暂停决策层触发**：否则 S4 自己发的事件会把 LLM 招来，`stop_current` 中途砍掉本任务
        // （2026-09-12 实测：第 522 tick 被砍，最后一个用例没跑完）。
        com.dddgn.alice.decision.GoalDirector.suspend(bot, 1200);
        FixtureToolKit.ensureAxe(bot);
        FixtureToolKit.ensurePickaxe(bot);
        // **必须等落地结算**：`onGround` 由 `move()` 更新，teleport 当 tick 读到的还是上一状态的 stale 值
        // （2026-09-12 实测：同 tick 断言 ⇒ 空气竖井里也报 fixture_not_on_ground）
        advance(Phase.SETTLE);
        return Status.RUNNING;
    }

    /** 等重力结算（10 tick 静止），再做夹具前提断言。 */
    private Status settle() {
        if (phaseTicks == 1) {
            bot.controller().stopMovement();
        }
        // **不再每 tick 清速度**：2026-09-12 实测那样会让 bot 悬在 y=64.25 不下落（外部每 tick 归零速度
        // 与物理互相打架），也让夹具凭空造出"onGround=false"的怪状态。只停输入即可。
        if (phaseTicks < SETTLE_TICKS) {
            return Status.RUNNING;
        }
        ServerLevel level = bot.serverLevel();
        BlockPos shaft = PillarDiagnosticTask.SHAFT_START;
        // 前提①：竖井中心列（脚位 + 头位）必须真的可通行 —— 堵住就会窒息，测的是空气
        if (!isPassable(level, shaft) || !isPassable(level, shaft.above())) {
            failures.add("fixture_shaft_blocked");
            return finish("fixture_shaft_blocked");
        }
        // 前提②：脚下有实心地板（否则会往下掉，脚位一变"不动"就重新计时）
        if (!level.getBlockState(shaft.below()).isSolid()) {
            failures.add("fixture_no_floor");
            return finish("fixture_no_floor");
        }
        // 前提③：真的**站在地板上**。这里不用 `onGround()` 判死：2026-09-12 实测它在
        // "传送进竖井 + 静止"状态下读不到 true（世界存档确认脚下就是石头地板、四周空气），
        // 属于 `onGround` 该 tick 的取值问题，不是"悬空"。改用**可推导**的支撑判定：
        // 脚位 y 落在"脚下方块顶面 ±0.5"内 ⇒ 站在地板上（悬空/下落必然超界）。
        // **差一格踩过坑**：脚下方块（y = shaft.y - 1）的**底面**在 shaft.getY() - 1，顶面 = 底面 + 形状高度。
        // 2026-09-12 实测写成 `shaft.getY() + 形状高度` ⇒ floorTop=65.0（真值 64.0）⇒ 前提误报失败。
        BlockPos below = shaft.below();
        double floorTop = below.getY() + level.getBlockState(below)
                .getCollisionShape(level, below).max(net.minecraft.core.Direction.Axis.Y);
        double feetY = bot.getY();
        boolean supported = feetY >= floorTop - 0.05D && feetY <= floorTop + 0.5D;
        // 为什么把包围盒/碰撞数一起打出来：2026-09-12 探针发现 bot 停在 y=64.25 **不下落**
        // （速度恒 -0.078、位置恒定、onGround=false）。这条定点诊断用来判定是"包围盒与方块相撞"
        // 还是别的机制 —— 不猜，取数。
        var box = bot.getBoundingBox();
        int collisions = 0;
        for (net.minecraft.world.phys.shapes.VoxelShape ignored : level.getBlockCollisions(bot, box)) {
            collisions++;
        }
        BotLog.info("[EventThreshold] premise feetY={} floorTop={} onGround={} dy={} supported={}"
                        + " box=[{}..{}] collisions={} blocks: foot={} above={} above2={}",
                feetY, floorTop, bot.onGround(), bot.getDeltaMovement().y, supported,
                String.format(java.util.Locale.ROOT, "%.2f,%.2f,%.2f", box.minX, box.minY, box.minZ),
                String.format(java.util.Locale.ROOT, "%.2f,%.2f,%.2f", box.maxX, box.maxY, box.maxZ),
                collisions,
                level.getBlockState(shaft).getBlock().getName().getString(),
                level.getBlockState(shaft.above()).getBlock().getName().getString(),
                level.getBlockState(shaft.above(2)).getBlock().getName().getString());
        if (!supported) {
            failures.add("fixture_not_supported");
            return finish("fixture_not_supported");
        }
        if (!bot.onGround()) {
            // 不判死：`supported` 已经证明它踩在地板上；这里只记录（若仍为 false 说明"抬高再落"也没能
            // 触发一次带截断的位移，需要单独查站立判定的其他读者，而不是把夹具卡死在这）
            BotLog.warn("[EventThreshold] onGround 仍为 false（supported=true 说明确实站在 {} 上）",
                    floorTop);
        }
        // 前提④：竖井四壁封闭（否则"顶壁挪不动"不成立）
        if (!shaftEnclosed(level, shaft)) {
            failures.add("fixture_shaft_not_enclosed");
            return finish("fixture_shaft_not_enclosed");
        }
        // 前提⑤：必须真的有可损伤的斧/镐
        if (countTools() == 0) {
            failures.add("fixture_no_tool");
            return finish("fixture_no_tool");
        }
        // **归一化**（可重复运行的前提）：夹具会把斧/镐压到 15%，上一轮结束时它可能**仍处于"已上报"状态**
        // ⇒ 直接压到 15% 不会产生新事件（2026-09-12 实测：第二轮 A/B 直接 FAIL）。
        // 所以每轮先**修满并等滞回复位**，再压到 15% 起算。
        setAllToolsRatio(1.0F);
        BotLog.info("[EventThreshold] 工具归一化：已修满，等 {} tick 让滞回复位", NORMALIZE_TICKS);
        advance(Phase.NORMALIZE);
        return Status.RUNNING;
    }

    /** 归一化：等修满被检测到（滞回复位）⇒ 再压到 15% 起算基准。 */
    private Status normalize() {
        if (phaseTicks < NORMALIZE_TICKS) {
            return Status.RUNNING;
        }
        if (EventThresholds.toolLowReported(bot)) {
            // 如实报：复位没发生，说明检测器或夹具坏了，不能拿"没事件"当通过
            failures.add("fixture_rearm_failed");
            return finish("fixture_rearm_failed");
        }
        setAllToolsRatio(LOW_RATIO);
        toolBase = countEvents("TOOL_LOW");
        stuckBase = countEvents("STUCK");
        note = "tools=" + countTools() + " onGround=" + bot.onGround()
                + " shaft=" + PillarDiagnosticTask.SHAFT_START.toShortString();
        BotLog.info("[EventThreshold] 夹具就绪 {} toolBase={} stuckBase={}", note, toolBase, stuckBase);
        advance(Phase.LOW_FIRST);
        return Status.RUNNING;
    }

    /** 方块是否可通行（无碰撞形状 = air / 草 / 火把这一类）。 */
    private static boolean isPassable(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
    }

    /** 竖井前提：脚位与脚上一格的四邻都是实心（bot 顶壁走不动）。 */
    private boolean shaftEnclosed(ServerLevel level, BlockPos shaft) {
        for (BlockPos pos : new BlockPos[]{shaft, shaft.above()}) {
            for (net.minecraft.core.Direction direction : net.minecraft.core.Direction.Plane.HORIZONTAL) {
                if (!level.getBlockState(pos.relative(direction)).isSolid()) {
                    BotLog.warn("[EventThreshold] 竖井未封闭 pos={} dir={}", pos.toShortString(), direction);
                    return false;
                }
            }
        }
        return true;
    }

    /** A：压到 15% 后，60 tick 内恰好一条 TOOL_LOW。 */
    private Status lowFirst() {
        int now = countEvents("TOOL_LOW");
        if (now > toolBase) {
            record("tool_low_once", now - toolBase == 1, "delta=" + (now - toolBase)
                    + " ticks=" + phaseTicks);
            advance(Phase.LOW_REARM);
            return Status.RUNNING;
        }
        if (phaseTicks > OBSERVE_TICKS) {
            record("tool_low_once", false, "60 tick 内无 TOOL_LOW（耐久已压到 15%）");
            advance(Phase.LOW_REARM);
        }
        return Status.RUNNING;
    }

    /** B：修满（复位滞回）→ 再压到 15% ⇒ 应再报一条。 */
    private Status lowRearm() {
        // 前半段：修满并让检测看到"已复位"
        if (phaseTicks <= 30) {
            setAllToolsRatio(1.0F);
            return Status.RUNNING;
        }
        if (phaseTicks == 31) {
            if (EventThresholds.toolLowReported(bot)) {
                record("tool_rearm", false, "修满后仍处于已报状态（滞回未复位）");
                advance(Phase.STUCK_FIRE);
                return Status.RUNNING;
            }
            setAllToolsRatio(LOW_RATIO);
            return Status.RUNNING;
        }
        int now = countEvents("TOOL_LOW");
        if (now > toolBase + 1) {
            record("tool_rearm", now - toolBase == 2, "delta=" + (now - toolBase)
                    + " ticks=" + phaseTicks);
            advance(Phase.STUCK_FIRE);
            return Status.RUNNING;
        }
        if (phaseTicks > 30 + OBSERVE_TICKS) {
            record("tool_rearm", false, "复位后再压到 15% 未再报 TOOL_LOW（delta="
                    + (now - toolBase) + "）");
            advance(Phase.STUCK_FIRE);
        }
        return Status.RUNNING;
    }

    /** C：**有移动意图**（顶壁长按前进）而脚位不动 ⇒ 阈值一到就报 STUCK。 */
    private Status stuckFire() {
        if (phaseTicks == 1) {
            EventThresholds.resetStuckTracking(bot);
            stuckBase = countEvents("STUCK");
            stuckFoot = com.dddgn.alice.pathing.MovementHelper.footCell(bot.serverLevel(), bot);
            BotLog.info("[EventThreshold] STUCK 时钟复零（窗口={} foot={}）",
                    EventThresholds.STUCK_WINDOW_TICKS, stuckFoot.toShortString());
        }
        // 顶壁：真的有移动意图（hasActiveMovement=true），但一格都挪不动（自然物理，不清速度）
        bot.controller().setForward(1.0F);
        // **前提断言**：bot 必须还在竖井底那一格；被挤出去说明夹具不成立，"没报 STUCK"不作数
        BlockPos nowFoot = com.dddgn.alice.pathing.MovementHelper.footCell(bot.serverLevel(), bot);
        if (!nowFoot.equals(stuckFoot)) {
            record("stuck_once", false, "夹具失效：bot 从 " + stuckFoot.toShortString() + " 挪到了 "
                    + nowFoot.toShortString() + "（竖井没关住）");
            bot.controller().stopMovement();
            advance(Phase.STUCK_QUIET);
            return Status.RUNNING;
        }
        int now = countEvents("STUCK");
        if (now > stuckBase) {
            record("stuck_once", now - stuckBase == 1, "delta=" + (now - stuckBase)
                    + " ticks=" + phaseTicks + " intent=true");
            advance(Phase.STUCK_QUIET);
            return Status.RUNNING;
        }
        if (phaseTicks > STUCK_WINDOW) {
            record("stuck_once", false, "有意图但不动 " + phaseTicks + " tick 仍未报 STUCK（阈值="
                    + EventThresholds.STUCK_WINDOW_TICKS + "）");
            advance(Phase.STUCK_QUIET);
        }
        return Status.RUNNING;
    }

    /** D：继续顶壁 ⇒ 同一病症不重复上报。 */
    private Status stuckQuiet() {
        if (bot.controller().hasActiveMovement()) {
            bot.controller().setForward(1.0F);
        }
        if (phaseTicks < QUIET_TICKS) {
            return Status.RUNNING;
        }
        int now = countEvents("STUCK");
        record("stuck_no_spam", now == stuckBase + 1, "继续顶壁 " + phaseTicks
                + " tick 后 STUCK 总数=" + now + "（期望 " + (stuckBase + 1) + "）");
        bot.controller().stopMovement();
        advance(Phase.DONE);
        return Status.RUNNING;
    }

    /** 正常收尾：**成功也要打 SUMMARY**（2026-09-12 实测：只在失败路径打，成功时四条 case= 却无结论行）。 */
    private Status done() {
        return finish(failures.isEmpty() ? "passed" : "failed");
    }

    // ==================== 工具/事件工具方法 ====================

    /** 保持完全静止：停控制器 + 清速度（进入静止段时调一次，避免每 tick 刷 stop_movement 日志）。 */
    private void hold() {
        bot.controller().stopMovement();
        bot.setDeltaMovement(Vec3.ZERO);
    }

    private int countTools() {
        int n = 0;
        var inventory = bot.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (isTool(stack)) {
                n++;
            }
        }
        return n;
    }

    private static boolean isTool(ItemStack stack) {
        return !stack.isEmpty() && stack.isDamageableItem()
                && (stack.getItem() instanceof AxeItem || stack.getItem() instanceof PickaxeItem);
    }

    /** 把所有斧/镐的剩余耐久压到 `remaining` 比例（写 `damageValue`，不改变物品本身）。 */
    private void setAllToolsRatio(float remaining) {
        var inventory = bot.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!isTool(stack)) {
                continue;
            }
            int max = stack.getMaxDamage();
            int damage = Math.round(max * (1.0F - remaining));
            stack.setDamageValue(Math.max(0, Math.min(max - 1, damage)));
        }
        BotManager.syncMainHand(bot);
    }

    private int countEvents(String type) {
        int n = 0;
        for (BotEventLog.BotEvent event : BotEventLog.recent(bot, BotEventLog.CAPACITY)) {
            if (type.equals(event.type())) {
                n++;
            }
        }
        return n;
    }

    private void record(String name, boolean ok, String detail) {
        completed.add(name);
        if (!ok) {
            failures.add(name);
        }
        BotLog.info("[EventThreshold] case={} result={} {}", name, ok ? "PASS" : "FAIL", detail);
    }

    private void advance(Phase next) {
        phase = next;
        phaseTicks = 0;
    }

    private Status finish(String reason) {
        phase = Phase.DONE;
        bot.controller().stopMovement();
        // **收尾复原**：夹具把工具压到过 15%，留给下一轮/下一个任务会持续触发 TOOL_LOW
        setAllToolsRatio(1.0F);
        String summary = "tool_low_once=" + verdict("tool_low_once")
                + " tool_rearm=" + verdict("tool_rearm")
                + " stuck_once=" + verdict("stuck_once")
                + " stuck_no_spam=" + verdict("stuck_no_spam")
                + " verdict=" + (failures.isEmpty() && "passed".equals(reason) ? "PASS" : "FAIL");
        BotLog.info("[EventThreshold] SUMMARY {} {} reason={}", summary, note, reason);
        if (observer != null) {
            observer.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "[EventThreshold] " + summary + " reason=" + reason));
        }
        return failures.isEmpty() && "passed".equals(reason) ? Status.DONE : Status.FAILED;
    }

    /**
     * 用例结论：**跑过且通过**才 PASS；跑过但失败 FAIL；**没跑到 NOT_RUN**。
     *
     * <p>2026-09-12 实测：夹具前提失败时四个用例一个都没跑，旧实现却全打印 PASS
     * （`verdict()` 只查"用例名是否在 failures 里"，夹具失败映射不到用例上）—— 把"没跑"报成
     * "通过"是最危险的一种谎，这里改成三态。
     */
    private String verdict(String name) {
        if (failures.contains(name)) {
            return "FAIL";
        }
        return completed.contains(name) ? "PASS" : "NOT_RUN";
    }
}
