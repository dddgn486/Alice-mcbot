package com.dddgn.alice.task;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.fixture.transfer.TransferEndpointSelectorEventsFixture;
import com.dddgn.alice.fixture.transfer.TransferFixture;
import com.dddgn.alice.fixture.transfer.TransferSelectionCommandParseFixture;
import com.dddgn.alice.fixture.transfer.TransferSelectionFixture;
import com.dddgn.alice.log.BotLog;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * **传输模块自检**（R2，2026-09-13）：一次右键跑完传输的**全部 4 个夹具**，约 1~2 秒。
 *
 * <p>为什么要有它（用户："不太直观"）：传输此前**唯一**的测试入口是 `/alice selftest` ——
 * 一个命令触发、只见日志、且因 Mekanism 硬引用在无 Mek 客户端**必崩**的 legacy harness。
 * 本任务把它换成与其它模块同规格的**游戏内零参数入口 + 一行 SUMMARY**。
 *
 * <p>覆盖（全部来自原 selftest 的夹具，逻辑未变）：
 * <ul>
 *   <li>{@code fixture}：正常两段传输 + 容量拒绝 + 源不足 + 组件拒绝 + 端点拒绝 + 模拟冲突 +
 *       增量不一致（post-mismatch）+ 账本策略 + 任务中断策略 + 硬寻路码映射 + 预算码映射；</li>
 *   <li>{@code selection}：端点草稿（选择/取消/过期/跨维度/同端点拒绝）；</li>
 *   <li>{@code selector_events}：选择器物品的事件分发与"不干扰原版交互"；</li>
 *   <li>{@code command_parse}：`transfer-selection` 命令解析。</li>
 * </ul>
 */
public class TransferCheckTask implements Task {

    private final BotPlayer bot;
    private final ServerPlayer observer;
    private final List<String> failures = new ArrayList<>();
    private int ticks;
    private boolean done;
    private String note = "-";
    // ---- 端到端子任务（**必须跨真实 tick 推进**：bot 物理在服务器 tick 之间发生）----
    private Phase phase = Phase.FIXTURES;
    private Task endToEndTask;
    private int endToEndTicks;
    private boolean fixturePass;
    private boolean selectionPass;
    private boolean selectorEventsPass;
    private boolean commandParsePass;
    private boolean endToEndPass;
    private int endToEndMoved = -1;
    private boolean endToEndSourceEmpty;
    private static final int END_TO_END_BUDGET_TICKS = 300;

    private enum Phase { FIXTURES, END_TO_END, REPORT, DONE }

    public TransferCheckTask(BotPlayer bot, ServerPlayer observer) {
        this.bot = bot;
        this.observer = observer;
    }

    @Override
    public String taskName() {
        return "TransferCheck";
    }

    @Override
    public TaskTarget target() {
        return TaskTarget.block(bot.blockPosition());
    }

    @Override
    public String failureReason() {
        return failures.isEmpty() ? "" : String.join(",", failures);
    }

    @Override
    public String terminalReason() {
        return done ? (failures.isEmpty() ? "passed" : "failed") : "";
    }

    @Override
    public Status tick() {
        if (++ticks > 800) {
            return finish("timeout");
        }
        return switch (phase) {
            case FIXTURES -> runFixtures();
            case END_TO_END -> tickEndToEnd();
            case REPORT -> report();
            case DONE -> failures.isEmpty() ? Status.DONE : Status.FAILED;
        };
    }

    private Status runFixtures() {
        var level = bot.serverLevel();
        // **地形交给数据包场景函数**（2026-09-13 用户建议 + 项目规矩）：夹具只负责
        // "重建场景 → 传送 → 填内容 → 断言"。
        level.getServer().getCommands().performPrefixedCommand(
                level.getServer().createCommandSourceStack().withSuppressedOutput(),
                "function " + TransferCourseAnchor.SCENE_FUNCTION);
        bot.teleportTo(level, TransferCourseAnchor.BASE.getX() + 0.5D, TransferCourseAnchor.BASE.getY(),
                TransferCourseAnchor.BASE.getZ() + 0.5D, java.util.Set.of(), bot.getYRot(), bot.getXRot());
        bot.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        bot.controller().stopMovement();
        note = "scene=" + TransferCourseAnchor.SCENE_FUNCTION
                + " base=" + TransferCourseAnchor.BASE.toShortString();

        // 4 个**同步**夹具（不涉及真实走位：它们直接调原语或用接缝注入）
        fixturePass = guarded("fixture", () -> TransferFixture.run(level, bot,
                TransferCourseAnchor.FIXTURE_BASE));
        selectionPass = guarded("selection", () -> TransferSelectionFixture.run(level,
                TransferCourseAnchor.FIXTURE_BASE));
        selectorEventsPass = guarded("selector_events", TransferEndpointSelectorEventsFixture::run);
        commandParsePass = guarded("command_parse", TransferSelectionCommandParseFixture::run);
        // 端到端**跨真实 tick**推进（2026-09-13 实测教训：同一 tick 内的同步循环烧尽段预算，
        // 而 bot 的物理在服务器 tick 之间发生 ⇒ `SEGMENT_TIMEOUT actualFoot` 一格没动）
        if (!prepareEndToEnd(level, TransferCourseAnchor.BASE)) {
            endToEndPass = false;
        }
        phase = Phase.END_TO_END;
        return Status.RUNNING;
    }

    /** 每**真实** tick 推进端到端子任务一次（这就是它与"同步夹具"的根本区别）。 */
    private Status tickEndToEnd() {
        if (endToEndTask == null) {
            phase = Phase.REPORT;
            return Status.RUNNING;
        }
        endToEndTicks++;
        Task.Status status = endToEndTask.tick();
        if (status == Task.Status.RUNNING && endToEndTicks < END_TO_END_BUDGET_TICKS) {
            return Status.RUNNING;
        }
        endToEndPass = assertEndToEnd(status);
        BotLog.info("[Transfer] end_to_end status={} ticks={} moved={} sourceEmpty={} terminal={}",
                status, endToEndTicks, endToEndMoved, endToEndSourceEmpty,
                endToEndTask.terminalReason());
        endToEndTask = null;
        phase = Phase.REPORT;
        return Status.RUNNING;
    }

    private Status report() {
        String summary = "fixture=" + (fixturePass ? "PASS" : "FAIL")
                + " end_to_end=" + (endToEndPass ? "PASS" : "FAIL")
                + " selection=" + (selectionPass ? "PASS" : "FAIL")
                + " selector_events=" + (selectorEventsPass ? "PASS" : "FAIL")
                + " command_parse=" + (commandParsePass ? "PASS" : "FAIL")
                + " verdict=" + (failures.isEmpty() ? "PASS" : "FAIL");
        BotLog.info("[Transfer] SUMMARY {} {}", summary, note);
        if (observer != null) {
            observer.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "[Transfer] " + summary));
        }
        phase = Phase.DONE;
        return Status.RUNNING;
    }

    /**
     * **端到端**：放好两个箱子与源物品 ⇒ 跑真实 `TransferTask` 到终态 ⇒ 断言物品到了目标箱。
     *
     * <p>箱子放在 bot 东侧 2 格 / 东侧 2 格再偏 Z 3 格：**故意留出一两步路**，
     * 以便同时覆盖 L1 的三件事（选站点 → 走位 → 触及校验）与 R3 的容器预算消费。
     */
    private boolean prepareEndToEnd(net.minecraft.server.level.ServerLevel level, BlockPos base) {
        BlockPos source = TransferCourseAnchor.SOURCE;
        BlockPos destination = TransferCourseAnchor.DESTINATION;
        bot.teleportTo(level, base.getX() + 0.5D, base.getY(), base.getZ() + 0.5D,
                java.util.Set.of(), bot.getYRot(), bot.getXRot());
        bot.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        bot.controller().stopMovement();
        var sourceChest = (net.minecraft.world.level.block.entity.ChestBlockEntity)
                level.getBlockEntity(source);
        var destinationChest = (net.minecraft.world.level.block.entity.ChestBlockEntity)
                level.getBlockEntity(destination);
        if (sourceChest == null || destinationChest == null) {
            BotLog.warn("[Transfer] end_to_end 夹具前提不成立：场景函数未放置箱子（source={} destination={}）",
                    source.toShortString(), destination.toShortString());
            return false;
        }
        var inventory = bot.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            inventory.setItem(slot, net.minecraft.world.item.ItemStack.EMPTY);
        }
        for (int slot = 0; slot < sourceChest.getContainerSize(); slot++) {
            sourceChest.setItem(slot, net.minecraft.world.item.ItemStack.EMPTY);
        }
        for (int slot = 0; slot < destinationChest.getContainerSize(); slot++) {
            destinationChest.setItem(slot, net.minecraft.world.item.ItemStack.EMPTY);
        }
        sourceChest.setItem(0, new net.minecraft.world.item.ItemStack(
                net.minecraft.world.item.Items.IRON_INGOT, 3));
        sourceChest.setChanged();

        var ref = new com.dddgn.alice.transfer.ChestEndpointRef(level.dimension().location(), source);
        var destRef = new com.dddgn.alice.transfer.ChestEndpointRef(level.dimension().location(), destination);
        var request = new com.dddgn.alice.transfer.TransferRequest(java.util.UUID.randomUUID(),
                bot.getUUID(), bot.getUUID(), ref, destRef,
                net.minecraft.core.registries.BuiltInRegistries.ITEM
                        .getKey(net.minecraft.world.item.Items.IRON_INGOT),
                3, level.getGameTime());
        var ledger = com.dddgn.alice.transfer.TransferLedgerData.get(bot.getServer());
        ledger.admit(request);
        endToEndTask = new TransferTask(bot, request, ledger);
        endToEndTicks = 0;
        return true;
    }

    /** 端到端收尾断言：物品真的从源箱到了目标箱（记录实际数量供日志）。 */
    private boolean assertEndToEnd(Task.Status status) {
        var level = bot.serverLevel();
        endToEndMoved = 0;
        if (level.getBlockEntity(TransferCourseAnchor.DESTINATION)
                instanceof net.minecraft.world.level.block.entity.ChestBlockEntity destinationChest) {
            for (int slot = 0; slot < destinationChest.getContainerSize(); slot++) {
                var stack = destinationChest.getItem(slot);
                if (stack.is(net.minecraft.world.item.Items.IRON_INGOT)) {
                    endToEndMoved += stack.getCount();
                }
            }
        }
        endToEndSourceEmpty = level.getBlockEntity(TransferCourseAnchor.SOURCE)
                instanceof net.minecraft.world.level.block.entity.ChestBlockEntity sourceChest
                && sourceChest.getItem(0).isEmpty();
        if (status != Task.Status.DONE) {
            failures.add("end_to_end");
        }
        boolean ok = status == Task.Status.DONE && endToEndMoved == 3 && endToEndSourceEmpty;
        if (!ok && !failures.contains("end_to_end")) {
            failures.add("end_to_end");
        }
        BotLog.info("[Transfer] case=end_to_end result={} moved={} sourceEmpty={}",
                ok ? "PASS" : "FAIL", endToEndMoved, endToEndSourceEmpty);
        return ok;
    }

    /** 单个夹具包一层：**夹具抛异常也要如实记成 FAIL**，而不是把整条自检炸掉（2026-09-13 教训）。 */
    private boolean guarded(String name, java.util.function.BooleanSupplier body) {
        try {
            boolean ok = body.getAsBoolean();
            if (!ok && !failures.contains(name)) {
                failures.add(name);
            }
            BotLog.info("[Transfer] case={} result={}", name, ok ? "PASS" : "FAIL");
            return ok;
        } catch (RuntimeException | Error thrown) {
            if (!failures.contains(name)) {
                failures.add(name);
            }
            BotLog.warn("[Transfer] case={} result=FAIL exception={}", name, thrown.toString());
            return false;
        }
    }

    private Status finish(String reason) {
        done = true;
        BotLog.warn("[Transfer] 自检未完成 reason={}", reason);
        return Status.FAILED;
    }
}
