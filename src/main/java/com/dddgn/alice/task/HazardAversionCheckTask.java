package com.dddgn.alice.task;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.pathing.core.search.CorePathPlanner;
import com.dddgn.alice.pathing.core.search.PathPlan;
import com.dddgn.alice.pathing.core.search.PathRequest;
import com.dddgn.alice.pathing.core.search.SearchBudget;
import com.dddgn.alice.pathing.risk.RiskProfile;
import com.dddgn.alice.pathing.risk.RiskSwitches;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * **`hazardAversion` 的判据（D-292，2026-09-17 用户裁定"先加"）** —— **只规划不执行** ⇒ 全程不碰危险 ✓。
 *
 * <p>场景（`alice_test:hazard_route_course_terrain`）：起点 `(64,64,242)` → 终点 `(64,64,262)`；
 * **直行道**在 x=64，其**旁边**（x=65）在 z 250..252 有**熔岩**（脚位同层 ⇒ 邻接 ✓）；
 * 另有一条**安全绕行道**（x=68，两端各一条连接段 ✓）。直路走 3~4 个"贴着熔岩"的格子
 * ⇒ 厌恶打开时该路要付 3~4 × 20 = 60~80 的加价 ✓，而绕行只多约 8 格 ⇒ **规划器应当绕开** ✓。
 *
 * <p>三次规划（每次都要**重新冻结画像**才生效 ✓ —— 这正是 S-6 的语义）：
 * <ol>
 *   <li>`hazardAversion=false` ⇒ 记录 `totalCost` 与"绕行道格数"；</li>
 *   <li>`hazardAversion=true` ⇒ **必须**绕开：`totalCost` 明显更高（付了加价）**且**绕行道格数更多 ✓ ← **判据**；</li>
 *   <li>复位后 ⇒ 回到与 ① 相同的选择（收尾自证 ✓）。</li>
 * </ol>
 * **反向对照**：把 `MovementContext` 里的危险加价去掉 ⇒ ②与①完全相同 ⇒ 本夹具红 ✓。
 */
public class HazardAversionCheckTask implements Task {

    public static final BlockPos START = new BlockPos(64, 64, 242);
    public static final BlockPos GOAL = new BlockPos(64, 64, 262);
    /** 绕行道的 x 坐标（x≥66 视为"走了绕行" ✓）。 */
    private static final int DETOUR_X = 66;

    private final BotPlayer bot;
    private final List<String> failures = new ArrayList<>();
    private int checks;
    private int phase;
    private boolean done;
    private int ticks;
    private double costOff;
    private double costOn;
    private int detourOff;
    private int detourOn;
    private int hazardOff;
    private int hazardOn;

    public HazardAversionCheckTask(BotPlayer bot) {
        this.bot = bot;
    }

    @Override
    public String taskName() {
        return "HazardAversionCheck";
    }

    @Override
    public TaskTarget target() {
        return TaskTarget.block(GOAL);
    }

    @Override
    public String failureReason() {
        return failures.isEmpty() ? "" : String.join(" | ", failures);
    }

    @Override
    public String terminalReason() {
        return done ? (failures.isEmpty() ? "passed" : "failed") : "";
    }

    @Override
    public Status tick() {
        if (done) {
            return failures.isEmpty() ? Status.DONE : Status.FAILED;
        }
        switch (phase) {
            case 0 -> {
                // **自己装场景**（R-2 纪律）：先让 bot 站到起点把区块**热起来**，再跑场景函数
                // （区块冷时 `/fill` 不落地 ⇒ 判据会在虚空里假绿 ✗ —— 电池源码里记过这个坑 ✓）。
                bot.teleportTo(bot.serverLevel(), START.getX() + 0.5D, START.getY(), START.getZ() + 0.5D,
                        java.util.Set.of(), bot.getYRot(), bot.getXRot());
                bot.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
                dispatch("function alice_test:hazard_route_course_terrain");
                phase = 1;
                return Status.RUNNING;
            }
            case 1 -> {
                if (ticks++ < 4) {
                    return Status.RUNNING;   // 给 `/fill` 一点落定时间
                }
                // 场景建好后重新站到起点（场景会把上方清成空气 ⇒ 需要重新落地）
                bot.teleportTo(bot.serverLevel(), START.getX() + 0.5D, START.getY(), START.getZ() + 0.5D,
                        java.util.Set.of(), bot.getYRot(), bot.getXRot());
                bot.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
                check("前提：bot 在场景起点附近（实测 " + bot.blockPosition().toShortString() + "）",
                        bot.blockPosition().distManhattan(START) <= 3);
                RiskSwitches.set(RiskSwitches.HAZARD_AVERSION, false);
                RiskProfile.freeze(bot);
                PathPlan off = plan();
                costOff = off.totalCost();
                detourOff = detourCells(off);
                hazardOff = hazardAdjacentCells(off);
                check("① 厌恶关：规划成功（status=" + off.status() + "）", off.movements() != null
                        && !off.movements().isEmpty());
                RiskSwitches.set(RiskSwitches.HAZARD_AVERSION, true);
                RiskProfile.freeze(bot);   // 关键：改开关后必须重新冻结（S-6 语义 ✓）
                phase = 2;
            }
            case 2 -> {
                PathPlan on = plan();
                costOn = on.totalCost();
                detourOn = detourCells(on);
                hazardOn = hazardAdjacentCells(on);
                // **判据**：厌恶打开后必须"付了加价"且"绕得更远"
                check("② 厌恶开：路线里**不再有贴着危险的格子**（off=" + hazardOff + " 格 on=" + hazardOn + " 格，期望 on=0）",
                        hazardOn == 0 && hazardOff > 0);
                check("② 厌恶开：代价确实变了（off=" + String.format(java.util.Locale.ROOT, "%.2f", costOff)
                                + " on=" + String.format(java.util.Locale.ROOT, "%.2f", costOn) + "）",
                        Math.abs(costOn - costOff) > 0.5D);
                check("② 厌恶开：改走绕行道（off=" + detourOff + " 格 on=" + detourOn + " 格）",
                        detourOn > detourOff);
                // 收尾：复位开关与画像
                RiskSwitches.set(RiskSwitches.HAZARD_AVERSION, false);
                RiskProfile.freeze(bot);
                PathPlan back = plan();
                check("③ 复位后回到与①相同的选择（cost=" 
                                + String.format(java.util.Locale.ROOT, "%.2f", back.totalCost()) + "）",
                        Math.abs(back.totalCost() - costOff) < 0.001D);
                BotLog.info("[HazardAversion] 观测：off(cost={} 贴危险={} 绕行={}) on(cost={} 贴危险={} 绕行={}) restored={}",
                        String.format(java.util.Locale.ROOT, "%.2f", costOff), hazardOff, detourOff,
                        String.format(java.util.Locale.ROOT, "%.2f", costOn), hazardOn, detourOn,
                        String.format(java.util.Locale.ROOT, "%.2f", back.totalCost()));
                done = true;
                boolean pass = failures.isEmpty();
                BotLog.info("[HazardAversion] SUMMARY checks={} failures={} {} → {}",
                        checks, failures.size(), failures, pass ? "PASS" : "FAIL");
                return Status.RUNNING;
            }
            default -> {
                return Status.RUNNING;
            }
        }
        return Status.RUNNING;
    }

    /** **只规划，不执行**（与各诊断任务同一条路径 ✓）。 */
    private PathPlan plan() {
        PathRequest base = PathRequest.withWorldModification(bot.getUUID().toString(), START, GOAL,
                "hazard-aversion-diagnostic");
        PathRequest request = new PathRequest(base.botId(), START, base.goal(), base.allowedMovementTypes(),
                SearchBudget.of(CorePathPlanner.DEFAULT_MAX_NODES, CorePathPlanner.DEFAULT_MAX_MILLIS),
                base.requester());
        return new CorePathPlanner().plan(bot, bot.serverLevel(), request);
    }

    /** 规划出的落点里有多少个**贴着危险方块**（与 `MovementHelper.avoidWalkingInto` 同口径 ✓）。 */
    private int hazardAdjacentCells(PathPlan plan) {
        if (plan.projectedFootPath() == null) {
            return 0;
        }
        int count = 0;
        for (BlockPos pos : plan.projectedFootPath()) {
            boolean hit = false;
            for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.Plane.HORIZONTAL) {
                hit |= com.dddgn.alice.pathing.MovementHelper.avoidWalkingInto(
                        bot.serverLevel().getBlockState(pos.relative(dir)));
            }
            if (hit) {
                count++;
            }
        }
        return count;
    }

    /** 规划出的落点里有多少个落在绕行道侧（x ≥ 66）⇒ "绕得有多远"的可比数字 ✓。 */
    private int detourCells(PathPlan plan) {
        if (plan.projectedFootPath() == null) {
            return 0;
        }
        int count = 0;
        for (BlockPos pos : plan.projectedFootPath()) {
            if (pos.getX() >= DETOUR_X) {
                count++;
            }
        }
        return count;
    }

    /** 以控制台身份跑一条数据包命令（场景自备 ⇒ 不依赖前序模块 ✓）。 */
    private void dispatch(String command) {
        var server = bot.serverLevel().getServer();
        server.getCommands().performPrefixedCommand(
                server.createCommandSourceStack().withSuppressedOutput(), command);
    }

    private void check(String what, boolean ok) {
        checks++;
        if (!ok) {
            failures.add(what);
        }
    }
}
