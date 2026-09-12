package com.dddgn.alice.task;

import com.dddgn.alice.action.WriteGrant;
import com.dddgn.alice.action.WriteReason;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.item.FixtureToolKit;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.perception.ScopeBuffer;
import com.dddgn.alice.task.mining.MiningBudget;
import com.dddgn.alice.task.mining.MiningPlanner;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * **挖掘前流体风险自检**（{@code alice:fluid_mine_check}，S-4 / P0-C）：一次右键跑两个用例。
 *
 * <p>验的是那条一直没接上的检查：**目标格本身不是岩浆，但邻格有岩浆源 ⇒ 挖穿后岩浆会涌进来**。
 * 它和 D-037（Movement 通行性：身体别**进**岩浆、流体不可挖）**不重叠** —— 那条管"别走进去"，
 * 这条管"挖穿之后会不会涌进来"。
 *
 * <pre>
 * A 目标正下方是岩浆源 ⇒ `MiningPlanner` 必须**硬拒**（reason=fluid_risk_lava），
 *   且 MineTask 不许再去"清障/加高"（clearedBlocks/gainedSteps 必须都是 0）
 * B 正对照：同一平台上普通石头目标 ⇒ 正常挖完并入包（证明检查不会误伤正常挖掘）
 * </pre>
 */
public class FluidMineCheckTask implements Task {

    /** 场景常量（与 `alice_test:fluid_mine_course` 对齐）。 */
    public static final BlockPos STAND_FOOT = new BlockPos(64, 64, 104);
    /** 目标：正下方（y=63）是岩浆源的那一格石头。 */
    public static final BlockPos TARGET_OVER_LAVA = new BlockPos(66, 64, 104);
    /** 正对照目标：平台上的普通石头。 */
    public static final BlockPos TARGET_NORMAL = new BlockPos(64, 64, 106);

    private static final int RUN_BUDGET_TICKS = 1200;

    private final BotPlayer bot;
    private final ServerPlayer observer;
    private final ScopeBuffer scope;

    private enum Phase { SETUP, RUN_REFUSE, RUN_CONTROL, DONE }

    private Phase phase = Phase.SETUP;
    private int ticks;
    private MineTask inner;
    /** 规划层断言：探针必须**在任何站位/隧道规划之前**就拒掉。 */
    private String planRefuse = "-";
    /** 任务层断言：MineTask 必须硬拒，且**没有**清障/加高。 */
    private String runRefuse = "-";
    private String controlStatus = "-";

    public FluidMineCheckTask(BotPlayer bot, ServerPlayer observer, ScopeBuffer scope) {
        this.bot = bot;
        this.observer = observer;
        this.scope = scope;
    }

    @Override
    public String taskName() {
        return "FluidMineCheck";
    }

    @Override
    public TaskTarget target() {
        return TaskTarget.block(TARGET_OVER_LAVA);
    }

    @Override
    public String failureReason() {
        return passed() ? "" : "fluid_mine_check_failed";
    }

    @Override
    public Status tick() {
        if (++ticks > RUN_BUDGET_TICKS) {
            BotLog.warn("[FluidMineCheck] 超时 phase={} ticks={}", phase, ticks);
            return finish("timeout");
        }
        return switch (phase) {
            case SETUP -> setup();
            case RUN_REFUSE -> runRefuse();
            case RUN_CONTROL -> runControl();
            case DONE -> passed() ? Status.DONE : Status.FAILED;
        };
    }

    private Status setup() {
        ServerLevel level = bot.serverLevel();
        var server = level.getServer();
        var source = server.createCommandSourceStack().withSuppressedOutput();
        server.getCommands().performPrefixedCommand(source, "function alice_test:fluid_mine_course");
        bot.teleportTo(level, STAND_FOOT.getX() + 0.5D, STAND_FOOT.getY(), STAND_FOOT.getZ() + 0.5D,
                java.util.Set.of(), bot.getYRot(), bot.getXRot());
        bot.setDeltaMovement(Vec3.ZERO);
        bot.controller().stopMovement();
        FixtureToolKit.resetInventory(bot);
        // 夹具发料（D-122：发料放任务里，不只放物品里）
        FixtureToolKit.ensurePickaxe(bot);
        FixtureToolKit.ensureHotbarStack(bot, () -> new ItemStack(Items.COBBLESTONE),
                stack -> stack.is(Items.COBBLESTONE), 4, "cobblestone");
        scope.begin(TARGET_OVER_LAVA, 12, bot.getUUID());
        // 用例 A 的**规划层**断言：探针必须在任何站位/隧道规划之前就拒掉
        MiningPlanner.Result planned = new MiningPlanner().plan(bot, TARGET_OVER_LAVA,
                MiningBudget.forTarget(bot, level, TARGET_OVER_LAVA, false));
        planRefuse = planned.success() ? "PLANNED(未拒绝!)" : planned.failureReason();
        BotLog.info("[FluidMineCheck] A 规划层 target={} success={} reason={}",
                TARGET_OVER_LAVA.toShortString(), planned.success(), planRefuse);
        phase = Phase.RUN_REFUSE;
        return Status.RUNNING;
    }

    /** 用例 A：真的跑一遍 MineTask，断言"硬拒 + 没有清障/加高"。 */
    private Status runRefuse() {
        if (inner == null) {
            inner = new MineTask(bot, TARGET_OVER_LAVA, scope,
                    MiningBudget.forTarget(bot, bot.serverLevel(), TARGET_OVER_LAVA, false),
                    com.dddgn.alice.task.mining.MiningProfile.TUNNEL_ALLOWED,
                    WriteGrant.of(taskName(), WriteReason.EXPECTED_TARGET));
        }
        Status status = inner.tick();
        if (status == Status.RUNNING) {
            return Status.RUNNING;
        }
        String reason = inner.failureReason() == null ? "" : inner.failureReason();
        BotLog.info("[FluidMineCheck] A 终态 status={} reason={} cleared={} gained={}",
                status, reason, inner.clearedBlocks(), inner.gainedSteps());
        boolean hardRefused = status == Status.FAILED && reason.contains("fluid_risk_lava");
        boolean noClearNoGain = inner.clearedBlocks() == 0 && inner.gainedSteps() == 0;
        runRefuse = (hardRefused ? "FAILED_fluid_risk_lava" : status + "/" + reason)
                + " no_clear_gain=" + noClearNoGain;
        inner = null;
        phase = Phase.RUN_CONTROL;
        return Status.RUNNING;
    }

    /** 用例 B：正对照 —— 普通石头目标必须正常挖完。 */
    private Status runControl() {
        if (inner == null) {
            inner = new MineTask(bot, TARGET_NORMAL, scope,
                    MiningBudget.forTarget(bot, bot.serverLevel(), TARGET_NORMAL, false),
                    com.dddgn.alice.task.mining.MiningProfile.TUNNEL_ALLOWED,
                    WriteGrant.of(taskName(), WriteReason.EXPECTED_TARGET));
        }
        Status status = inner.tick();
        if (status == Status.RUNNING) {
            return Status.RUNNING;
        }
        controlStatus = status.name() + (status == Status.DONE ? "" : "/" + inner.failureReason());
        BotLog.info("[FluidMineCheck] B 正对照 status={} reason={}", status, inner.failureReason());
        inner = null;
        phase = Phase.DONE;
        return finish(controlStatus);
    }

    private Status finish(String control) {
        phase = Phase.DONE;
        boolean pass = passed();
        String summary = "plan_refuse=" + planRefuse + " run_refuse=" + runRefuse
                + " control=" + control + " → " + (pass ? "PASS" : "FAIL");
        BotLog.info("[FluidMineCheck] SUMMARY {}", summary);
        if (observer != null) {
            observer.sendSystemMessage(Component.literal("[alice] 挖掘流体风险自检 " + summary));
        }
        return pass ? Status.DONE : Status.FAILED;
    }

    private boolean passed() {
        return "fluid_risk_lava".equals(planRefuse)
                && runRefuse.contains("FAILED_fluid_risk_lava")
                && runRefuse.contains("no_clear_gain=true")
                && controlStatus.equals("DONE");
    }
}
