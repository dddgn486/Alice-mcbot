package com.dddgn.alice.gui;

import com.dddgn.alice.bot.BotManager;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.log.BotLog;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;

/**
 * Informational container menu over a bot's inventory, modelled on the vanilla
 * {@code InventoryMenu}: the bot's backpack uses the vanilla slot geometry (armor column
 * 8/26/44/62, offhand at 77,62, 27 main at 84.., 9 hotbar at 142) and armor/offhand use
 * dedicated {@code Slot} subclasses ({@link BotArmorSlot}/{@link BotOffhandSlot}) that mirror
 * the vanilla {@code InventoryMenu$1/$2} (getMaxStackSize=1, mayPlace=canEquip, mayPickup
 * honours the binding curse, setByPlayer triggers on-equip). The player's own inventory is
 * laid out below the bot grid. The main-hand slot is the bot's {@code selected} ordinary slot
 * (0..8), already part of the hotbar grid, so it is not duplicated.
 * <p>
 * While the bot runs a task ({@code taskActive}, via {@link BotManager#isBusy}) the bot slots
 * are forced read-only so a user cannot corrupt an in-progress transfer/mine. The menu is
 * opened over the <em>requesting player's</em> real connection via
 * {@code player.openMenu(modelProvider)}; it never uses {@code bot.connection.send}
 * (a FakeConnection no-op).
 */
public final class BotInventoryMenu extends AbstractContainerMenu {

    /** Last bot opened, used only to rebuild a menu on server-side resize. */
    private static BotPlayer OPEN_BOT;

    /** Bot-owned slot count (36 ordinary + 4 armor + 1 offhand); indices below this are bot-owned. */
    private static final int BOT_SLOT_CAP = 41;

    private static final EquipmentSlot[] ARMOR_SLOT_IDS = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };

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

    /**
     * Adds the bot's slots using the vanilla inventory-menu geometry:
     * armor column (x=8, y=8/26/44/62), offhand (x=77, y=62), 27 main (y=84..), 9 hotbar (y=142).
     * Armor/offhand use custom {@code Slot} subclasses mirroring InventoryMenu$1/$2.
     */
    private void buildBotSlots(BotPlayer bot) {
        Inventory botContainer = bot.getInventory();
        // Armor column (index 39=head, 38=chest, 37=legs, 36=feet), top to bottom.
        for (int i = 0; i < 4; i++) {
            addSlot(new BotArmorSlot(botContainer, 39 - i, 8, 8 + i * 18, bot, ARMOR_SLOT_IDS[i]));
        }
        // Offhand (index 40).
        addSlot(new BotOffhandSlot(botContainer, 40, 77, 62, bot));
        // 27 main inventory (index 9..35).
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(botContainer, 9 + col + row * 9, 8 + col * 18, 84 + row * 18));
            }
        }
        // 9 hotbar (index 0..8).
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(botContainer, col, 8 + col * 18, 142));
        }
    }

    /** Adds the player's own inventory slots below the bot grid, reusing vanilla geometry. */
    private void buildPlayerSlots(Inventory playerInv) {
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
        BotLog.info("[GUI_DEBUG] quickMove before: index={}, slotItem={}, stackCount={}, taskActive={}",
                index, slot.getItem(), stack.getCount(), taskActive);
        if (taskActive) {
            // Read-only while the bot is busy: block quick-move entirely (both directions
            // touch bot-owned slots).
            return ItemStack.EMPTY;
        }

        ItemStack original = stack.copy();
        EquipmentSlot equipmentSlot = LivingEntity.getEquipmentSlotForItem(stack);

        boolean moved;
        if (index < BOT_SLOT_CAP) {
            // Bot slot -> player grid: forward-move.
            moved = moveItemStackTo(stack, BOT_SLOT_CAP, this.slots.size(), false);
        } else if (equipmentSlot.getType() == EquipmentSlot.Type.ARMOR) {
            // Player item is armor -> move to the matching bot armor slot if free.
            int armorSlot = armorSlotIndex(equipmentSlot);
            if (!this.slots.get(armorSlot).hasItem()) {
                moved = moveItemStackTo(stack, armorSlot, armorSlot + 1, false);
            } else {
                moved = false; // armor slot occupied; do not move (fall through to keep original)
            }
        } else if (equipmentSlot == EquipmentSlot.OFFHAND) {
            // Player item is offhand -> move to bot offhand slot (index 4) if free.
            int offhandSlot = 4;
            if (!this.slots.get(offhandSlot).hasItem()) {
                moved = moveItemStackTo(stack, offhandSlot, offhandSlot + 1, false);
            } else {
                moved = false;
            }
        } else {
            // Player slot -> bot grid: forward-move.
            moved = moveItemStackTo(stack, 0, BOT_SLOT_CAP, false);
        }
        BotLog.info("[GUI_DEBUG] quickMove after: index={}, moved={}, stackNow={}, slotNow={}",
                index, moved, stack, slot.getItem());
        if (!moved) {
            return ItemStack.EMPTY;
        }

        // Sync the source slot back to the container so server/client stay in agreement.
        if (stack.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        if (stack.getCount() == original.getCount()) {
            return ItemStack.EMPTY;
        }
        slot.onTake(player, stack);
        BotLog.info("[GUI_DEBUG] quickMove returned original: count={}, slotNowAfterSync={}",
                original.getCount(), slot.getItem());
        return original;
    }

    private int armorSlotIndex(EquipmentSlot slot) {
        // bot armor slots occupy menu indices 0..3 (HEAD, CHEST, LEGS, FEET in that order).
        return switch (slot) {
            case HEAD -> 0;
            case CHEST -> 1;
            case LEGS -> 2;
            case FEET -> 3;
            default -> 0;
        };
    }

    @Override
    public boolean stillValid(Player player) {
        // Always openable for viewing; write protection is enforced per-action by taskActive.
        return true;
    }

    @Override
    public void clicked(int slotId, int button, ClickType clickType, Player player) {
        boolean botSlot = slotId >= 0 && slotId < BOT_SLOT_CAP;
        BotLog.info("[GUI_DEBUG] clicked before: slotId={}, button={}, clickType={}, botSlot={}, taskActive={}",
                slotId, button, clickType, botSlot, taskActive);
        if (taskActive && botSlot) {
            // Read-only while busy: swallow the click, leaving inventory untouched.
            BotLog.info("[GUI_DEBUG] clicked swallowed (read-only while busy) slotId={}", slotId);
            return;
        }
        super.clicked(slotId, button, clickType, player);
        BotLog.info("[GUI_DEBUG] clicked after: slotId={}, button={}, clickType={}",
                slotId, button, clickType);
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        if (OPEN_BOT == bot) {
            OPEN_BOT = null;
        }
    }

    /** Armor slot with vanilla InventoryMenu$1 semantics (max stack 1, canEquip, binding-curse). */
    static final class BotArmorSlot extends Slot {
        private final BotPlayer owner;
        private final EquipmentSlot equipmentslot;

        BotArmorSlot(Container container, int index, int x, int y, BotPlayer owner, EquipmentSlot equipmentslot) {
            super(container, index, x, y);
            this.owner = owner;
            this.equipmentslot = equipmentslot;
        }

        @Override
        public int getMaxStackSize() {
            return 1;
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return !stack.isEmpty() && stack.canEquip(equipmentslot, owner);
        }

        @Override
        public boolean mayPickup(Player player) {
            ItemStack stack = this.getItem();
            return stack.isEmpty() || player.isCreative() || !EnchantmentHelper.hasBindingCurse(stack);
        }

        @Override
        public void setByPlayer(ItemStack stack) {
            // Mirror the vanilla on-equip hook (the bot's inventory is authoritative; no
            // client-side equip packet is sent from here).
            super.setByPlayer(stack);
        }
    }

    /** Offhand slot with vanilla InventoryMenu$2 semantics (max stack 1, binding-curse pickup). */
    static final class BotOffhandSlot extends Slot {
        private final BotPlayer owner;

        BotOffhandSlot(Container container, int index, int x, int y, BotPlayer owner) {
            super(container, index, x, y);
            this.owner = owner;
        }

        @Override
        public int getMaxStackSize() {
            return 1;
        }

        @Override
        public boolean mayPickup(Player player) {
            ItemStack stack = this.getItem();
            return stack.isEmpty() || player.isCreative() || !EnchantmentHelper.hasBindingCurse(stack);
        }

        @Override
        public void setByPlayer(ItemStack stack) {
            super.setByPlayer(stack);
        }
    }
}
