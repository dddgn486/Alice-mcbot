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
 * Informational container menu over a bot's inventory. The layout mirrors the vanilla
 * {@code InventoryMenu} (player backpack) so the grid, armor column, offhand and player
 * slots line up with the {@code inventory.png} background. The bot's 36 ordinary slots,
 * 4 armor slots (36..39), offhand (40) and the player's own inventory are laid out
 * without overlap. The main-hand slot is the bot's {@code selected} ordinary slot (0..8)
 * and is already part of the ordinary grid, so it is not duplicated.
 * <p>
 * While the bot runs a task ({@code taskActive}, via {@link BotManager#isBusy}) the bot
 * slots are forced read-only so a user cannot corrupt an in-progress transfer/mine. The
 * menu is opened over the <em>requesting player's</em> real connection via
 * {@code player.openMenu(modelProvider)}; it never uses {@code bot.connection.send}
 * (a FakeConnection no-op).
 */
public final class BotInventoryMenu extends AbstractContainerMenu {

    /** Last bot opened, used only to rebuild a menu on server-side resize. */
    private static BotPlayer OPEN_BOT;

    /** Bot-owned slot count (36 ordinary + 4 armor + 1 offhand); indices below this are bot-owned. */
    private static final int BOT_SLOT_CAP = 41;

    private final BotPlayer bot;
    private final boolean taskActive;

    public BotInventoryMenu(int containerId, Inventory playerInv, BotPlayer bot) {
        super(ModMenuTypes.BOT_INVENTORY_MENU.get(), containerId);
        this.bot = bot;
        this.taskActive = bot != null && BotManager.isBusy(bot);
        OPEN_BOT = bot;

        int botSlots = 0;
        if (bot != null) {
            botSlots = buildBotSlots(bot);
        } else {
            BotLog.warn("bot_inventory opened with null bot; showing player slots only");
        }
        buildPlayerSlots(playerInv, botSlots);
    }

    /**
     * Adds the bot's slots using the vanilla inventory-menu geometry:
     * armor column (x=8, y=8/26/44/62), offhand (x=77, y=62), 27 main (y=84..), 9 hotbar (y=142).
     * Returns the number of bot slots added (used as the base index for the player grid).
     */
    private int buildBotSlots(BotPlayer bot) {
        Container botContainer = bot.getInventory();
        int base = 0;
        // Armor column (index 39=head, 38=chest, 37=legs, 36=feet), top to bottom.
        for (int i = 0; i < 4; i++) {
            addSlot(new Slot(botContainer, 39 - i, 8, 8 + i * 18));
            base++;
        }
        // Offhand (index 40).
        addSlot(new Slot(botContainer, 40, 77, 62));
        base++;
        // 27 main inventory (index 9..35).
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(botContainer, 9 + col + row * 9, 8 + col * 18, 84 + row * 18));
                base++;
            }
        }
        // 9 hotbar (index 0..8).
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(botContainer, col, 8 + col * 18, 142));
            base++;
        }
        return base;
    }

    /**
     * Adds the player's own inventory slots below the bot grid, reusing the same geometry
     * but shifted down so it does not overlap the bot section. The player grid is placed
     * from y=180 onward.
     */
    private void buildPlayerSlots(Inventory playerInv, int botSlots) {
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(playerInv, 9 + col + row * 9, 8 + col * 18, 180 + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(playerInv, col, 8 + col * 18, 238));
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

    /** Total bot slot count (used by fixture/render to verify layout). */
    public static int botSlotCount() {
        return BOT_SLOT_CAP; // 36 ordinary + 4 armor + 1 offhand
    }

    /** Base index where the player's own inventory slots begin. */
    public int playerSlotBase() {
        return BOT_SLOT_CAP;
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
        // Vanilla ChestMenu semantics: keep a copy of the pre-move stack to return, move the
        // real stack, then sync the source slot back to the container so server/client agree.
        ItemStack original = stack.copy();
        boolean moved;
        if (index < BOT_SLOT_CAP) {
            moved = moveItemStackTo(stack, BOT_SLOT_CAP, this.slots.size(), true);
        } else {
            moved = moveItemStackTo(stack, 0, BOT_SLOT_CAP, false);
        }
        if (!moved) {
            return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) {
            slot.set(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        return original;
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
