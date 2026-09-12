package com.dddgn.alice.task;

import com.dddgn.alice.log.BotLog;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.transfer.ChestBotTransferPrimitive;
import com.dddgn.alice.transfer.TransferCodes;
import com.dddgn.alice.transfer.TransferLedgerData;
import com.dddgn.alice.transfer.TransferRequest;
import com.dddgn.alice.transfer.TransferRoutes;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/** Narrow single-request orchestration. It consumes only existing HARD_PATH planning/execution. */
public final class TransferTask implements Task {
    private static final int PHASE_NO_PROGRESS = 200;
    private static final int ACTIVE_DEADLINE = 2400;
    private static final int MAX_SUSPENSION = 12000;
    private final BotPlayer bot;
    private final TransferRequest request;
    private final TransferLedgerData ledger;
    private final ServerLevel level;
    private Phase phase = Phase.TO_SOURCE;
    /** K-2：行走器改为新内核的 `PathRetryRunner`（原先 legacy `PathExecutor`）。 */
    private PathRetryRunner runner;
    private long started;
    private long phaseStarted;
    private String failure = "";
    private boolean completed;
    // ==================== L2 菜单路线状态（2026-09-13） ====================
    /** 当前正在进行的菜单会话（只走菜单协议：开 → 点击 → 关）。 */
    private com.dddgn.alice.action.MenuSession menuSession;
    private MenuStage menuStage = MenuStage.NONE;
    private int menuSourceSlot = -1;
    private int menuPlayerSlot = -1;
    private int menuDestinationSlot = -1;
    private com.dddgn.alice.action.WriteGrant activeGrant;

    /** 菜单路线的子阶段（比相位更细：一次写入要跨多 tick）。 */
    private enum MenuStage { NONE, OPENING, PICK, PLACE }
    /** Test-only fixture seam. Null is the production path. */
    public enum FixtureMovementOutcome { SEARCH_LIMIT, UNREACHABLE, FAILED }
    private enum Phase { TO_SOURCE, SOURCE_WRITE, TO_DESTINATION, DESTINATION_WRITE }


    /** Test-only clock control for the isolated fixture; production never calls it. */
    public void setFixtureElapsedTicks(long activeElapsed, long phaseElapsed) {
        long now = level.getGameTime();
        started = now - activeElapsed;
        phaseStarted = now - phaseElapsed;
    }

    public TransferTask(BotPlayer bot, TransferRequest request, TransferLedgerData ledger) {
        this.bot = bot; this.request = request; this.ledger = ledger; this.level = bot.serverLevel();
        started = level.getGameTime(); phaseStarted = started;
        transition(TransferLedgerData.State.MOVE_TO_SOURCE, TransferLedgerData.Location.NOT_MOVED, "", false);
    }
    @Override public TaskTarget target() { return TaskTarget.block(phase == Phase.TO_DESTINATION || phase == Phase.DESTINATION_WRITE ? request.destination().position() : request.source().position()); }
    @Override public String failureReason() { return failure; }
    @Override public Status tick() {
        TransferLedgerData.Entry entry = ledger.find(request.requestId()).orElse(null);
        if (entry != null && entry.state() == TransferLedgerData.State.ABORTED) { failure = "aborted"; return Status.FAILED; }
        long now = level.getGameTime();
        if (entry != null && entry.state() == TransferLedgerData.State.SUSPENDED) {
            if (entry.suspensionStartedTick() >= 0 && now - entry.suspensionStartedTick() > MAX_SUSPENSION) {
                ledger.expireSuspensions(now, MAX_SUSPENSION);
            }
            failure = TransferCodes.MANUAL_TAKEOVER_REQUIRED;
            return Status.FAILED;
        }
        TransferLedgerData.Location provenLocation = entry != null && entry.location() == TransferLedgerData.Location.BOT_INVENTORY
                ? TransferLedgerData.Location.BOT_INVENTORY : TransferLedgerData.Location.NOT_MOVED;
        if (now - started > ACTIVE_DEADLINE) return suspend(TransferCodes.TIMEOUT, provenLocation);
        if (now - phaseStarted > PHASE_NO_PROGRESS) return suspend(TransferCodes.TIMEOUT, provenLocation);
        return switch (phase) {
            case TO_SOURCE -> move(request.source().position(), TransferLedgerData.State.MOVE_TO_SOURCE, false);
            case SOURCE_WRITE -> sourceWrite();
            case TO_DESTINATION -> move(request.destination().position(), TransferLedgerData.State.MOVE_TO_DESTINATION, true);
            case DESTINATION_WRITE -> destinationWrite();
        };
    }
    /**
     * 走到**端点附近的可站点**（L1，2026-09-13 用户裁定）。
     *
     * <p>为什么不再"站到方块顶上"：那是 L0（能力直写）路线的产物，既不符合直觉，也让
     * "够不够得着"这件事完全没有语义。现在：① 在端点周围挑**最近的合法站点**
     * （正上方 + 同层 4 正邻 + 4 斜邻，要求可站且净空）；② 到达后按**原版触及语义**校验
     * （{@link com.dddgn.alice.action.BlockInteraction#reachable}）；够不到就如实失败。
     */
    private Status move(BlockPos endpoint, TransferLedgerData.State state, boolean inTransit) {
        // 测试接缝（默认惰性）：夹具可注入"行走结果"以确定性演练三种失败码
        FixtureMovementOutcome injected =
                com.dddgn.alice.transfer.TransferTestHooks.takeMovementOutcome();
        if (injected != null) {
            FixtureMovementOutcome outcome = injected;
            String code = switch (outcome) {
                case SEARCH_LIMIT -> TransferCodes.HARD_PATH_SEARCH_LIMIT;
                case UNREACHABLE -> TransferCodes.HARD_PATH_UNREACHABLE;
                case FAILED -> TransferCodes.HARD_PATH_FAILED;
            };
            return suspend(code, inTransit ? TransferLedgerData.Location.BOT_INVENTORY : TransferLedgerData.Location.NOT_MOVED);
        }
        // K-2（2026-09-13）：行走改用**新内核**（`PathRetryRunner`：每次从当前脚位重规划 + 重试）。
        // 原先这里是 legacy `SurfacePathfinder` + `PathExecutor` —— 生产路径上最后一处双内核引用。
        // 顺带白拿 K-1：预算耗尽时 runner 会**先走前缀再重规划**，而不是原地失败。
        if (runner == null) {
            BlockPos goal = standPointNear(endpoint);
            if (goal != null && goal.equals(bot.blockPosition())) {
                // 已经站在可站点上（常见：端点就在脚边）⇒ 不必规划，直接做触及校验
                if (!com.dddgn.alice.action.BlockInteraction.reachable(bot, endpoint)) {
                    return suspend(TransferCodes.ENDPOINT_OUT_OF_REACH, inTransit
                            ? TransferLedgerData.Location.BOT_INVENTORY : TransferLedgerData.Location.NOT_MOVED);
                }
                phase = inTransit ? Phase.DESTINATION_WRITE : Phase.SOURCE_WRITE;
                phaseStarted = level.getGameTime();
                return Status.RUNNING;
            }
            if (goal == null) {
                BotLog.warn("[Transfer] 端點周围找不到可站点 endpoint={} bot={}（L1 站位搜索）",
                        endpoint.toShortString(), bot.blockPosition().toShortString());
                return suspend(TransferCodes.ENDPOINT_NO_STANDING_POINT, inTransit
                        ? TransferLedgerData.Location.BOT_INVENTORY : TransferLedgerData.Location.NOT_MOVED);
            }
            com.dddgn.alice.pathing.core.search.PathRequest request =
                    com.dddgn.alice.pathing.core.search.PathRequest.of(
                            bot.getUUID().toString(), bot.blockPosition(), goal, "transfer");
            runner = new PathRetryRunner(bot, request, PathRetryRunner.DEFAULT_MAX_REPLANS,
                    "transfer-" + state);
            transition(state, inTransit ? TransferLedgerData.Location.BOT_INVENTORY
                    : TransferLedgerData.Location.NOT_MOVED, "", false);
        }
        PathRetryRunner.State moved = runner.tick();
        if (moved == PathRetryRunner.State.RUNNING) return Status.RUNNING;
        if (moved == PathRetryRunner.State.FAILED) {
            // 失败码映射（保持原来的词表）：PLAN_* 里"确实不可达"才报 UNREACHABLE，
            // 其余（预算耗尽/未加载/前缀用尽）一律 SEARCH_LIMIT —— 对齐 D-076「SEARCH_LIMIT ≠ UNREACHABLE」。
            String code = runner.result() == null ? "" : String.valueOf(runner.result().failureCode());
            runner = null;
            String mapped = code.contains("UNREACHABLE") ? TransferCodes.HARD_PATH_UNREACHABLE
                    : code.contains("PLAN_") ? TransferCodes.HARD_PATH_SEARCH_LIMIT
                    : TransferCodes.HARD_PATH_FAILED;
            return suspend(mapped, inTransit ? TransferLedgerData.Location.BOT_INVENTORY
                    : TransferLedgerData.Location.NOT_MOVED);
        }
        // 到达站点 ⇒ 按原版触及语义校验（L1）：够不到就如实失败，不硬写
        if (!com.dddgn.alice.action.BlockInteraction.reachable(bot, endpoint)) {
            BotLog.warn("[Transfer] endpoint_out_of_reach {} from {}（L1 触及校验）",
                    endpoint.toShortString(), bot.blockPosition().toShortString());
            runner = null;
            return suspend(TransferCodes.ENDPOINT_OUT_OF_REACH, inTransit
                    ? TransferLedgerData.Location.BOT_INVENTORY : TransferLedgerData.Location.NOT_MOVED);
        }
        phase = inTransit ? Phase.DESTINATION_WRITE : Phase.SOURCE_WRITE;
        runner = null;
        phaseStarted = level.getGameTime();
        return Status.RUNNING;
    }
    /** 端点周围**最近的合法站点**（正上方 + 同层 4 正邻 + 4 斜邻）；找不到返回 null。 */
    private BlockPos standPointNear(BlockPos endpoint) {
        var candidates = new java.util.ArrayList<BlockPos>();
        // **不站到容器顶上**（2026-09-13）：那是 L0 遗留行为；而且箱子/半砖这类**不满一格高**的方块
        // 会给内核的 ASCEND 出难题（踩上去需要半格台阶逻辑）。候选只取**同层 4 正邻 + 8 斜邻**。
        for (net.minecraft.core.Direction direction
                : net.minecraft.core.Direction.Plane.HORIZONTAL) {
            candidates.add(endpoint.relative(direction));
        }
        for (net.minecraft.core.Direction direction
                : net.minecraft.core.Direction.Plane.HORIZONTAL) {
            BlockPos diagonal = endpoint.relative(direction);
            for (net.minecraft.core.Direction side : net.minecraft.core.Direction.Plane.HORIZONTAL) {
                if (side.getAxis() != direction.getAxis()) {
                    candidates.add(diagonal.relative(side));
                }
            }
        }
        BlockPos feet = bot.blockPosition();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos pos : candidates) {
            boolean standable = com.dddgn.alice.pathing.MovementHelper.canWalkOn(level, pos)
                    && com.dddgn.alice.pathing.MovementHelper.canWalkThrough(level, pos)
                    && com.dddgn.alice.pathing.MovementHelper.canWalkThrough(level, pos.above());
            if (!standable) {
                continue;
            }
            double distance = pos.distSqr(feet);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = pos.immutable();
            }
        }
        return best;
    }

    /** 容器写入的**授权**（2026-09-13 用户裁定：容器写入算世界改动）。 */
    private static com.dddgn.alice.action.WriteGrant containerGrant() {
        return com.dddgn.alice.action.WriteGrant.of("transfer",
                com.dddgn.alice.action.WriteReason.CONTAINER_TRANSFER);
    }

    // ==================== L2：菜单路线（真实 openMenu + 菜单点击） ====================

    /**
     * **源箱 → bot**：走真实菜单协议。
     *
     * <p>流程：语义表校验（不支持就如实失败）→ 消耗 A11 预算 → `MenuSession` 打开菜单 →
     * 在容器槽位里找目标物品 → 点击拿起 → 点击放进**空的玩家槽** → 关闭 → **按背包增量校验** →
     * 记账（G5 移动记录）→ 进入下一相位。
     *
     * <p>为什么逐 tick 推进：菜单生效、点击生效都跨 tick（真人也是这样），所以这一段是**子状态机**。
     */
    private Status sourceWriteViaMenu() {
        var info = com.dddgn.alice.action.ContainerSemantics.of(level.getBlockState(request.source().position()));
        if (info == null) {
            return suspend(TransferCodes.UNSUPPORTED_CONTAINER, TransferLedgerData.Location.NOT_MOVED);
        }
        if (menuStage == MenuStage.NONE) {
            activeGrant = containerGrant();
            if (com.dddgn.alice.action.WriteBudget.consumeContainerWrite(bot,
                    request.source().position(), activeGrant)
                    == com.dddgn.alice.action.WriteBudget.Verdict.REFUSED) {
                return suspend(TransferCodes.CONTAINER_BUDGET_EXHAUSTED, TransferLedgerData.Location.NOT_MOVED);
            }
            transition(TransferLedgerData.State.SOURCE_LEG_PRE, TransferLedgerData.Location.NOT_MOVED, "", false);
            menuSession = com.dddgn.alice.action.MenuSession.open(bot, request.source().position(), info.slotCount());
            menuStage = MenuStage.OPENING;
            return Status.RUNNING;
        }
        if (menuStage == MenuStage.OPENING) {
            var state = menuSession.tick();
            if (state == com.dddgn.alice.action.MenuSession.State.FAILED) {
                return menuFailed(menuSession.failure(), TransferLedgerData.Location.NOT_MOVED);
            }
            if (state != com.dddgn.alice.action.MenuSession.State.OPEN) {
                return Status.RUNNING;
            }
            menuSourceSlot = menuSession.findInContainer(stack -> !stack.isEmpty()
                    && stack.getItem() == net.minecraft.core.registries.BuiltInRegistries.ITEM
                            .get(request.itemId()));
            if (menuSourceSlot < 0) {
                menuSession.close("source_item_missing");
                menuStage = MenuStage.NONE;
                return failNotMovedMenu(TransferCodes.SOURCE_INSUFFICIENT);
            }
            menuPlayerSlot = menuSession.findEmptyPlayerSlot();
            if (menuPlayerSlot < 0) {
                menuSession.close("bot_full");
                menuStage = MenuStage.NONE;
                return suspend(TransferCodes.BOT_INVENTORY_FULL, TransferLedgerData.Location.NOT_MOVED);
            }
            menuStage = MenuStage.PICK;
            return Status.RUNNING;
        }
        if (menuStage == MenuStage.PICK) {
            if (!menuSession.click(menuSourceSlot, net.minecraft.world.inventory.ClickType.PICKUP)) {
                return menuFailed(menuSession.failure(), TransferLedgerData.Location.NOT_MOVED);
            }
            menuStage = MenuStage.PLACE;
            return Status.RUNNING;
        }
        // PLACE：把"鼠标上"的那堆放进玩家槽 ⇒ 关闭 ⇒ 校验增量
        if (!menuSession.click(menuPlayerSlot, net.minecraft.world.inventory.ClickType.PICKUP)) {
            return menuFailed(menuSession.failure(), TransferLedgerData.Location.NOT_MOVED);
        }
        menuSession.close("source_leg_done");
        int inBot = countInBot(request.itemId());
        menuStage = MenuStage.NONE;
        if (inBot <= 0) {
            return failNotMovedMenu(TransferCodes.SOURCE_INSUFFICIENT);
        }
        ledger.recordMovement(String.valueOf(request.requestId()), "chest_to_bot",
                String.valueOf(request.itemId()), inBot, bot.blockPosition().toShortString(),
                level.getGameTime(), activeGrant.requester(), activeGrant.reason().name());
        transition(TransferLedgerData.State.IN_TRANSIT_BOT, TransferLedgerData.Location.BOT_INVENTORY,
                "menu=ok moved=" + inBot, false);
        phase = Phase.TO_DESTINATION;
        phaseStarted = level.getGameTime();
        return Status.RUNNING;
    }

    /** **bot → 目标箱**：走真实菜单协议（镜像上面那条腿）。 */
    private Status destinationWriteViaMenu() {
        var info = com.dddgn.alice.action.ContainerSemantics.of(level.getBlockState(request.destination().position()));
        if (info == null) {
            return suspend(TransferCodes.UNSUPPORTED_CONTAINER, TransferLedgerData.Location.BOT_INVENTORY);
        }
        if (menuStage == MenuStage.NONE) {
            activeGrant = containerGrant();
            if (com.dddgn.alice.action.WriteBudget.consumeContainerWrite(bot,
                    request.destination().position(), activeGrant)
                    == com.dddgn.alice.action.WriteBudget.Verdict.REFUSED) {
                return suspend(TransferCodes.CONTAINER_BUDGET_EXHAUSTED, TransferLedgerData.Location.BOT_INVENTORY);
            }
            transition(TransferLedgerData.State.DESTINATION_LEG_PRE, TransferLedgerData.Location.BOT_INVENTORY, "", false);
            menuSession = com.dddgn.alice.action.MenuSession.open(bot, request.destination().position(), info.slotCount());
            menuStage = MenuStage.OPENING;
            return Status.RUNNING;
        }
        if (menuStage == MenuStage.OPENING) {
            var state = menuSession.tick();
            if (state == com.dddgn.alice.action.MenuSession.State.FAILED) {
                return menuFailed(menuSession.failure(), TransferLedgerData.Location.BOT_INVENTORY);
            }
            if (state != com.dddgn.alice.action.MenuSession.State.OPEN) {
                return Status.RUNNING;
            }
            // **背包索引 → 菜单槽位号**（2026-09-13 实测踩坑：两个索引空间，直接混用就会点在空格上）
            int inventoryIndex = findBotInventoryIndexWith(request.itemId());
            menuPlayerSlot = inventoryIndex < 0 ? -1 : menuSession.playerMenuSlotFor(inventoryIndex);
            if (menuPlayerSlot < 0) {
                menuSession.close("bot_item_missing");
                menuStage = MenuStage.NONE;
                return suspend(inventoryIndex < 0 ? TransferCodes.SOURCE_INSUFFICIENT
                        : TransferCodes.CONTAINER_MENU_FAILED, TransferLedgerData.Location.BOT_INVENTORY);
            }
            menuDestinationSlot = menuSession.findInContainer(net.minecraft.world.item.ItemStack::isEmpty);
            if (menuDestinationSlot < 0) {
                menuSession.close("destination_full");
                menuStage = MenuStage.NONE;
                return suspend(TransferCodes.DESTINATION_FULL, TransferLedgerData.Location.BOT_INVENTORY);
            }
            menuStage = MenuStage.PICK;
            return Status.RUNNING;
        }
        if (menuStage == MenuStage.PICK) {
            if (!menuSession.click(menuPlayerSlot, net.minecraft.world.inventory.ClickType.PICKUP)) {
                return menuFailed(menuSession.failure(), TransferLedgerData.Location.BOT_INVENTORY);
            }
            menuStage = MenuStage.PLACE;
            return Status.RUNNING;
        }
        if (!menuSession.click(menuDestinationSlot, net.minecraft.world.inventory.ClickType.PICKUP)) {
            return menuFailed(menuSession.failure(), TransferLedgerData.Location.BOT_INVENTORY);
        }
        menuSession.close("destination_leg_done");
        int inDestination = countInContainer(request.destination().position(), request.itemId());
        menuStage = MenuStage.NONE;
        if (inDestination <= 0) {
            return suspend(TransferCodes.UNKNOWN_DISCREPANCY, TransferLedgerData.Location.UNKNOWN);
        }
        ledger.recordMovement(String.valueOf(request.requestId()), "bot_to_chest",
                String.valueOf(request.itemId()), inDestination, bot.blockPosition().toShortString(),
                level.getGameTime(), activeGrant.requester(), activeGrant.reason().name());
        transition(TransferLedgerData.State.VERIFIED, TransferLedgerData.Location.DESTINATION_CHEST,
                "menu=ok moved=" + inDestination, false);
        completed = true;
        return Status.DONE;
    }

    /** 菜单路线失败：关菜单 + 如实记账（把会话的具体失败码写进证据）。 */
    private Status menuFailed(String menuCode, TransferLedgerData.Location location) {
        if (menuSession != null) {
            menuSession.close("failed:" + menuCode);
        }
        menuStage = MenuStage.NONE;
        failure = TransferCodes.CONTAINER_MENU_FAILED;
        transition(TransferLedgerData.State.SUSPENDED, location, failure + ":" + menuCode, true);
        BotLog.warn("[Transfer] 菜单路线失败 code={} menuCode={}（已关菜单）", failure, menuCode);
        return Status.FAILED;
    }

    private Status failNotMovedMenu(String code) {
        failure = code;
        BotLog.warn("[Transfer] fail_not_moved code={} phase={}（菜单路线）", code, phase);
        transition(TransferLedgerData.State.FAILED_NOT_MOVED, TransferLedgerData.Location.NOT_MOVED, code, false);
        return Status.FAILED;
    }

    /** bot 背包里目标物品的总数（菜单路线的校验口径）。 */
    private int countInBot(net.minecraft.resources.ResourceLocation itemId) {
        var item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(itemId);
        int total = 0;
        var inventory = bot.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            var stack = inventory.getItem(slot);
            if (!stack.isEmpty() && stack.getItem() == item) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /** 容器里的目标物品总数（读方块实体，只读）。 */
    private int countInContainer(BlockPos pos, net.minecraft.resources.ResourceLocation itemId) {
        if (!(level.getBlockEntity(pos) instanceof net.minecraft.world.Container container)) {
            return 0;
        }
        var item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(itemId);
        int total = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            var stack = container.getItem(slot);
            if (!stack.isEmpty() && stack.getItem() == item) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /**
     * bot 背包里第一个装着目标物品的**背包索引**（0..35）。
     *
     * <p>⚠️ 注意：这是 `Inventory` 的索引，**不是菜单槽位号**！调用方必须用
     * `MenuSession.playerMenuSlotFor(...)` 转换（2026-09-13 实测踩坑）。
     */
    private int findBotInventoryIndexWith(net.minecraft.resources.ResourceLocation itemId) {
        var item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(itemId);
        var inventory = bot.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            var stack = inventory.getItem(slot);
            if (!stack.isEmpty() && stack.getItem() == item) {
                return slot;
            }
        }
        return -1;
    }

    private Status sourceWrite() {
        if (!com.dddgn.alice.action.BlockInteraction.reachable(bot, request.source().position())) {
            return suspend(TransferCodes.ENDPOINT_OUT_OF_REACH, TransferLedgerData.Location.NOT_MOVED);
        }
        if (TransferRoutes.route() == TransferRoutes.Route.MENU) {
            return sourceWriteViaMenu();
        }
        // 授权 + 预算（容器写入纳入"世界改动"体系；超限即拒绝）
        com.dddgn.alice.action.WriteGrant grant = containerGrant();
        if (com.dddgn.alice.action.WriteBudget.consumeContainerWrite(bot,
                request.source().position(), grant) == com.dddgn.alice.action.WriteBudget.Verdict.REFUSED) {
            return suspend(TransferCodes.CONTAINER_BUDGET_EXHAUSTED, TransferLedgerData.Location.NOT_MOVED);
        }
        transition(TransferLedgerData.State.SOURCE_LEG_PRE, TransferLedgerData.Location.NOT_MOVED, "", false);
        ChestBotTransferPrimitive.Result result = ChestBotTransferPrimitive.sourceChestToBot(level, request, bot.getInventory());
        if (!result.proven()) return result.unknownDiscrepancy() ? unknown(result) : failNotMoved(result);
        // G5：容器写入维度 —— 记下**真实移动**（从源箱取出）
        ledger.recordMovement(String.valueOf(request.requestId()), "chest_to_bot", String.valueOf(request.itemId()),
                Math.max(0, result.botDelta()), bot.blockPosition().toShortString(), level.getGameTime(),
                grant.requester(), grant.reason().name());
        transition(TransferLedgerData.State.IN_TRANSIT_BOT, TransferLedgerData.Location.BOT_INVENTORY, result.code() + evidence(result), false);
        phase = Phase.TO_DESTINATION; phaseStarted = level.getGameTime(); return Status.RUNNING;
    }
    private Status destinationWrite() {
        if (!com.dddgn.alice.action.BlockInteraction.reachable(bot, request.destination().position())) {
            return suspend(TransferCodes.ENDPOINT_OUT_OF_REACH, TransferLedgerData.Location.BOT_INVENTORY);
        }
        if (TransferRoutes.route() == TransferRoutes.Route.MENU) {
            return destinationWriteViaMenu();
        }
        com.dddgn.alice.action.WriteGrant grant = containerGrant();
        if (com.dddgn.alice.action.WriteBudget.consumeContainerWrite(bot,
                request.destination().position(), grant) == com.dddgn.alice.action.WriteBudget.Verdict.REFUSED) {
            return suspend(TransferCodes.CONTAINER_BUDGET_EXHAUSTED, TransferLedgerData.Location.BOT_INVENTORY);
        }
        transition(TransferLedgerData.State.DESTINATION_LEG_PRE, TransferLedgerData.Location.BOT_INVENTORY, "", false);
        ChestBotTransferPrimitive.Result result = ChestBotTransferPrimitive.botToDestinationChest(level, request, bot.getInventory());
        if (!result.proven()) return result.unknownDiscrepancy() ? unknown(result) : suspend(result.code(), TransferLedgerData.Location.BOT_INVENTORY);
        // G5：记下**真实移动**（写入目标箱）
        ledger.recordMovement(String.valueOf(request.requestId()), "bot_to_chest", String.valueOf(request.itemId()),
                Math.max(0, result.destinationDelta()), bot.blockPosition().toShortString(),
                level.getGameTime(), grant.requester(), grant.reason().name());
        transition(TransferLedgerData.State.VERIFIED, TransferLedgerData.Location.DESTINATION_CHEST, result.code() + evidence(result), false);
        completed = true; return Status.DONE;
    }
    private Status failNotMoved(ChestBotTransferPrimitive.Result result) { failure = result.code(); transition(TransferLedgerData.State.FAILED_NOT_MOVED, TransferLedgerData.Location.NOT_MOVED, failure + evidence(result), false); return Status.FAILED; }
    private Status unknown(ChestBotTransferPrimitive.Result result) { failure = TransferCodes.UNKNOWN_DISCREPANCY; transition(TransferLedgerData.State.UNKNOWN_DISCREPANCY, TransferLedgerData.Location.UNKNOWN, failure + evidence(result), true); return Status.FAILED; }
    /**
     * 中止（挂起）——**必须留痕**（2026-09-13 实测教训：原先这里不打日志，
     * `end_to_end` 失败时日志里一片空白，只能靠猜 ⇒ 可诊断性缺口）。
     */
    private Status suspend(String code, TransferLedgerData.Location location) {
        failure = code;
        BotLog.warn("[Transfer] suspend code={} location={} phase={} route={} bot={} src={} dest={}",
                code, location, phase, TransferRoutes.route(), bot.blockPosition().toShortString(),
                request.source().position().toShortString(), request.destination().position().toShortString());
        transition(TransferLedgerData.State.SUSPENDED, location, code, true);
        return Status.FAILED;
    }
    public void survivalInterrupted(String code) { if (!completed) transition(TransferLedgerData.State.SUSPENDED, phase == Phase.TO_DESTINATION || phase == Phase.DESTINATION_WRITE ? TransferLedgerData.Location.BOT_INVENTORY : TransferLedgerData.Location.NOT_MOVED, code, true); }
    public void botRemoved() { if (!completed) transition(TransferLedgerData.State.UNKNOWN_DISCREPANCY, TransferLedgerData.Location.UNKNOWN, TransferCodes.UNKNOWN_DISCREPANCY, true); }
    public TransferRequest request() { return request; }
    private void transition(TransferLedgerData.State state, TransferLedgerData.Location location, String code, boolean manual) { ledger.transition(request.requestId(), state, location, code, level.getGameTime(), state + ":" + location + ":" + level.getGameTime(), manual); }
    private static String evidence(ChestBotTransferPrimitive.Result r) { return " source=" + r.sourceDelta() + " bot=" + r.botDelta() + " destination=" + r.destinationDelta(); }
}
