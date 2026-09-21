package com.dddgn.alice.task;

import com.dddgn.alice.bot.BotManager;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.decision.GoalDirector;
import com.dddgn.alice.log.BotLog;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ⭐ **「浮起来之后自己走上岸」的真人客户端入口**（用户 2026-09-21 第十二轮裁定）。
 *
 * <h2>它为什么存在（真机要验什么）</h2>
 * 这条行为改动落在**维生的无任务档**（`BotManager.tickHazardWithoutTask`）：无任务的 bot 在水里要
 * **先浮（`D-380`）→ 再走到 8 格内已有的无液体站位**（**纯通行、零写权**）。日志能证明"位置对了"，
 * 但"它是不是真的自己爬出来了、爬得自不自然"**只能人眼看** ⇒ 需要一次客户端轮次。
 *
 * <h2>入口（零参数；一条命令 = 建场景 + 就位 + 武装）</h2>
 * <pre>
 * /alice shore-escape-test
 * </pre>
 * 它做四件事：① 在**固定坐标**（{@link #CENTER}）清出一块**孤立**场景并建"平台 + 3×3×3 水池"；
 * ② 把 bot（没有就现生一个）**传送进池底**；③ **清掉它的任务**并**按住决策层**（不许 LLM 派活 ——
 * 被测的正是"没有任务"那条路径）；④ 把玩家传到观察点、把该看什么打进聊天栏与日志。
 *
 * <h2>应当看到什么（期望）</h2>
 * <pre>
 * ① bot 立刻上浮（`[Survival] **无任务**时人在水下…⇒ 立刻上浮自救`）；
 * ② 头露出水面后**自己走向平台**（`[Survival] **无任务**时人在水里 ⇒ 找岸（**纯通行**、零写权）：
 *    shore=… —— 启动 SurvivalExitTask`）；
 * ③ 走上干格后**停住**（任务 `COMPLETED`，人不再泡在水里）；
 * ④ **一块方块都没放、也没挖**（纯通行）。
 * </pre>
 * ⚠️ 场景是"空中平台"：四周至少 2 格全空气 ⇒ **干格只有平台边缘那一圈**，
 * 不会跟自然地形连在一起（技能 §2 的孤立长方体规则）。
 */
public final class ShoreEscapeFixture {

    /**
     * 场景中心（**固定坐标**：平台脚位层的中心格）。与 {@link #describe()} 的说明一致。
     *
     * <p>选在远处（1500 一带）以免与既有测试场景（`0,64,0` 挖矿 / `0,64,66` 蹚水 / `64,64,102` 维生 …）
     * 相互污染。
     */
    public static final BlockPos CENTER = new BlockPos(1500, 64, 1500);

    /** 平台半宽（`dx,dz ∈ [-4,4]` ⇒ 9×9）。 */
    public static final int PLATFORM_R = 4;
    /** 水池半宽（`dx,dz ∈ [-1,1]` ⇒ 3×3）；池底在脚位层之下 {@link #POOL_DEPTH} 格。 */
    public static final int POOL_R = 1;
    /** 水池深度（格）：脚位层 + 下面 2 格都是水，再下一格是池底石头。 */
    public static final int POOL_DEPTH = 3;
    /** 清出空气的余量（**孤立**：平台外至少 2 圈空气）。 */
    public static final int CLEAR_MARGIN = 2;
    /** 平台上方的净空（格）。 */
    public static final int HEADROOM = 3;
    /** 按住决策层的时长（tick；≈ 1 分钟，足够用户观察）。 */
    public static final int SUSPEND_TICKS = 1200;

    /** bot 的入池格（池底那一格，**在水里**）。 */
    public static final BlockPos POOL_BOT_FOOT = CENTER.below(POOL_DEPTH - 1);
    /** 玩家的观察点（平台角上，离池边 3 格）。 */
    public static final BlockPos OBSERVE_FOOT = CENTER.offset(PLATFORM_R, 0, PLATFORM_R);

    private ShoreEscapeFixture() {
    }

    /** 人类可读的场景说明（进日志与聊天栏；与上面的常量逐条对齐）。 */
    public static String describe() {
        return "平台 " + (2 * PLATFORM_R + 1) + "×" + (2 * PLATFORM_R + 1) + " 中心 " + CENTER.toShortString()
                + "；水池 " + (2 * POOL_R + 1) + "×" + (2 * POOL_R + 1) + "×" + POOL_DEPTH + "（池底 "
                + CENTER.below(POOL_DEPTH).toShortString() + "）；入池格 " + POOL_BOT_FOOT.toShortString()
                + "；观察点 " + OBSERVE_FOOT.toShortString();
    }

    /**
     * 建场景 + 就位 + 武装。
     *
     * @return 机器可读的一行武装记录（也直接进日志）；失败 ⇒ 抛 {@link IllegalStateException}
     *         （命令层如实回失败，不假装武装成功）
     */
    public static String arm(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        int cleared = buildScene(level);
        BotPlayer bot = BotManager.firstInLevel(level);
        if (bot == null) {
            bot = BotManager.firstOrSpawn(level, POOL_BOT_FOOT);
        }
        if (bot == null) {
            throw new IllegalStateException("没有可用的 bot（生成失败）");
        }
        // ⭐ 被测路径要求**没有任务**：有就先干净停掉（手动测试入口 ⇒ 允许）
        boolean stopped = false;
        if (BotManager.hasTask(bot)) {
            BotManager.stopTask(bot, "shore_escape_test");
            stopped = true;
        }
        // 按住决策层：否则 LLM 可能给它派活，那测的就不是"无任务"那条路径了
        GoalDirector.suspend(bot, SUSPEND_TICKS);
        // 入池（**池底那一格**：整只在没入水中 ⇒ 走 `D-380` 的"立刻上浮"）
        bot.teleportTo(level, POOL_BOT_FOOT.getX() + 0.5D, POOL_BOT_FOOT.getY(), POOL_BOT_FOOT.getZ() + 0.5D,
                java.util.Set.of(), bot.getYRot(), bot.getXRot());
        bot.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        bot.controller().stopMovement();
        // 玩家到观察点（看得见水池与 bot）
        player.teleportTo(level, OBSERVE_FOOT.getX() + 0.5D, OBSERVE_FOOT.getY(), OBSERVE_FOOT.getZ() + 0.5D,
                java.util.Set.of(), player.getYRot(), player.getXRot());

        String armed = "scene=" + CENTER.toShortString() + " bot=" + bot.getName().getString()
                + " poolFoot=" + POOL_BOT_FOOT.toShortString() + " 清出非空气=" + cleared
                + " 停掉旧任务=" + stopped + " 决策层按住=" + SUSPEND_TICKS + "tick"
                + " hasTask=" + BotManager.hasTask(bot);
        BotLog.warn("[ShoreTest] armed {}", armed);
        return armed;
    }

    /** 清出孤立空气 + 建平台 + 建水池；返回"清掉的非空气方块数"（如实记账，不静默覆盖）。 */
    private static int buildScene(ServerLevel level) {
        int cleared = 0;
        Map<BlockPos, BlockState> touched = new LinkedHashMap<>();
        // ① 先清（**含平台外 2 圈** ⇒ 孤立长方体；这一步是"覆盖别人的东西"⇒ 如实计数并上报）
        for (int dx = -(PLATFORM_R + CLEAR_MARGIN); dx <= PLATFORM_R + CLEAR_MARGIN; dx++) {
            for (int dz = -(PLATFORM_R + CLEAR_MARGIN); dz <= PLATFORM_R + CLEAR_MARGIN; dz++) {
                for (int dy = -POOL_DEPTH; dy <= HEADROOM; dy++) {
                    BlockPos pos = CENTER.offset(dx, dy, dz);
                    if (!level.getBlockState(pos).isAir()) {
                        cleared++;
                    }
                    touched.put(pos, level.getBlockState(pos));
                    level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
                }
            }
        }
        // ② 平台（脚位层的支撑层 = 石头；池子那几列除外）
        for (int dx = -PLATFORM_R; dx <= PLATFORM_R; dx++) {
            for (int dz = -PLATFORM_R; dz <= PLATFORM_R; dz++) {
                boolean inPool = Math.abs(dx) <= POOL_R && Math.abs(dz) <= POOL_R;
                if (!inPool) {
                    level.setBlock(CENTER.offset(dx, -1, dz), Blocks.STONE.defaultBlockState(), 3);
                }
            }
        }
        // ③ 水池：3 深全是水 + 池底石头
        for (int dx = -POOL_R; dx <= POOL_R; dx++) {
            for (int dz = -POOL_R; dz <= POOL_R; dz++) {
                for (int d = 0; d < POOL_DEPTH; d++) {
                    level.setBlock(CENTER.offset(dx, -d, dz), Blocks.WATER.defaultBlockState(), 3);
                }
                level.setBlock(CENTER.offset(dx, -POOL_DEPTH, dz), Blocks.STONE.defaultBlockState(), 3);
            }
        }
        // ④ 自证：平台可站、池子灌满、池底实心（前提先生效再武装 —— 免得用户对着坏场景看半天）
        boolean rimOk = com.dddgn.alice.pathing.MovementHelper
                .canStandCentered(level, CENTER.offset(PLATFORM_R, 0, 0));
        boolean waterOk = java.util.stream.IntStream.range(0, POOL_DEPTH)
                .allMatch(d -> level.getBlockState(CENTER.below(d)).is(Blocks.WATER));
        boolean floorOk = !level.getBlockState(CENTER.below(POOL_DEPTH)).isAir();
        BotLog.warn("[ShoreTest] 场景自证 rim可站={} 池灌满={} 池底实心={} 清出={} 描述={}",
                rimOk, waterOk, floorOk, cleared, describe());
        if (!rimOk || !waterOk || !floorOk) {
            throw new IllegalStateException("场景没建好（rim可站=" + rimOk + " 池灌满=" + waterOk
                    + " 池底实心=" + floorOk + "）");
        }
        return cleared;
    }
}
