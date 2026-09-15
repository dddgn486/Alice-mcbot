package com.dddgn.alice.survival;

import com.dddgn.alice.log.BotLog;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 所有 bot 共用的维生监控入口。第一版只观察并给出硬中断信号，不主动逃生或改动世界。
 * 这样挖矿、拾取、道路施工可以共享同一套生命安全底线。
 */
public final class SurvivalSystem {
    private static final Map<UUID, Monitor> MONITORS = new HashMap<>();

    private SurvivalSystem() {
    }

    public static HazardState tick(ServerPlayer bot) {
        Monitor monitor = MONITORS.computeIfAbsent(bot.getUUID(), ignored -> new Monitor());
        long gameTime = bot.level().getGameTime();
        if (monitor.lastTick == gameTime && monitor.lastState != null) {
            return monitor.lastState;
        }
        HazardState state = monitor.observe(bot);
        monitor.lastTick = gameTime;
        monitor.lastState = state;
        if (state.type() != HazardType.NONE && monitor.shouldLog(state)) {
            BotLog.warn("维生监测: bot={} hazard={} duration={} air={} health={} pos={}",
                    bot.getName().getString(), state.type(), state.durationTicks(), state.airSupply(),
                    state.health(), state.position().toShortString());
        }
        return state;
    }

    public static HazardState current(ServerPlayer bot) {
        Monitor monitor = MONITORS.get(bot.getUUID());
        return monitor == null || monitor.lastState == null
                ? new HazardState(HazardType.NONE, 0, bot.getAirSupply(),
                bot.getHealth(), bot.getHealth(), bot.blockPosition()) : monitor.lastState;
    }

    // ==================== 维生决策（S-5，2026-09-15）====================

    /**
     * **软危险的宽限期**（tick）：溺水/着火必须**先持续这么久**才可能行使否决权。
     *
     * <p>为什么需要它（两个独立理由，都不是凭感觉定的数）：
     * <ol>
     *   <li>**不抖动**：`ON_FIRE`/`LOW_AIR` 可以是单 tick 的现象（一脚踩进火里、头刚没入水面）。
     *       否决一次 = **中断一整个长作业**，代价远大于等 0.5 秒看它是否自解；</li>
     *   <li>**查询成本**：软危险的判据要问"有没有出口"（{@link #nearestSafeRefuge} 是 17³ 体积查询），
     *       不该每个 tick 都跑。</li>
     * </ol>
     *
     * <p>为什么是 **10**：溺水伤害发生在 `airSupply` 归零后**再 20 tick**（原版把 air 压到 −20 才结算），
     * 所以 10 tick 的宽限**仍然赶在第一次掉血之前**；着火伤害每 20 tick 一次，晚 0.5 秒同样安全。
     */
    public static final int SOFT_HAZARD_GRACE_TICKS = 10;

    /**
     * **维生决策**（纯查询，无副作用）：调用方（`BotSession`）据此决定"否决 / 不否决 / 如实登记不否决"。
     *
     * <p>为什么要有第三个值而不是布尔：**软危险"没有出口"时正确答案是"不否决"**（见
     * {@link #decide}），而这件事**必须留下痕迹** —— 否则日志上只看到"bot 在淹死/在烧，什么都没发生"，
     * 无法区分"判据没生效"和"判据生效了、判断是继续跑"。{@code HOLD_NO_EXIT} 就是"生效了但选择不动手"。
     */
    public enum Verdict {
        /** 不是危险，或还没到该动手的程度（宽限期内）——什么都不做。 */
        IGNORE,
        /** 行使否决权：中断当前任务，并由 `SurvivalExitTask` 走去逃生落点。 */
        INTERRUPT,
        /** **软危险 + 半径内无出口 ⇒ 不否决**（登记一次后让任务继续）。 */
        HOLD_NO_EXIT
    }

    /** **硬危险**：继续做任何事都只会更糟 ⇒ 无条件否决（不要求有出口）。 */
    public static boolean hardHazard(HazardType type) {
        return type == HazardType.LAVA_CONTACT || type == HazardType.SUFFOCATING;
    }

    /** **软危险**：溺水 / 着火 —— 需要"出口 + 宽限"才值得否决（见 {@link #decide}）。 */
    public static boolean softHazard(HazardType type) {
        return type == HazardType.LOW_AIR || type == HazardType.ON_FIRE;
    }

    /**
     * **唯一的维生决策入口**（S-5，2026-09-15）。
     *
     * <p>判据（按顺序）：
     * <ol>
     *   <li>硬危险（岩浆/窒息）⇒ {@link Verdict#INTERRUPT} —— 与 S-1 的行为完全一致，**本轮没改**；</li>
     *   <li>软危险（溺水/着火）且已过 {@link #SOFT_HAZARD_GRACE_TICKS}：**有出口才否决**；
     *       没出口 ⇒ {@link Verdict#HOLD_NO_EXIT}（**不否决**）。</li>
     * </ol>
     *
     * <p>为什么软危险"没出口就不否决"（本轮新增的判据，S-5）：否决的动作是"中断任务 + 走去落点"，
     * 而没落点时 `startSurvivalExit` 只能**如实登记"无出口"**并把 bot 留在原地 —— 对溺水/着火来说，
     * "停在原地挨着"**严格劣于**"让任务继续"（任务至少可能在往水面/安全处走）。硬危险不适用这条：
     * 岩浆里停不停都在烧，中断不会更糟（而且这是已实测过的既有行为，不在这轮改）。
     *
     * <p>配置读取：判据只读当前状态；结果由调用方决定**怎么登记**（软危险无出口的登记要求"一次"，
     * 见 `BotSession.tick(HazardState)` 的 `durationTicks == SOFT_HAZARD_GRACE_TICKS`）。
     */
    public static Verdict decide(ServerPlayer bot, HazardState state) {
        if (hardHazard(state.type())) {
            return Verdict.INTERRUPT;
        }
        if (!softHazard(state.type())) {
            return Verdict.IGNORE;
        }
        if (state.durationTicks() < SOFT_HAZARD_GRACE_TICKS) {
            return Verdict.IGNORE;
        }
        return hasRefuge(bot) ? Verdict.INTERRUPT : Verdict.HOLD_NO_EXIT;
    }

    /** 否决是否成立（保留旧名，语义 = {@link #decide} 是否给出 `INTERRUPT`）。 */
    public static boolean shouldInterrupt(ServerPlayer bot, HazardState state) {
        return decide(bot, state) == Verdict.INTERRUPT;
    }

    /** 半径 {@link #REFUGE_RADIUS} 内**有没有**出口（**排除 bot 当前格**：站在原地不算出口）。 */
    public static boolean hasRefuge(ServerPlayer bot) {
        return nearestSafeRefuge(bot, REFUGE_RADIUS, footCell(bot)) != null;
    }

    /**
     * **bot 真正站着的那一格**（S-5 修正，2026-09-15）—— 全类只此一份定义。
     *
     * <p>为什么不能用 `bot.blockPosition()`：那是"脚**所在**格"，而本类的落点判据
     * （{@code isRefuge} → `canWalkOn`）用的是**规划层口径**"支撑格的上一格"（`MovementHelper.footCell`，
     * D-105）。贴地时实体的 y 会落在方块顶面**略下方**，于是 `blockPosition()` 退回**支撑格**
     * —— 那一格是实体方块、永远不会是落点。用它当"排除自己"等于**没排除**：
     * bot 自己站的那格会被算成"有出口"，否决完 `SurvivalExitTask` 走到原地、0 步 COMPLETED，
     * 而 bot **一格没动**（2026-09-15 由新电池步 `survival_exit` 实测抓到：同 tick 里
     * `nearestSafeRefuge(bot, 8, blockPosition())` 非空、`nearestSafeRefuge(bot, 8, footCell())` 为空）。
     */
    public static BlockPos footCell(ServerPlayer bot) {
        return com.dddgn.alice.pathing.MovementHelper.footCell(bot.serverLevel(), bot);
    }

    /** 否决理由码（落进 `task_execution_terminal` 的 `code=` 词汇表，供台账引用）。 */
    public static String interruptionReason(HazardState state) {
        return switch (state.type()) {
            case LAVA_CONTACT -> "survival_lava_contact";
            case SUFFOCATING -> "survival_suffocating";
            case LOW_AIR -> "survival_low_air";
            case ON_FIRE -> "survival_on_fire";
            default -> "";
        };
    }

    public static void forget(ServerPlayer bot) {
        MONITORS.remove(bot.getUUID());
    }

    // ==================== S-1（P1-C）：否决必须带出口 ====================

    /** 逃生搜索半径（格）：够"离开脚下这一格危险"，又不至于把整片区域扫一遍。 */
    public static final int REFUGE_RADIUS = 8;

    /**
     * **只回答"哪个落点算安全"，不规划路径**（S-1 / P1-C，2026-09-12）。
     *
     * <p>为什么要有它：维生系统行使否决权之后，bot 原先**停在原地继续被烧** —— 这是全项目唯一的
     * "拒绝没有出口"反例。用户定的规矩是"**谁否决，都得说清那该去哪**"，所以这里只做**纯查询**，
     * 由 `BotSession` 发起 {@code SurvivalExitTask}（= 已验收的 `WalkToTask`）走过去。
     * 三方职责因此保持干净：**维生给落点、寻路给路径、维生仍然不找路**（本类 javadoc 的承诺不破）。
     *
     * <p>判据（保守，宁可少给也不给错）：脚位与头位**都可穿过**（不窒息）、**脚下有真支撑**
     * （`MovementHelper.canWalkOn`）、脚位/头位**都不是流体**（岩浆会烧、水会淹）、
     * **所在区块已加载**（S-2：绝不为逃生去同步加载区块）。
     *
     * @return 最近的安全脚位；半径内没有 ⇒ {@code null}（**如实返回 null**，由调用方登记"无出口"）
     */
    public static BlockPos nearestSafeRefuge(ServerPlayer bot, int radius) {
        return nearestSafeRefuge(bot, radius, null);
    }

    /** 同 {@link #nearestSafeRefuge(ServerPlayer, int)}，但可**排除一格**（通常是 bot 当前所在格）。 */
    public static BlockPos nearestSafeRefuge(ServerPlayer bot, int radius, BlockPos exclude) {
        if (!(bot.level() instanceof net.minecraft.server.level.ServerLevel level)) {
            return null;
        }
        // 搜索原点/距离基准 = **脚位格**（见 {@link #footCell}）：与 isRefuge 的规划层口径一致。
        BlockPos origin = footCell(bot);
        int r = Math.max(1, radius);
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-r, -r, -r), origin.offset(r, r, r))) {
            BlockPos candidate = pos.immutable();   // betweenClosed 复用同一个可变实例（见 BlockPos 可变性 skill）
            if (exclude != null && candidate.equals(exclude)) {
                continue;
            }
            if (!isRefuge(level, candidate)) {
                continue;
            }
            double distance = candidate.distSqr(origin);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return best;
    }

    private static boolean isRefuge(net.minecraft.server.level.ServerLevel level, BlockPos foot) {
        if (!level.hasChunkAt(foot)) {
            return false;   // S-2：不朝未加载区块逃避生（读那一格会同步加载区块）
        }
        if (!level.getFluidState(foot).isEmpty() || !level.getFluidState(foot.above()).isEmpty()) {
            return false;   // 脚/头位是流体（岩浆会烧、水会淹）
        }
        if (!com.dddgn.alice.pathing.MovementHelper.canWalkThrough(level, foot)
                || !com.dddgn.alice.pathing.MovementHelper.canWalkThrough(level, foot.above())) {
            return false;   // 身体/头部被挡 ⇒ 会窒息
        }
        return com.dddgn.alice.pathing.MovementHelper.canWalkOn(level, foot);
    }

    private static final class Monitor {
        private HazardType previous = HazardType.NONE;
        private int duration;
        private float previousHealth;
        private int logCooldown;
        private HazardState lastState;
        private long lastTick = Long.MIN_VALUE;

        private HazardState observe(ServerPlayer bot) {
            HazardType current = classify(bot);
            if (current == previous) {
                duration++;
            } else {
                previous = current;
                duration = current == HazardType.NONE ? 0 : 1;
            }
            float health = bot.getHealth();
            HazardState state = new HazardState(current, duration, bot.getAirSupply(), health,
                    previousHealth, bot.blockPosition());
            previousHealth = health;
            if (logCooldown > 0) logCooldown--;
            return state;
        }

        private boolean shouldLog(HazardState state) {
            if (logCooldown > 0) return false;
            logCooldown = state.type() == HazardType.NONE ? 0 : 20;
            return true;
        }

        private static HazardType classify(ServerPlayer bot) {
            if (bot.isInLava() || containsFluid(bot, Blocks.LAVA)) {
                return HazardType.LAVA_CONTACT;
            }
            if (bot.isInWall()) {
                return HazardType.SUFFOCATING;
            }
            if (bot.getAirSupply() <= 0) {
                return HazardType.LOW_AIR;
            }
            if (bot.isOnFire()) {
                return HazardType.ON_FIRE;
            }
            if (bot.isInWater() || containsWater(bot)) {
                return HazardType.WATER_CONTACT;
            }
            return HazardType.NONE;
        }

        private static boolean containsWater(ServerPlayer bot) {
            return bot.level().getFluidState(bot.blockPosition()).is(net.minecraft.tags.FluidTags.WATER)
                    || bot.level().getFluidState(bot.blockPosition().above()).is(net.minecraft.tags.FluidTags.WATER);
        }

        private static boolean containsFluid(ServerPlayer bot, net.minecraft.world.level.block.Block block) {
            BlockPos pos = bot.blockPosition();
            return bot.level().getBlockState(pos).is(block)
                    || bot.level().getBlockState(pos.above()).is(block);
        }
    }
}
