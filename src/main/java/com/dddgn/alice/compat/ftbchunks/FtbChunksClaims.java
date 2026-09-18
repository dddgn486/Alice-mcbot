package com.dddgn.alice.compat.ftbchunks;

import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.protection.ClaimSources;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * **FTB Chunks 只读兼容**（D-316）——软依赖、反射、模组缺失即空转。
 *
 * <p>方向（用户裁定）：**只做 FTB → 我们**（把 FTB 认领的地盘当成保护区，bot 不碰）。
 * **不写回**：`claimAsPlayer(...)` 是"以那个玩家**自己的队伍**认领"，用它同步任务区会把地认到
 * Alice 队头上（额度/可见性/保护对象都不是玩家的队伍）⇒ 要写回必须由**真人玩家**作 claimant，
 * 那是另一次裁定，本轮不做。
 *
 * <p>已核实的模组事实（1.20.1 / `2001.3.8` 源码，非推测）：
 * <ul>
 *   <li>读认领：{@code FTBChunksAPI.api().getManager().getChunk(ChunkDimPos)} ⇒ {@code ClaimedChunk} 或 {@code null}；
 *       {@code ChunkDimPos} 在 **FTB Library**（`dev.ftb.mods.ftblibrary.math`）里，不是 FTB Chunks 自己的类；</li>
 *   <li>保护生效点在 {@code ClaimedChunkManagerImpl#shouldPreventInteraction}，读的正是同一个 {@code getChunk}
 *       ⇒ 我们的预检与它的执法**同源同判**，不会出现"我们说能挖、它拦下"的分叉；</li>
 *   <li>它的保护判据里有 {@code PlayerHooks.isFake(player)} 分支（假人白名单 `allow_named_fake_players`、
 *       全局 `fake_players=ALLOW/DENY`）与全绕过的 {@code getBypassProtection}/{@code setBypassProtection}
 *       （公开 API，落点 = 该玩家队伍的 extraData）。⚠️ 我们的 bot 是**玩家化的真 `ServerPlayer`**
 *       （不是 Forge `FakePlayer`）⇒ **它到底走"假人"分支还是"普通玩家"分支，必须实测**（客户端轮次）。</li>
 * </ul>
 *
 * <p>⚠️ 反射的失败方向：**任何一步解析不了 ⇒ {@code available()}=false ⇒ 空转**（于是行为回到"只有本地认领"），
 * 并在日志里**响亮**说明原因 —— 绝不让兼容层把服务端搞崩，也不假装自己生效了。
 */
public final class FtbChunksClaims implements ClaimSources.Source {

    /** 来源 id ⇒ 理由码 {@code protected_ftb_claim}（与 `protected_area` / `protected_block` 同一族）。 */
    public static final String ID = "ftb_claim";

    public static final FtbChunksClaims INSTANCE = new FtbChunksClaims();

    private static final String API_CLASS = "dev.ftb.mods.ftbchunks.api.FTBChunksAPI";
    private static final String MANAGER_CLASS = "dev.ftb.mods.ftbchunks.api.ClaimedChunkManager";
    private static final String CHUNK_DIM_POS_CLASS = "dev.ftb.mods.ftblibrary.math.ChunkDimPos";

    /** 解析结果缓存（null = 还没试过；模组在场与否在一个进程里不会变）。 */
    private static volatile Boolean available;
    private static Method apiMethod;
    private static Method isManagerLoadedMethod;
    private static Method getManagerMethod;
    private static Method getChunkMethod;
    private static Constructor<?> chunkDimPosConstructor;

    /** 每个真实世界 tick 只打一次"空转"提示，避免刷屏。 */
    private static int lastSkipLogTick = Integer.MIN_VALUE;

    private FtbChunksClaims() {
    }

    /** 模组是否在场且 API 解析成功（缓存）。 */
    public static boolean available() {
        Boolean cached = available;
        if (cached != null) {
            return cached;
        }
        synchronized (FtbChunksClaims.class) {
            if (available == null) {
                available = resolve();
            }
            return available;
        }
    }

    private static boolean resolve() {
        try {
            Class<?> apiClass = Class.forName(API_CLASS);
            Class<?> managerClass = Class.forName(MANAGER_CLASS);
            Class<?> chunkDimPosClass = Class.forName(CHUNK_DIM_POS_CLASS);
            apiMethod = apiClass.getMethod("api");
            isManagerLoadedMethod = apiClass.getMethod("isManagerLoaded");
            getManagerMethod = apiClass.getMethod("getManager");
            getChunkMethod = managerClass.getMethod("getChunk", chunkDimPosClass);
            chunkDimPosConstructor = chunkDimPosClass.getConstructor(ResourceKey.class, ChunkPos.class);
            BotLog.info("[Protection] 检测到 FTB Chunks：认领将以 {} 接入保护区闸门（只读，不写回）", ID);
            return true;
        } catch (ClassNotFoundException absent) {
            BotLog.info("[Protection] 未检测到 FTB Chunks（软依赖）⇒ 保护区只认本地认领");
            return false;
        } catch (ReflectiveOperationException | LinkageError changed) {
            BotLog.warn("[Protection] FTB Chunks 在场但 API 对不上（版本变了？）⇒ 兼容层空转：{}",
                    changed.toString());
            return false;
        }
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public boolean isClaimed(ServerLevel level, ChunkPos pos) {
        if (!available()) {
            return false;
        }
        try {
            Object api = apiMethod.invoke(null);
            if (api == null || !(Boolean) isManagerLoadedMethod.invoke(api)) {
                // 管理器还没起来（服务器启动早期）⇒ 这一问按"没认领"答，且**不刷屏**
                logSkipOnce(level, "manager_not_loaded");
                return false;
            }
            Object manager = getManagerMethod.invoke(api);
            Object chunkDimPos = chunkDimPosConstructor.newInstance(level.dimension(), pos);
            return getChunkMethod.invoke(manager, chunkDimPos) != null;
        } catch (ReflectiveOperationException | LinkageError broken) {
            logSkipOnce(level, broken.toString());
            return false;
        }
    }

    private static void logSkipOnce(ServerLevel level, String why) {
        int tick = level.getServer() == null ? 0 : level.getServer().getTickCount();
        if (tick == lastSkipLogTick) {
            return;
        }
        lastSkipLogTick = tick;
        BotLog.warn("[Protection] FTB Chunks 查询暂时不可用（本 tick 内不再重复）：{}", why);
    }
}
