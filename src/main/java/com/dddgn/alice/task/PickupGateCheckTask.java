package com.dddgn.alice.task;

import com.dddgn.alice.bot.BotManager;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.decision.DropPolicy;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.task.mining.MiningProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * **被动拾取闸门自检**（S3.5 / D-143）：两个用例，验"路过时该捡的捡、不该捡的不捡"。
 *
 * <pre>
 * A 我方掉落物（收养 ⇒ OURS_DIRECT）⇒ bot 走过去 ⇒ **应进背包**（策略 AUTO）
 * B 外来掉落物（不登记 ⇒ FOREIGN）  ⇒ bot 走过去 ⇒ **[Pickup] blocked**，东西**仍留在地上**（策略 ASK⇒拦截）
 * </pre>
 *
 * <p>为什么这两条必须一起测：只测 A 无法证明闸门存在（原版就会捡），只测 B 无法证明没把正常拾取弄坏。
 */
public class PickupGateCheckTask implements Task {

    /** 掉落物落点（bot 起点东侧 4 格，同平台）。 */
    public static final BlockPos DROP_A = com.dddgn.alice.task.LumberCourseAnchor.START_FOOT.offset(4, 0, 0);
    /** 外来掉落物落点（更远一点，避免两批混在一起）。 */
    public static final BlockPos DROP_B = com.dddgn.alice.task.LumberCourseAnchor.START_FOOT.offset(6, 0, 0);

    private enum Phase { SETUP, WALK_A, ASSERT_A, WALK_B, ASSERT_B, DONE }

    private final BotPlayer bot;
    private final ServerPlayer observer;

    private Phase phase = Phase.SETUP;
    private int ticks;
    private int itemsBefore;
    private int aPicked;
    private int bRemaining;
    private String note = "-";

    public PickupGateCheckTask(BotPlayer bot, ServerPlayer observer) {
        this.bot = bot;
        this.observer = observer;
    }

    @Override
    public String taskName() {
        return "PickupGateCheck";
    }

    @Override
    public TaskTarget target() {
        return TaskTarget.block(DROP_B);
    }

    @Override
    public String failureReason() {
        return passed() ? "" : "pickup_gate_check_failed";
    }

    @Override
    public String terminalReason() {
        return phase == Phase.DONE ? (passed() ? "passed" : "failed") : "";
    }

    @Override
    public Status tick() {
        if (++ticks > 1200) {
            return finish("timeout");
        }
        return switch (phase) {
            case SETUP -> setup();
            case WALK_A -> walkA();
            case ASSERT_A -> assertA();
            case WALK_B -> walkB();
            case ASSERT_B -> assertB();
            case DONE -> passed() ? Status.DONE : Status.FAILED;
        };
    }

    private Status setup() {
        ServerLevel level = bot.serverLevel();
        var server = level.getServer();
        var source = server.createCommandSourceStack().withSuppressedOutput();
        server.getCommands().performPrefixedCommand(source, "function alice_test:lumber_course");
        bot.teleportTo(level, com.dddgn.alice.task.LumberCourseAnchor.START_FOOT.getX() + 0.5D,
                com.dddgn.alice.task.LumberCourseAnchor.START_FOOT.getY(),
                com.dddgn.alice.task.LumberCourseAnchor.START_FOOT.getZ() + 0.5D,
                java.util.Set.of(), bot.getYRot(), bot.getXRot());
        bot.setDeltaMovement(Vec3.ZERO);
        bot.controller().stopMovement();
        var session = BotManager.sessionOf(bot);
        if (session == null) {
            return finish("no_session");
        }
        session.scope().begin(DROP_A, 16, bot.getUUID());
        // A：我方掉落物（显式收养 ⇒ OURS_DIRECT）
        spawn(level, DROP_A, 1);
        int adopted = session.scope().adoptExistingDrops(level, DROP_A, 4);
        BotLog.info("[PickupGateCheck] A 造物：我方掉落物 1 堆（adopted={}）", adopted);
        // B：外来掉落物（**不登记** ⇒ FOREIGN）
        spawn(level, DROP_B, 1);
        itemsBefore = countNear(level, DROP_B, 2.0D);
        BotLog.info("[PickupGateCheck] B 造物：外来掉落物 1 堆（未登记 ⇒ FOREIGN），命中数={}", itemsBefore);
        phase = Phase.WALK_A;
        return Status.RUNNING;
    }

    private void spawn(ServerLevel level, BlockPos pos, int stacks) {
        for (int i = 0; i < stacks; i++) {
            ItemEntity drop = new ItemEntity(level, pos.getX() + 0.5D + i * 0.3D, pos.getY() + 0.5D,
                    pos.getZ() + 0.5D, new ItemStack(Items.COBBLESTONE, 4));
            drop.setDeltaMovement(Vec3.ZERO);
            level.addFreshEntity(drop);
        }
    }

    private int countNear(ServerLevel level, BlockPos pos, double radius) {
        return level.getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(radius),
                ItemEntity::isAlive).size();
    }

    private int inventoryCobblestone() {
        int total = 0;
        var inventory = bot.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            var stack = inventory.getItem(slot);
            if (!stack.isEmpty() && stack.is(Items.COBBLESTONE)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private Status walkA() {
        if (ticks % 40 == 1) {
            BotLog.info("[PickupGateCheck] A 走向我方掉落物 {}…（当前圆石={}）",
                    DROP_A.toShortString(), inventoryCobblestone());
        }
        return walkTo(DROP_A, Phase.ASSERT_A);
    }

    private Status assertA() {
        aPicked = inventoryCobblestone();
        BotLog.info("[PickupGateCheck] A 断言：走到我方掉落物上 ⇒ 背包圆石={}（应 >0，策略 AUTO 放行）", aPicked);
        phase = Phase.WALK_B;
        return Status.RUNNING;
    }

    private Status walkB() {
        if (ticks % 40 == 1) {
            BotLog.info("[PickupGateCheck] B 走向外来掉落物 {}…", DROP_B.toShortString());
        }
        return walkTo(DROP_B, Phase.ASSERT_B);
    }

    private Status assertB() {
        bRemaining = countNear(bot.serverLevel(), DROP_B, 2.0D);
        BotLog.info("[PickupGateCheck] B 断言：走到外来掉落物上 ⇒ 地上还剩 {} 堆（应 >0，策略 ASK 在被动路径上拦截）",
                bRemaining);
        phase = Phase.DONE;
        return finish(passed() ? "passed" : "failed");
    }

    /** 用**已验收的** WalkToTask 走过去（不挖不放置），到点后再断言。 */
    private Status walkTo(BlockPos goal, Phase next) {
        if (runner == null) {
            runner = new WalkToTask(bot, goal);
        }
        Status status = runner.tick();
        if (status == Status.RUNNING) {
            return Status.RUNNING;
        }
        runner = null;
        phase = next;
        return Status.RUNNING;
    }

    private WalkToTask runner;
    private boolean finished;

    private Status finish(String reason) {
        phase = Phase.DONE;
        if (finished) {
            return passed() ? Status.DONE : Status.FAILED;
        }
        finished = true;
        boolean pass = passed();
        note = reason;
        String summary = "a_picked=" + aPicked + " b_remaining=" + bRemaining + " reason=" + reason
                + " → " + (pass ? "PASS" : "FAIL");
        BotLog.info("[PickupGateCheck] SUMMARY {}（{}）", summary, DropPolicy.describeWindow());
        if (observer != null && !observer.hasDisconnected() && !observer.isRemoved()) {
            observer.sendSystemMessage(Component.literal("[alice] 被动拾取闸门自检 " + summary));
        }
        return pass ? Status.DONE : Status.FAILED;
    }

    private boolean passed() {
        return aPicked > 0 && bRemaining > 0 && Phase.DONE == phase;
    }
}
