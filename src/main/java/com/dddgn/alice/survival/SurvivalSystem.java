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

    public static boolean shouldInterrupt(HazardState state) {
        return state.type() == HazardType.LAVA_CONTACT
                || state.type() == HazardType.SUFFOCATING;
    }

    public static String interruptionReason(HazardState state) {
        return switch (state.type()) {
            case LAVA_CONTACT -> "survival_lava_contact";
            case SUFFOCATING -> "survival_suffocating";
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
        BlockPos origin = bot.blockPosition();
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
