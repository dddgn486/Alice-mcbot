package com.dddgn.alice.task;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.log.BotLog;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;

/**
 * **维生逃生任务**（S-1 / P1-C）：走到维生系统给出的最近安全落点。
 *
 * <p>为什么直接复用 {@link WalkToTask} 而不是新写一套移动：用户裁定过的分工是
 * 「**维生给落点、寻路给路径**」——`SurvivalSystem.nearestSafeRefuge` 只做纯查询，
 * 真正的移动交给**已经客户端验收**的硬路径任务（`PathRequest.of` 纯通行，不挖不放置，
 * D-076 红线不受影响）。
 *
 * <p>实现为独立类（而不是 `WalkToTask` 加个布尔）是为了让**终态记录里能一眼看出这是逃生回合**：
 * `task_execution_terminal kind=SurvivalExitTask …`，日志里也能和普通 `WalkTo` 区分开。
 *
 * <p><b>⭐ 空气告警</b>（`D-383`，2026-09-21 真机第十二轮）：逃生**路线本身**可能要求钻水下
 * —— 真机实测（冰坑 + 狭长通道）第 1 次逃生 `eyeInWater=true`、`air 284→…→158`（约 7 秒），
 * 而逃生任务带 {@link SurvivalExit} 标记 ⇒ 维生那三条救援分支**全被 `!escapeTask` 排除**、
 * 判决恒 `IGNORE` ⇒ **逃生途中维生零动作**（是它自己游上来才没淹死）。
 * ⇒ 本类自己带一条告警：**眼在水里 + 空气低 ⇒ 按住跳跃**（见 {@link #assistSurfacingIfAirLow()}）。
 */
public final class SurvivalExitTask extends WalkToTask implements SurvivalExit {

    /** 是否动用**逃生准备金**（D-241）：只有"信封本来就有写授权"的任务才允许为 true。 */
    private final boolean withReserve;

    /** 空气告警是否**正在**按住跳跃（用来把"进入/退出"各记一次日志，不刷屏）。 */
    private boolean surfacingAssist;

    public SurvivalExitTask(BotPlayer bot, BlockPos refugeFoot) {
        this(bot, refugeFoot, false);
    }

    public SurvivalExitTask(BotPlayer bot, BlockPos refugeFoot, boolean withReserve) {
        super(bot, refugeFoot);
        this.withReserve = withReserve;
    }

    /**
     * **逃生用哪个请求**：默认仍是纯通行；只有当"纯通行去不了、而信封允许改世界"时，
     * 才换成 {@code PathRequest.survivalEscape}（放置 + 破坏 + PILLAR，理由码 `ESCAPE_*`，上限 8/8）。
     */
    @Override
    protected com.dddgn.alice.pathing.core.search.PathRequest buildRequest(BotPlayer walker, BlockPos goal) {
        if (!withReserve) {
            return super.buildRequest(walker, goal);
        }
        return com.dddgn.alice.pathing.core.search.PathRequest.survivalEscape(
                walker.getUUID().toString(), com.dddgn.alice.pathing.MovementHelper.footCell(walker.serverLevel(), walker),
                goal, "survival-escape");
    }

    /**
     * `D-383`：空气告警此刻**是否正在按住跳跃**（只读；夹具断言用）。
     *
     * <p>⚠️ 夹具**不能**用 `bot.controller().isJumping()` 做这条判据：执行器自己也会在水里按跳跃
     * （`MovementHelper.shouldHoldJumpInWater`：目标脚位更高时）⇒ 两者混在一起就分不清是谁按的
     * （2026-09-21 首跑实测：相位 3 明明已经"解除"，`isJumping()` 仍是 true —— 那是执行器在爬出水池）。
     */
    public boolean airAlarmActive() {
        return surfacingAssist;
    }

    @Override
    public Status tick() {
        Status status = super.tick();
        // 走位照常推进**之后**再按空气告警覆盖跳跃输入（下一 tick 的物理生效；
        // 换顺序会被执行器这一 tick 的 `setJumping` 覆盖掉）。
        assistSurfacingIfAirLow();
        return status;
    }

    /**
     * ⭐ `D-383`：**逃生途中的空气告警** —— 眼在水里且空气 ≤ {@link SurvivalFloatTask#AIR_SAFE}
     * 时按住跳跃（原版：水里按跳跃 = 上浮）。
     *
     * <p><b>为什么不"暂停走位先浮"</b>：真机那条通道是**狭长水道**，1 格高时"浮不上去又不前进"
     * 就是**死锁**（空气照样归零）⇒ 这里只**加一个向上的输入**、走位一秒都不停：
     * 头一露出水面就开始回气；出不了水面也至少在做"往上 + 往前"的正确动作。
     *
     * <p><b>阈值为什么复用 {@link SurvivalFloatTask#AIR_SAFE}</b>：它就是本仓既有的"呼吸缓过来了"口径
     * （`D-237` 定的），**不新造数**；`AIR_SAFE=100` 相对满空气 300 留约 2/3 余量。
     *
     * <p><b>不在地面/水面按跳跃</b>：条件里带"眼在水里"⇒ 眼一出水立刻松开（与
     * {@code SurvivalFloatTask} 同一条纪律：长按跳跃在地面会变成兔子跳）。
     *
     * <p>⚠️ **诚实边界**：这条只把"零动作"变成"主动上浮尝试"。**完全封死的水下长通道**
     * （没有任何气口）仍然救不了 —— 那种路线的正确做法是**别选它**（空气预算/路线选择属于
     * 「水中逃生」计划的层 2/3，本轮不做）。
     */
    private void assistSurfacingIfAirLow() {
        BotPlayer bot = bot();
        boolean eyeInWater = bot.isEyeInFluid(FluidTags.WATER);
        if (!eyeInWater) {
            if (surfacingAssist) {
                surfacingAssist = false;
                BotLog.info("[Survival] 逃生途中空气告警**解除**（眼已出水，air={}）—— 松开跳跃",
                        bot.getAirSupply());
            }
            return;
        }
        int air = bot.getAirSupply();
        if (air > SurvivalFloatTask.AIR_SAFE) {
            if (surfacingAssist) {
                surfacingAssist = false;
                BotLog.info("[Survival] 逃生途中空气告警**解除**（air={} > {}）—— 松开跳跃",
                        air, SurvivalFloatTask.AIR_SAFE);
            }
            return;
        }
        if (!surfacingAssist) {
            surfacingAssist = true;
            BotLog.warn("[Survival] 逃生途中**空气告警**：眼在水里且 air={} ≤ {} ⇒ 按住跳跃上浮"
                            + "（`D-383`：逃生任务不被维生救援覆盖，所以这一档必须自带）goal={} pos={}",
                    air, SurvivalFloatTask.AIR_SAFE, target().blockPos().toShortString(),
                    bot.blockPosition().toShortString());
        }
        bot.controller().setJumping(true);
    }
}

