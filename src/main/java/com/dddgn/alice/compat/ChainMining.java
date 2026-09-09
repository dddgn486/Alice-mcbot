package com.dddgn.alice.compat;

import com.dddgn.alice.log.BotLog;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.Tags;

import java.lang.reflect.Method;
import java.util.Set;
import java.util.UUID;

/**
 * Ore Excavation（连锁挖掘）兼容适配器（D-075/D-077）——**软依赖**，反射调用，模组缺失即降级。
 *
 * <p>为什么是反射：该模组的连锁由**客户端按键 + mineMode**触发
 * （`EventHandler.onBlockBreak` → `PacketExcavation` 下发客户端 → 客户端回传形状 → `ExcPacketHandler.acceptServer`），
 * bot 没有客户端，因此只能直接调用它的**服务端入口**
 * `MiningScheduler.INSTANCE.startMining(ServerPlayer, BlockPos, BlockState, ExcavateShape, Direction, Direction)`。
 *
 * <p>已核实的模组事实（1.13.174 字节码，非推测）：
 * <ul>
 *   <li>`startMining` 是公开服务端 API；`shape = null` 合法；返回 `null` = `EventExcavate.Pre` 被取消；</li>
 *   <li>连锁破坏走 `player.gameMode.destroyBlock(pos)` → **会触发 `BlockEvent.BreakEvent`**，作用域配对链成立；</li>
 *   <li>连锁期间 `EventHandler.onEntitySpawn` 取消 ItemEntity/XP 生成并缓冲（`captureAgent` 是**静态单例**），
 *       结束时 `dropEverything()` 在**同一格**一次性生成 → 本项目的延迟登记 + 簇级收集正好适配；</li>
 *   <li>工具的检查是**主手物品**（`ItemStack.isCorrectToolForDrops`），bot 必须手持对应工具。</li>
 * </ul>
 *
 * <p>**租约**：模组的 `captureAgent` 是全局静态，同一时刻只允许一次连锁在跑；
 * 本类用全局租约（owner = 触发者 UUID）挡住并发，连锁结束后自动释放。
 */
public final class ChainMining {

    private static final String SCHEDULER_CLASS = "oreexcavation.handlers.MiningScheduler";
    private static final String SHAPE_CLASS = "oreexcavation.shapes.ExcavateShape";
    private static final String SETTINGS_CLASS = "oreexcavation.core.ExcavationSettings";

    /** 启动结果（失败原因如实区分，便于回落与诊断）。 */
    public enum StartResult {
        OK,
        /** 模组不在场。 */
        MOD_ABSENT,
        /** 已有其它 bot 的连锁在跑（模组 captureAgent 是全局静态）。 */
        LEASE_BUSY,
        /** `startMining` 返回 null（`EventExcavate.Pre` 被取消）。 */
        NOT_STARTED,
        /** 反射调用异常。 */
        REFLECTION_FAILED
    }

    /** 是否应走连锁：OFF 永否；AUTO 需模组在场 + 目标属矿石/原木；FORCE 只要求模组在场。 */
    public static boolean shouldChain(com.dddgn.alice.task.mining.MiningTuning.ChainMode mode, BlockState state) {
        if (mode == null || mode == com.dddgn.alice.task.mining.MiningTuning.ChainMode.OFF) {
            return false;
        }
        if (!available()) {
            return false;
        }
        return mode == com.dddgn.alice.task.mining.MiningTuning.ChainMode.FORCE || isChainable(state);
    }

    /** 默认只连锁**矿石与原木**（D-075 用户裁定）。 */
    public static boolean isChainable(BlockState state) {
        return state != null && (state.is(Tags.Blocks.ORES) || state.is(BlockTags.LOGS));
    }

    /** 模组是否在场（缓存 Class.forName 结果）。 */
    public static boolean available() {
        Boolean cached = available;
        if (cached != null) {
            return cached;
        }
        boolean found;
        try {
            Class.forName(SCHEDULER_CLASS);
            found = true;
        } catch (ClassNotFoundException e) {
            found = false;
        }
        available = found;
        return found;
    }

    public static StartResult start(ServerPlayer bot, BlockPos pos) {
        if (!available()) {
            return StartResult.MOD_ABSENT;
        }
        releaseStaleLease();
        if (leaseOwner != null && !leaseOwner.equals(bot.getUUID())) {
            return StartResult.LEASE_BUSY;
        }
        try {
            Class<?> schedulerClass = Class.forName(SCHEDULER_CLASS);
            Class<?> shapeClass = Class.forName(SHAPE_CLASS);
            Object scheduler = schedulerClass.getField("INSTANCE").get(null);
            Method start = schedulerClass.getMethod("startMining", ServerPlayer.class, BlockPos.class,
                    BlockState.class, shapeClass, net.minecraft.core.Direction.class,
                    net.minecraft.core.Direction.class);
            BlockState state = bot.serverLevel().getBlockState(pos);
            Object agent = start.invoke(scheduler, bot, pos, state, null,
                    net.minecraft.core.Direction.UP, net.minecraft.core.Direction.NORTH);
            if (agent == null) {
                return StartResult.NOT_STARTED;
            }
            leaseOwner = bot.getUUID();
            return StartResult.OK;
        } catch (ReflectiveOperationException e) {
            BotLog.warn("[ChainMining] 反射触发失败: {}", e);
            return StartResult.REFLECTION_FAILED;
        }
    }

    /** 该 bot 是否还有进行中的连锁（返回 false 时顺带释放租约）。 */
    public static boolean isRunning(ServerPlayer bot) {
        if (!available()) {
            return false;
        }
        try {
            Class<?> schedulerClass = Class.forName(SCHEDULER_CLASS);
            Object scheduler = schedulerClass.getField("INSTANCE").get(null);
            Method getActiveAgent = schedulerClass.getMethod("getActiveAgent", UUID.class);
            Object agent = getActiveAgent.invoke(scheduler, bot.getUUID());
            if (agent == null) {
                if (bot.getUUID().equals(leaseOwner)) {
                    leaseOwner = null;
                }
                return false;
            }
            return true;
        } catch (ReflectiveOperationException e) {
            BotLog.warn("[ChainMining] 查询连锁状态失败: {}", e);
            return false;
        }
    }

    /** 该 bot 的连锁已破坏方块数（诊断用）。 */
    public static int minedCount(ServerPlayer bot) {
        if (!available()) {
            return 0;
        }
        try {
            Class<?> schedulerClass = Class.forName(SCHEDULER_CLASS);
            Object scheduler = schedulerClass.getField("INSTANCE").get(null);
            Method getActiveAgent = schedulerClass.getMethod("getActiveAgent", UUID.class);
            Object agent = getActiveAgent.invoke(scheduler, bot.getUUID());
            if (agent == null) {
                return 0;
            }
            Object value = agent.getClass().getField("minedBlocks").get(agent);
            return value instanceof Set<?> set ? set.size() : 0;
        } catch (ReflectiveOperationException e) {
            return 0;
        }
    }

    public static void stop(ServerPlayer bot) {
        if (!available()) {
            return;
        }
        try {
            Class<?> schedulerClass = Class.forName(SCHEDULER_CLASS);
            Object scheduler = schedulerClass.getField("INSTANCE").get(null);
            schedulerClass.getMethod("stopMining", ServerPlayer.class).invoke(scheduler, bot);
        } catch (ReflectiveOperationException e) {
            BotLog.warn("[ChainMining] 停止连锁失败: {}", e);
        } finally {
            if (bot.getUUID().equals(leaseOwner)) {
                leaseOwner = null;
            }
        }
    }

    /** 模组当前配置摘要（诊断/日志用）；缺失或不可读时如实返回。 */
    public static String settingsSummary() {
        if (!available()) {
            return "absent";
        }
        try {
            Class<?> settingsClass = Class.forName(SETTINGS_CLASS);
            int range = settingsClass.getField("mineRange").getInt(null);
            int limit = settingsClass.getField("mineLimit").getInt(null);
            int speed = settingsClass.getField("mineSpeed").getInt(null);
            boolean autoPickup = settingsClass.getField("autoPickup").getBoolean(null);
            boolean ignoreTools = settingsClass.getField("ignoreTools").getBoolean(null);
            return "range=" + range + ",limit=" + limit + ",speed=" + speed
                    + ",autoPickup=" + autoPickup + ",ignoreTools=" + ignoreTools;
        } catch (ClassNotFoundException e) {
            return "absent";
        } catch (ReflectiveOperationException e) {
            return "unreadable:" + e.getClass().getSimpleName();
        }
    }

    private static void releaseStaleLease() {
        if (leaseOwner == null) {
            return;
        }
        try {
            Class<?> schedulerClass = Class.forName(SCHEDULER_CLASS);
            Object scheduler = schedulerClass.getField("INSTANCE").get(null);
            Method getActiveAgent = schedulerClass.getMethod("getActiveAgent", UUID.class);
            if (getActiveAgent.invoke(scheduler, leaseOwner) == null) {
                leaseOwner = null;
            }
        } catch (ReflectiveOperationException e) {
            leaseOwner = null;
        }
    }

    private static volatile Boolean available;
    private static UUID leaseOwner;

    private ChainMining() {
    }
}
