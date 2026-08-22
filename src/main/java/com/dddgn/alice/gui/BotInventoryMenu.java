package com.dddgn.alice.gui;

import com.dddgn.alice.bot.BotManager;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.log.BotLog;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Informational container menu over a bot's inventory: 36 ordinary slots (0..35) and
 * 4 armor slots (36..39). The main-hand slot is the robot's {@code selected} ordinary
 * slot (0..8), so it is already represented in the ordinary grid and is not repeated.
 * The player's own inventory is laid out below. While the bot runs a task
 * ({@code taskActive}, via {@link BotManager#isBusy(BotPlayer)}), the bot slots are
 * forced read-only so a user cannot corrupt an in-progress transfer/mine.
 * <p>
 * The menu is opened over the <em>requesting player's</em> real connection via
 * {@code player.openMenu(modelProvider)}; it never uses {@code bot.connection.send}
 * (a FakeConnection no-op). This mirrors the equipment-rendering lesson.
 */
public final class BotInventoryMenu extends AbstractContainerMenu {

    /** Last bot opened, used only to rebuild a menu on server-side resize. */
    private static BotPlayer OPEN_BOT;

    /** Bot slot count (36 ordinary + 4 armor); indices below this are bot-owned. */
    private static final int BOT_SLOT_CAP = 40;

    private final BotPlayer bot;
    private final boolean taskActive;

    public BotInventoryMenu(int containerId, Inventory playerInv, BotPlayer bot) {
        super(ModMenuTypes.BOT_INVENTORY_MENU.get(), containerId);
        this.bot = bot;
        this.taskActive = bot != null && BotManager.isBusy(bot);
        OPEN_BOT = bot;

        if (bot != null) {
            buildBotSlots(bot);
        } else {
            BotLog.warn("bot_inventory opened with null bot; showing player slots only");
        }
        buildPlayerSlots(playerInv);
    }

    private void buildBotSlots(BotPlayer bot) {
        Container botContainer = bot.getInventory();
        // 36 ordinary slots (0..35). The vanilla player inventory is laid out as 9 hotbar +
        // 27 main; here we show all 36 as a 4x9 grid (rows 0..3), index = row*9+col.
        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < 9; col++) {
                int index = row * 9 + col;
                addSlot(new Slot(botContainer, index, 8 + col * 18, 26 + row * 18));
            }
        }
        // 4 armor slots (index 36..39): a single column on the left.
        for (int i = 0; i < 4; i++) {
            addSlot(new Slot(botContainer, 36 + i, 8, 26 + i * 18));
        }
        // The selected main-hand slot (0..8) is already part of the ordinary grid; it is
        // intentionally not duplicated here to avoid double-counting the same stack.
    }

    private void buildPlayerSlots(Inventory playerInv) {
        // Player main inventory 27 (rows 3) + hotbar 9.
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(playerInv, 9 + row * 9 + col, 8 + col * 18, 140 + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(playerInv, col, 8 + col * 18, 198));
        }
    }

    /** Build a menu from server state (menu type create path). Uses the last opened bot. */
    public static BotInventoryMenu fromServer(int containerId, Inventory playerInv) {
        BotPlayer target = OPEN_BOT;
        if (target == null) {
            BotLog.warn("bot_inventory menu rebuild with no open bot; showing player slots only");
        }
        return new BotInventoryMenu(containerId, playerInv, target);
    }

    public BotPlayer bot() {
        return bot;
    }

    public boolean taskActive() {
        return taskActive;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = this.slots.get(index);
        if (slot == null || !slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slot.getItem();
        if (taskActive) {
            // Read-only while the bot is busy: block quick-move entirely (both directions
            // touch bot-owned slots).
            return ItemStack.EMPTY;
        }
        if (index < BOT_SLOT_CAP) {
            return moveItemStackTo(stack, BOT_SLOT_CAP, this.slots.size(), true) ? stack : ItemStack.EMPTY;
        }
        return moveItemStackTo(stack, 0, BOT_SLOT_CAP, false) ? stack : ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        // Always openable for viewing; write protection is enforced per-action by taskActive.
        return true;
    }

    @Override
    public void clicked(int slotId, int button, ClickType clickType, Player player) {
        if (taskActive && slotId >= 0 && slotId < BOT_SLOT_CAP) {
            // Read-only while busy: swallow the click, leaving inventory untouched.
            return;
        }
        super.clicked(slotId, button, clickType, player);
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        if (OPEN_BOT == bot) {
            OPEN_BOT = null;
        }
    }
}
