package com.dddgn.alice.task;

import com.dddgn.alice.action.BlockInteraction;
import com.dddgn.alice.action.MenuSession;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.log.BotLog;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * **L2 最小验证探针**（{@code alice:menu_probe}，2026-09-13）：让 bot **真的开一次箱子菜单**、
 * 通过**菜单协议**搬一次物品、再关掉 —— 全程带延迟，便于**在游戏里肉眼观察**。
 *
 * <p>要验证的核心命题（`docs/INTERACTION_LAYERS_COMPARISON.md` §3 第 5 项）：
 * **没有真实客户端时，服务端菜单状态是否自洽** —— 即
 * `useItemOn` 能否真的 `openMenu`、`menu.clicked(...)` 能否真的搬动物品、`closeContainer()` 能否干净收尾。
 * 这条不成立，整条 B 路线（菜单协议）就得重新评估。
 *
 * <p>与 A 路线（capability 直写）的**可见差异**正是本探针想看的东西：
 * 开盖动画 + 开箱音（`ContainerOpenersCounter` 驱动）、bot 会**转向并挥手**、物品若落进快捷栏
 * 会**显示在 bot 手上**。
 *
 * <p>**这是探针不是生产实现**：为了确定性，站位用传送（生产 L2 应走内核寻路 + L1 站位/触及校验）。
 */
public class MenuProbeTask implements Task {

    // 各阶段延迟（tick）。故意放大：用户要看菜单效果（开盖/音效/逐次点击）。
    private static final int FACE_HOLD = 30;
    private static final int OPEN_HOLD = 40;
    private static final int CLICK_HOLD = 20;
    private static final int CLOSE_HOLD = 20;

    private enum Phase { SETUP, FACE, OPEN, OPENING, OPEN_HOLD, CLICK_PICK, CLICK_HOLD_1, CLICK_PLACE, CLICK_HOLD_2, CLOSE, CLOSE_HOLD, DONE }

    private final BotPlayer bot;
    private final ServerPlayer observer;
    private final List<String> failures = new ArrayList<>();
    private final List<String> notes = new ArrayList<>();

    private Phase phase = Phase.SETUP;
    private int phaseTicks;
    private int ticks;
    private BlockPos chestPos;
    private int chestItemsBefore = -1;
    private boolean menuOpened;
    private String menuType = "-";
    private int menuSlots = -1;
    private int pickedSlot = -1;
    private int placedSlot = -1;
    private boolean clicksOk;
    /** L2：**菜单会话**（开/操作/关闭的全部生命周期由它保证；探针只是第一个消费者）。 */
    private MenuSession session;

    public MenuProbeTask(BotPlayer bot, ServerPlayer observer) {
        this.bot = bot;
        this.observer = observer;
    }

    @Override
    public String taskName() {
        return "MenuProbe";
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
        return phase == Phase.DONE ? (failures.isEmpty() ? "passed" : "failed") : "";
    }

    @Override
    public Status tick() {
        if (++ticks > 600) {
            return finish("timeout");
        }
        phaseTicks++;
        return switch (phase) {
            case SETUP -> setup();
            case FACE -> hold(FACE_HOLD, this::doOpen, Phase.OPEN);
            case OPEN -> open();
            case OPENING -> opening();
            case OPEN_HOLD -> hold(OPEN_HOLD, this::doClickPick, Phase.CLICK_PICK);
            case CLICK_PICK -> clickPick();
            case CLICK_HOLD_1 -> hold(CLICK_HOLD, this::doClickPlace, Phase.CLICK_PLACE);
            case CLICK_PLACE -> clickPlace();
            case CLICK_HOLD_2 -> hold(CLICK_HOLD, this::doClose, Phase.CLOSE);
            case CLOSE -> close();
            case CLOSE_HOLD -> hold(CLOSE_HOLD, this::assertResultImpl, Phase.DONE);
            case DONE -> failures.isEmpty() ? Status.DONE : Status.FAILED;
        };
    }

    // ==================== 各阶段 ====================

    private Status setup() {
        var level = bot.serverLevel();
        // 箱子放在 bot 东侧 2 格（保证触及范围内），里面放 3 个铁锭
        chestPos = bot.blockPosition().east(2).immutable();
        level.setBlock(chestPos, Blocks.CHEST.defaultBlockState(), 3);
        if (level.getBlockEntity(chestPos) instanceof ChestBlockEntity chest) {
            for (int slot = 0; slot < chest.getContainerSize(); slot++) {
                chest.setItem(slot, ItemStack.EMPTY);
            }
            chest.setItem(0, new ItemStack(Items.IRON_INGOT, 3));
            chest.setChanged();
            chestItemsBefore = 3;
        } else {
            failures.add("chest_setup");
            return finish("chest_setup");
        }
        // bot 背包清空（好观察"物品真的进了 bot"）
        var inventory = bot.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            inventory.setItem(slot, ItemStack.EMPTY);
        }
        // 站到箱子旁（探针用传送保证确定性；生产 L2 应走内核寻路）
        BlockPos stand = chestPos.west();
        bot.teleportTo(level, stand.getX() + 0.5D, stand.getY(), stand.getZ() + 0.5D,
                java.util.Set.of(), bot.getYRot(), bot.getXRot());
        bot.setDeltaMovement(Vec3.ZERO);
        bot.controller().stopMovement();
        BotLog.info("[MenuProbe] setup chest={} stand={} itemsBefore={}（接下来 {}+{} tick 观察开盖与音效）",
                chestPos.toShortString(), stand.toShortString(), chestItemsBefore, FACE_HOLD, OPEN_HOLD);
        return advance(Phase.FACE);
    }

    /** 面向箱子（人看得见 bot 转头）。 */
    private Status hold(int limit, java.util.function.Supplier<Status> next, Phase nextPhase) {
        bot.controller().stopMovement();
        if (phaseTicks == 1) {
            BlockInteraction.faceTowards(bot, Vec3.atCenterOf(chestPos));
        }
        if (phaseTicks < limit) {
            return Status.RUNNING;
        }
        Status result = next.get();
        if (result == Status.RUNNING) {
            phase = nextPhase;
            phaseTicks = 0;
        }
        return result;
    }

    private Status doOpen() {
        return Status.RUNNING;
    }

    /** **真实右键开菜单**（交给 `MenuSession`：它负责 useItemOn + 等待生效 + 超时 + 失败收尾）。 */
    private Status open() {
        BlockInteraction.faceTowards(bot, Vec3.atCenterOf(chestPos));
        session = MenuSession.open(bot, chestPos, 27);
        BotLog.info("[MenuProbe] 发出真实右键（useItemOn），等待服务端菜单生效…");
        advance(Phase.OPENING);
        return Status.RUNNING;
    }

    /** 等 `MenuSession` 把菜单打开（超时/失败都会自动收尾）。 */
    private Status opening() {
        MenuSession.State state = session.tick();
        if (state == MenuSession.State.OPEN) {
            menuOpened = true;
            menuType = session.menuType();
            menuSlots = session.menuSlots();
            BotLog.info("[MenuProbe] 菜单已开 type={} slots={}（下面 {} tick 观察开盖与音效）",
                    menuType, menuSlots, OPEN_HOLD);
            advance(Phase.OPEN_HOLD);
            return Status.RUNNING;
        }
        if (state == MenuSession.State.FAILED) {
            // 透传会话的**真实失败码**（`menu_open_timeout` 与 `menu_open_failed` 是两件事：
            // 前者"发出右键但服务端没给菜单"（如中途被传送走），后者"右键本身没被接受"）
            failures.add("menu_open:" + session.failure());
            notes.add("menuFailure=" + session.failure());
            return finish(session.failure());
        }
        return Status.RUNNING;
    }

    private Status doClickPick() {
        return Status.RUNNING;
    }

    /** **真实点击（第一步）**：把箱子里那堆"拿在鼠标上"（`ClickType.PICKUP` = 真人左键）。 */
    private Status clickPick() {
        if (!session.isOpen()) {
            failures.add("click_without_menu");
            return finish("click_without_menu");
        }
        pickedSlot = session.findInContainer(stack -> stack.is(Items.IRON_INGOT));
        if (pickedSlot < 0) {
            failures.add("no_source_stack_in_menu");
            return finish("no_source_stack_in_menu");
        }
        // 容器写入授权（R1 收口，2026-09-14）：本探针**真的把物品从箱子里搬出来**，
        // 属于世界写入 ⇒ 过 `WriteBudget` 容器维度 + 策略表判定（requester=本探针 ⇒ DIAGNOSTIC 行，
        // 该行声明全集 ⇒ 只有预算耗尽才可能拒绝，那必须如实上报）。
        var containerGrant = com.dddgn.alice.action.WriteGrant.of(taskName(),
                com.dddgn.alice.action.WriteReason.CONTAINER_TRANSFER);
        if (com.dddgn.alice.action.WriteBudget.consumeContainerWrite(bot, chestPos, containerGrant)
                == com.dddgn.alice.action.WriteBudget.Verdict.REFUSED) {
            failures.add("container_write_refused");
            return finish("container_write_refused");
        }
        if (!session.click(pickedSlot, ClickType.PICKUP)) {
            failures.add("click_pick_failed:" + session.failure());
            return finish("click_pick_failed");
        }
        BotLog.info("[MenuProbe] click_pick slot={} carried={}（菜单里箱子的东西应已被拿起）",
                pickedSlot, session.carried().getCount() + "x" + session.carried().getHoverName().getString());
        advance(Phase.CLICK_HOLD_1);
        return Status.RUNNING;
    }

    private Status doClickPlace() {
        return Status.RUNNING;
    }

    /** **真实点击（第二步）**：放进 bot **快捷栏**的空槽（落在手上 ⇒ 客户端能看到 bot 拿着铁锭）。 */
    private Status clickPlace() {
        if (!session.isOpen()) {
            failures.add("menu_gone_before_place");
            return finish("menu_gone_before_place");
        }
        int hotbarFirst = session.firstHotbarSlot();
        placedSlot = -1;
        var menu = bot.containerMenu;
        for (int slot = hotbarFirst; slot < menu.slots.size(); slot++) {
            if (menu.slots.get(slot).getItem().isEmpty()) {
                placedSlot = slot;
                break;
            }
        }
        if (placedSlot < 0) {
            placedSlot = session.findEmptyPlayerSlot();   // 快捷栏满了就退而求其次
        }
        if (placedSlot < 0) {
            failures.add("no_empty_player_slot_in_menu");
            return finish("no_empty_player_slot_in_menu");
        }
        if (!session.click(placedSlot, ClickType.PICKUP)) {
            failures.add("click_place_failed:" + session.failure());
            return finish("click_place_failed");
        }
        clicksOk = session.carried().isEmpty();
        BotLog.info("[MenuProbe] click_place slot={} carried_after={} clicksOk={}",
                placedSlot, session.carried().isEmpty() ? "空" : "仍有物品", clicksOk);
        advance(Phase.CLICK_HOLD_2);
        return Status.RUNNING;
    }

    private Status doClose() {
        return Status.RUNNING;
    }

    private Status close() {
        session.close("probe_done");
        boolean closed = bot.containerMenu == bot.inventoryMenu;
        BotLog.info("[MenuProbe] closeContainer closed={}（箱子盖子应合上）", closed);
        if (!closed) {
            failures.add("menu_not_closed");
        }
        advance(Phase.CLOSE_HOLD);
        return Status.RUNNING;
    }

    private Status assertResultImpl() {
        int inBot = 0;
        var inventory = bot.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (inventory.getItem(slot).is(Items.IRON_INGOT)) {
                inBot += inventory.getItem(slot).getCount();
            }
        }
        int inChest = 0;
        if (bot.serverLevel().getBlockEntity(chestPos) instanceof ChestBlockEntity chest) {
            for (int slot = 0; slot < chest.getContainerSize(); slot++) {
                if (chest.getItem(slot).is(Items.IRON_INGOT)) {
                    inChest += chest.getItem(slot).getCount();
                }
            }
        }
        boolean moved = inBot == 3 && inChest == 0;
        if (!moved) {
            failures.add("item_not_moved");
        }
        if (session != null && !session.failure().isEmpty()) {
            failures.add("session:" + session.failure());
        }
        if (session != null && session.isOpen()) {
            failures.add("session_left_open");
        }
        notes.add("botHasIron=" + inBot);
        notes.add("chestLeftIron=" + inChest);
        String summary = "menu_opened=" + (menuOpened ? "PASS" : "FAIL")
                + " menu_type=" + menuType + "/slots=" + menuSlots
                + " clicks=" + (clicksOk ? "PASS" : "FAIL")
                + " item_moved=" + (moved ? "PASS" : "FAIL")
                + " verdict=" + (failures.isEmpty() ? "PASS" : "FAIL");
        BotLog.info("[MenuProbe] SUMMARY {} {}", summary, String.join(" ", notes));
        if (observer != null) {
            observer.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "[MenuProbe] " + summary));
        }
        return Status.RUNNING;
    }

    // ==================== 工具 ====================

    private Status advance(Phase next) {
        phase = next;
        phaseTicks = 0;
        return Status.RUNNING;
    }

    private Status finish(String reason) {
        // 出错也要把菜单关掉（现在由 `MenuSession` 保证；这里只是兜底调用，幂等）
        if (session != null) {
            session.close("probe_finish:" + reason);
        }
        phase = Phase.DONE;
        String summary = "menu_opened=" + (menuOpened ? "PASS" : "FAIL")
                + " menu_type=" + menuType + "/slots=" + menuSlots
                + " clicks=" + (clicksOk ? "PASS" : "FAIL")
                + " verdict=FAIL";
        BotLog.warn("[MenuProbe] SUMMARY {} reason={}", summary, reason);
        if (observer != null) {
            observer.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "[MenuProbe] " + summary + " reason=" + reason));
        }
        return Status.FAILED;
    }
}
