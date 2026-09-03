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
    static BotPlayer OPEN_BOT;

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
            // 服务端：使用真实的 bot inventory
            buildBotSlots(bot);
        } else {
            // 客户端：bot 为 null 时，创建假容器（槽数必须和服务端一致）
            BotLog.warn("bot_inventory opened with null bot; using dummy container for client sync");
            buildDummyBotSlots(playerInv);
        }
        buildPlayerSlots(playerInv);
    }

    /**
     * Adds the bot's slots with standard layout:
     * - Armor column (left, x=8)
     * - Offhand slot (x=77, y=8)
     * - 27 main inventory (3×9, y=84..)
     * - 9 hotbar (1×9, y=142)
     */
    private void buildBotSlots(BotPlayer bot) {
        Inventory botContainer = bot.getInventory();
        
        // Armor column (index 39=head, 38=chest, 37=legs, 36=feet), top to bottom.
        for (int i = 0; i < 4; i++) {
            addSlot(new BotArmorSlot(botContainer, 39 - i, 8, 8 + i * 18, bot, ARMOR_SLOT_IDS[i]));
        }
        
        // Offhand (index 40).
        addSlot(new BotOffhandSlot(botContainer, 40, 77, 8, bot));
        
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
        
        BotLog.info("[GUI_DEBUG] buildBotSlots completed with real bot inventory");
    }
    
    /**
     * 客户端使用：创建假容器槽（槽数必须和服务端一致）
     */
    private void buildDummyBotSlots(Inventory playerInv) {
        // 创建临时容器（41 个槽）
        net.minecraft.world.SimpleContainer dummyContainer = new net.minecraft.world.SimpleContainer(41);
        
        // 创建相同数量和位置的槽（但使用假容器）
        // 4 armor slots
        for (int i = 0; i < 4; i++) {
            addSlot(new Slot(dummyContainer, i, 8, 8 + i * 18));
        }
        
        // 1 offhand slot
        addSlot(new Slot(dummyContainer, 4, 77, 8));
        
        // 27 main inventory (index 5..31 in dummy)
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(dummyContainer, 5 + col + row * 9, 8 + col * 18, 84 + row * 18));
            }
        }
        
        // 9 hotbar (index 32..40 in dummy)
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(dummyContainer, 32 + col, 8 + col * 18, 142));
        }
        
        BotLog.info("[GUI_DEBUG] buildDummyBotSlots completed with dummy container (41 slots)");
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
        ItemStack original = stack.copy();
        
        BotLog.info("[GUI_DEBUG] quickMove BEFORE: index={}, slotItem={}, stackCount={}, taskActive={}",
                index, slot.getItem(), stack.getCount(), taskActive);
        
        if (taskActive) {
            // Read-only while the bot is busy: block quick-move entirely (both directions
            // touch bot-owned slots).
            BotLog.info("[GUI_DEBUG] quickMove blocked (taskActive)");
            return ItemStack.EMPTY;
        }

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
        
        BotLog.info("[GUI_DEBUG] quickMove AFTER move: index={}, moved={}, stackNow={}, slotNow={}",
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
        
        BotLog.info("[GUI_DEBUG] quickMove AFTER sync: count={}, slotNowAfterSync={}",
                original.getCount(), slot.getItem());
        
        // Force-sync bot slots after quick move
        forceSyncBotSlots();
        BotLog.info("[GUI_DEBUG] quickMove completed -> forced sync ALL {} bot slots", BOT_SLOT_CAP);
        
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
        // ✅ 关键修复：只在服务端执行，客户端只发送 packet
        if (player.level().isClientSide()) {
            BotLog.info("[GUI_DEBUG] clicked on CLIENT side, skipping (will send packet to server)");
            return;
        }
        
        boolean botSlot = slotId >= 0 && slotId < BOT_SLOT_CAP;
        
        // 日志探针：点击前状态
        if (botSlot && slotId < this.slots.size()) {
            ItemStack slotBefore = this.slots.get(slotId).getItem().copy();
            ItemStack carriedBefore = this.getCarried().copy();
            
            // 关键探针：直接读取 bot.getInventory()
            ItemStack botInvBefore = bot != null ? bot.getInventory().getItem(this.slots.get(slotId).getContainerSlot()) : ItemStack.EMPTY;
            
            BotLog.info("[GUI_DEBUG] clicked BEFORE: slotId={}, button={}, clickType={}, botSlot={}, taskActive={}, slotItem={}, carried={}, botInv[{}]={}",
                    slotId, button, clickType, botSlot, taskActive, slotBefore, carriedBefore, 
                    slotId, botInvBefore.isEmpty() ? "EMPTY" : botInvBefore.getCount() + "x" + botInvBefore.getItem());
        } else {
            BotLog.info("[GUI_DEBUG] clicked BEFORE: slotId={}, button={}, clickType={}, botSlot={}, taskActive={}",
                    slotId, button, clickType, botSlot, taskActive);
        }
        
        if (taskActive && botSlot) {
            // Read-only while busy: swallow the click, leaving inventory untouched.
            BotLog.info("[GUI_DEBUG] clicked swallowed (read-only while busy) slotId={}", slotId);
            return;
        }
        
        super.clicked(slotId, button, clickType, player);
        
        // 日志探针：点击后状态
        if (botSlot && slotId < this.slots.size()) {
            ItemStack slotAfter = this.slots.get(slotId).getItem().copy();
            ItemStack carriedAfter = this.getCarried().copy();
            
            // 关键探针：直接读取 bot.getInventory() 看是否真的变化了
            ItemStack botInvAfter = bot != null ? bot.getInventory().getItem(this.slots.get(slotId).getContainerSlot()) : ItemStack.EMPTY;
            
            BotLog.info("[GUI_DEBUG] clicked AFTER super: slotId={}, slotItem={}, carried={}, botInv[{}]={}",
                    slotId, slotAfter, carriedAfter, slotId, 
                    botInvAfter.isEmpty() ? "EMPTY" : botInvAfter.getCount() + "x" + botInvAfter.getItem());
        }
        
        // Force-sync bot slots to client after any operation touching bot inventory.
        // Reason: bot's Inventory.setItem/removeItem does not call setChanged(), and bot's
        // FakeConnection.send() is a no-op. Marking bot slots changed triggers broadcastChanges()
        // via the player's real connection, ensuring the client GUI shows updated bot inventory.
        if (botSlot || clickType == ClickType.QUICK_MOVE) {
            forceSyncBotSlots();
            BotLog.info("[GUI_DEBUG] clicked {} botSlot={} -> forced sync ALL {} bot slots", clickType, botSlot, BOT_SLOT_CAP);
        }
        
        // 日志探针：强制同步后
        if (botSlot && slotId < this.slots.size()) {
            ItemStack slotFinal = this.slots.get(slotId).getItem().copy();
            ItemStack botInvFinal = bot != null ? bot.getInventory().getItem(this.slots.get(slotId).getContainerSlot()) : ItemStack.EMPTY;
            BotLog.info("[GUI_DEBUG] clicked FINAL: slotId={}, slotItem={}, botInv[{}]={}",
                    slotId, slotFinal, slotId, 
                    botInvFinal.isEmpty() ? "EMPTY" : botInvFinal.getCount() + "x" + botInvFinal.getItem());
        }
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        if (OPEN_BOT == bot) {
            OPEN_BOT = null;
        }
    }
    
    @Override
    public void broadcastChanges() {
        // 日志探针：同步前检查 bot 槽位状态
        if (bot != null) {
            StringBuilder sb = new StringBuilder("[GUI_DEBUG] broadcastChanges BEFORE: ");
            for (int i = 0; i < Math.min(5, BOT_SLOT_CAP); i++) {
                ItemStack slotItem = this.slots.get(i).getItem();
                sb.append(String.format("slot[%d]=%s, ", i, slotItem.isEmpty() ? "EMPTY" : slotItem.getItem()));
            }
            BotLog.info(sb.toString());
        }
        
        super.broadcastChanges();
        
        // 日志探针：同步后
        if (bot != null) {
            BotLog.info("[GUI_DEBUG] broadcastChanges AFTER: called super");
        }
    }
    
    /** Force-sync all bot slots to the client by marking them changed. */
    private void forceSyncBotSlots() {
        for (int i = 0; i < BOT_SLOT_CAP; i++) {
            this.slots.get(i).setChanged();
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

        // ✅ 修复：副手应该能堆叠到物品的最大堆叠数，而不是只能放 1 个
        // 原版副手槽不限制堆叠数量
        // @Override
        // public int getMaxStackSize() {
        //     return 1;
        // }

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
