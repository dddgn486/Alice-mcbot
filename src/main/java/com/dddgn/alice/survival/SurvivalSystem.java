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
