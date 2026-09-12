package com.dddgn.alice.task;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.pathing.core.CapabilityGate;
import com.dddgn.alice.pathing.core.MovementCapabilities;
import com.dddgn.alice.pathing.core.MovementSpec;
import com.dddgn.alice.pathing.core.MovementType;
import com.dddgn.alice.pathing.core.RecoverabilityLevel;
import com.dddgn.alice.pathing.core.search.PlannedMovement;
import com.dddgn.alice.pathing.core.search.PlannedMovementSpecs;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * **能力闸门自检**（基-8 / G8）：六例，**纯逻辑**（世界事实用假 Facts 喂），约 1 秒。
 *
 * <p>背景：`MovementCapabilities` 的 10 个字段此前**读者为 0**（P0-B 同族病灶）。本自检断言
 * 这些字段现在**真的能拦人** —— 每个拒绝码各一条负例，外加"全绿时放行"的正例：
 * <ul>
 *   <li>{@code CAPABILITY_UNAUTHORIZED}：会改世界的 Movement 却拿纯通行请求执行；</li>
 *   <li>{@code ZONE_PROTECTED_AREA}：目标落在保护区；</li>
 *   <li>{@code NO_REQUIRED_TOOL}：需要工具而手上没有；</li>
 *   <li>{@code NO_THROWAWAY_BLOCKS}：需要消耗一次性方块而没有；</li>
 *   <li>{@code PLACE_BUDGET_EXHAUSTED}：放置预算用尽。</li>
 * </ul>
 * 另加一组**声明一致性**断言：真实转换点产出的 caps 必须与 Movement 的写世界性质相符
 * （平走类 `changesWorld=false`、放置类 `canPlaceBlocks=true`、破坏类 `canBreakBlocks=true`）——
 * 否则"能力声明"仍然可以是假的。
 */
public class CapabilityGateCheckTask implements Task {

    private final BotPlayer bot;
    private final ServerPlayer observer;
    private final List<String> failures = new ArrayList<>();
    private int ticks;
    private boolean done;

    public CapabilityGateCheckTask(BotPlayer bot, ServerPlayer observer) {
        this.bot = bot;
        this.observer = observer;
    }

    @Override
    public String taskName() {
        return "CapabilityGateCheck";
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
        if (++ticks > 60) {
            return finish("timeout");
        }
        if (ticks > 1) {
            return done ? (failures.isEmpty() ? Status.DONE : Status.FAILED) : Status.RUNNING;
        }
        runChecks();
        done = true;
        return Status.RUNNING;
    }

    /** 假事实：按用例切换，不依赖世界。 */
    private record FakeFacts(boolean pureRequest, String protection, int throwaway, boolean breakBudget,
                             boolean placeBudget, boolean tool) implements CapabilityGate.Facts {
        @Override
        public String protectionReason(BlockPos pos) {
            return protection;
        }

        @Override
        public int throwawayBlocks() {
            return throwaway;
        }

        @Override
        public boolean hasWriteBudget(boolean breaking) {
            return breaking ? breakBudget : placeBudget;
        }

        @Override
        public boolean hasRequiredTool(MovementType type) {
            return tool;
        }

        @Override
        public boolean pureTraversalRequest() {
            return pureRequest;
        }
    }

    private static final FakeFacts OK_FACTS =
            new FakeFacts(false, null, 4, true, true, true);

    private void runChecks() {
        MovementCapabilities pure = MovementCapabilities.pureTraversal(
                RecoverabilityLevel.PATH_REVERSIBLE,
                com.dddgn.alice.pathing.core.IntrinsicReversibility.REVERSIBLE);
        MovementCapabilities breaker = MovementCapabilities.pathAccess(RecoverabilityLevel.LOCAL_STEP);
        MovementCapabilities placer = MovementCapabilities.temporarySupport(RecoverabilityLevel.LOCAL_STEP);
        BlockPos pos = bot.blockPosition();

        // 正例：纯通行不需要任何授权/预算检查
        expect("pure_traversal_allowed", CapabilityGate.check(pure, MovementType.TRAVERSE, pos,
                new FakeFacts(true, "protected_area", 0, false, false, false)), null);
        // 负例 1：会改世界却拿纯通行请求执行
        expect("capability_unauthorized", CapabilityGate.check(breaker, MovementType.BREAK_AND_ENTER, pos,
                new FakeFacts(true, null, 4, true, true, true)), "CAPABILITY_UNAUTHORIZED");
        // 负例 2：保护区。**用合成 caps**（`zoneAuth=true`）而不是依赖工厂声明 ——
        // 2026-09-12 实测：当时 `pathAccess()` 声明 `requiresZoneAuthorization=false`，这条负例"放行"，
        // 暴露了"保护区永不触发"的真问题（已修工厂）；这里保持合成用例，保证断言不随工厂声明漂移。
        MovementCapabilities zoneAware = new MovementCapabilities(true, Set.of(),
                false, false, com.dddgn.alice.pathing.core.IntrinsicReversibility.REVERSIBLE,
                RecoverabilityLevel.LOCAL_STEP, 0, true, false, false, true, true);
        expect("zone_protected", CapabilityGate.check(zoneAware, MovementType.BREAK_AND_ENTER, pos,
                new FakeFacts(false, "protected_area", 4, true, true, true)), "ZONE_PROTECTED_AREA");
        // 负例 2b：**生产声明**也必须过保护区（防"字段又变装饰"回归）
        expect("zone_protected_by_declaration", CapabilityGate.check(breaker, MovementType.BREAK_AND_ENTER, pos,
                new FakeFacts(false, "protected_area", 4, true, true, true)), "ZONE_PROTECTED_AREA");
        // 负例 3：没有工具
        expect("no_tool", CapabilityGate.check(breaker, MovementType.BREAK_AND_ENTER, pos,
                new FakeFacts(false, null, 4, true, true, false)), "NO_REQUIRED_TOOL");
        // 负例 4：没有一次性方块
        expect("no_throwaway", CapabilityGate.check(placer, MovementType.PILLAR, pos,
                new FakeFacts(false, null, 0, true, true, true)), "NO_THROWAWAY_BLOCKS");
        // 负例 5：放置预算用尽
        expect("no_place_budget", CapabilityGate.check(placer, MovementType.PILLAR, pos,
                new FakeFacts(false, null, 4, true, false, true)), "PLACE_BUDGET_EXHAUSTED");
        // 正例：事实齐备 ⇒ 放行
        expect("all_facts_ok_allowed", CapabilityGate.check(breaker, MovementType.BREAK_AND_ENTER, pos,
                OK_FACTS), null);

        checkDeclarations();
        checkForeignBreakAttribution();
        checkContainerWriteRecord();

        String summary = "pure_traversal_allowed=" + verdict("pure_traversal_allowed")
                + " capability_unauthorized=" + verdict("capability_unauthorized")
                + " zone_protected=" + verdict("zone_protected")
                + " zone_by_declaration=" + verdict("zone_protected_by_declaration")
                + " no_tool=" + verdict("no_tool")
                + " no_throwaway=" + verdict("no_throwaway")
                + " no_place_budget=" + verdict("no_place_budget")
                + " declarations=" + verdict("declarations")
                + " foreign_break_attribution=" + verdict("foreign_break_attribution")
                + " container_write_record=" + verdict("container_write_record")
                + " verdict=" + (failures.isEmpty() ? "PASS" : "FAIL");
        BotLog.info("[CapabilityGate] SUMMARY {}", summary);
        if (observer != null) {
            observer.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "[CapabilityGate] " + summary));
        }
    }

    /**
     * **G3：外来破坏要能被认出来**（双向断言）——
     * 伪造两条 `BreakEvent`（这就是模组连锁/爆炸/其他玩家走的同一条路）：
     * ① breaker = **别人** ⇒ 计入外来破坏；② breaker = **本 bot** ⇒ **不得**计入外来破坏。
     *
     * <p>为什么必须双向：只测 ① 无法排除"把所有破坏都记成外来"这种假实现。
     */
    private void checkForeignBreakAttribution() {
        com.dddgn.alice.bot.BotManager.BotSession session = com.dddgn.alice.bot.BotManager.sessionOf(bot);
        if (session == null || session.scope() == null) {
            check("foreign_break_attribution", false, "no_session_scope");
            return;
        }
        com.dddgn.alice.perception.ScopeBuffer scope = session.scope();
        BlockPos pos = bot.blockPosition();
        scope.begin(pos, 4, bot.getUUID());
        int before = scope.foreignBreakCount();
        var level = bot.serverLevel();
        var state = level.getBlockState(pos);
        // ① 别人破坏 ⇒ 计入
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(
                new net.minecraftforge.event.level.BlockEvent.BreakEvent(level, pos, state,
                        observer == null ? bot : observer));
        int afterForeign = scope.foreignBreakCount();
        // ② 本 bot 破坏 ⇒ 不计入
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(
                new net.minecraftforge.event.level.BlockEvent.BreakEvent(level, pos.above(), state, bot));
        int afterSelf = scope.foreignBreakCount();
        boolean ok = afterForeign == before + 1 && afterSelf == afterForeign;
        check("foreign_break_attribution", ok,
                "before=" + before + " afterForeign=" + afterForeign + " afterSelf=" + afterSelf
                        + " " + scope.describeForeignBreaks());
    }

    /**
     * **G5：容器写入维度**——物品进出箱子的痕迹要能被记录并**跨存档存活**。
     *
     * <p>用**独立实例**做（不污染真实世界数据）：记两条移动 ⇒ 断言计数/件数/描述，再 NBT 往返一次，
     * 断言记录真的进了存档（否则"记了"只活在内存里，重启就没了 —— 那不算审计痕迹）。
     */
    private void checkContainerWriteRecord() {
        com.dddgn.alice.transfer.TransferLedgerData probe =
                new com.dddgn.alice.transfer.TransferLedgerData();
        probe.recordMovement("r1", "chest_to_bot", "minecraft:iron_ingot", 3, "1, 2, 3", 100L, "transfer", "CONTAINER_TRANSFER");
        probe.recordMovement("r2", "bot_to_chest", "minecraft:cobblestone", 8, "4, 5, 6", 200L, "transfer", "CONTAINER_TRANSFER");
        boolean recorded = probe.movementCount() == 2 && probe.movementItems() == 11
                && probe.describeMovements().contains("11 件");
        com.dddgn.alice.transfer.TransferLedgerData reloaded =
                com.dddgn.alice.transfer.TransferLedgerData.roundTrip(probe);
        boolean persisted = reloaded.movementCount() == 2 && reloaded.movementItems() == 11
                && reloaded.movements().size() == 2;
        check("container_write_record", recorded && persisted,
                "recorded=" + recorded + " persisted=" + persisted + " " + reloaded.describeMovements());
    }

    /** 真实转换点产出的 caps 必须与 Movement 的写世界性质相符（否则能力声明仍可能是假的）。 */
    private void checkDeclarations() {
        BlockPos from = bot.blockPosition();
        StringBuilder detail = new StringBuilder();
        boolean ok = true;
        ok &= expectCaps(from, MovementType.TRAVERSE, from.east(), false, false, false, detail);
        ok &= expectCaps(from, MovementType.DESCEND, from.offset(1, -1, 0), false, false, false, detail);
        ok &= expectCaps(from, MovementType.BREAK_AND_ENTER, from.east(), true, false, true, detail);
        ok &= expectCaps(from, MovementType.DOWNWARD, from.below(), true, false, true, detail);
        // PILLAR：**改世界 + 放置、不破坏**（2026-09-12 实测：我第一版把这里写成 changesWorld=false/breaks=true，是断言写错）
        ok &= expectCaps(from, MovementType.PILLAR, from.above(), true, true, false, detail);
        check("declarations", ok, detail.toString().trim());
    }

    private boolean expectCaps(BlockPos from, MovementType type, BlockPos to, boolean changesWorld,
                               boolean places, boolean breaks, StringBuilder detail) {
        PlannedMovement movement = new PlannedMovement(type, from, to, 1.0D,
                com.dddgn.alice.pathing.core.RecoverabilityEvaluator.levelOf(type,
                        com.dddgn.alice.pathing.core.RecoverabilityFacts.FALL_RETURN_VERIFIED),
                com.dddgn.alice.pathing.core.RecoverabilityFacts.FALL_RETURN_VERIFIED);
        MovementSpec spec = PlannedMovementSpecs.toSpec(movement, List.of("capability_check"));
        MovementCapabilities caps = spec.capabilities();
        boolean ok = caps.changesWorld() == changesWorld && caps.canPlaceBlocks() == places
                && caps.canBreakBlocks() == breaks;
        detail.append(type).append('=').append(CapabilityGate.describe(caps)).append(" | ");
        return ok;
    }

    private void expect(String name, Optional<String> actual, String expected) {
        boolean ok = expected == null ? actual.isEmpty() : actual.isPresent() && actual.get().equals(expected);
        check(name, ok, "expected=" + (expected == null ? "(放行)" : expected)
                + " actual=" + actual.orElse("(放行)"));
    }

    private void check(String name, boolean ok, String detail) {
        if (!ok && !failures.contains(name)) {
            failures.add(name);
        }
        BotLog.info("[CapabilityGate] case={} result={} {}", name, ok ? "PASS" : "FAIL", detail);
    }

    private String verdict(String name) {
        return failures.contains(name) ? "FAIL" : "PASS";
    }

    private Status finish(String reason) {
        done = true;
        BotLog.warn("[CapabilityGate] 自检未完成 reason={}", reason);
        return Status.FAILED;
    }
}
