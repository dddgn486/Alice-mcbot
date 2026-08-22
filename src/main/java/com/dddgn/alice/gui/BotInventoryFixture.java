package com.dddgn.alice.gui;

import com.dddgn.alice.bot.BotManager;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.log.BotLog;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Focused server evidence for the bot inventory menu: asserts the menu can be constructed
 * over a running bot, exposes the expected bot slots (36 ordinary + 4 armor + 1 offhand)
 * plus the player slots, that the slot coordinates do not overlap, that quick-move from a
 * bot slot toward an independent player grid succeeds, and that the busy/read-only flag
 * reflects {@link BotManager#isBusy}. It does not open a real client screen.
 */
public final class BotInventoryFixture {
    private BotInventoryFixture() {
    }

    public static boolean run(ServerLevel level, BotPlayer bot) {
        int botSlotCount = 41; // 36 ordinary + 4 armor + 1 offhand
        int playerSlotCount = 36; // 27 main + 9 hotbar
        int expectedTotal = botSlotCount + playerSlotCount;

        // Spawn an independent viewer bot to supply a separate player inventory; the viewer's
        // grid is the move target in quickMoveOut. It is removed after the fixture.
        BotPlayer viewer = null;
        Inventory viewerInv;
        try {
            viewer = BotManager.spawn(level, new BlockPos((int) (level.getSeed() % 1000), 60, (int) (level.getSeed() % 1000)), "ViewerBot");
            viewerInv = viewer.getInventory();
        } catch (Exception e) {
            BotLog.info("BOT_INVENTORY_FIXTURE viewer_spawn FAIL e={}", e.getClass().getSimpleName());
            return false;
        }

        BotInventoryMenu menu = new BotInventoryMenu(1, viewerInv, bot);
        boolean countPass = menu.slots.size() == expectedTotal;
        boolean busyFlagPass = (menu.taskActive() == BotManager.isBusy(bot));
        boolean idleLockFree = !menu.taskActive();
        boolean noOverlapPass = slotsDoNotOverlap(menu);
        boolean quickMoveOutPass = quickMoveFromBotSucceeds(menu, bot, viewerInv);

        boolean pass = countPass && busyFlagPass && idleLockFree && noOverlapPass && quickMoveOutPass;
        BotLog.info("BOT_INVENTORY_FIXTURE_SUITE {} slotCount={} expected={} busyFlag={} noOverlap={} quickMoveOut={}",
                pass ? "PASS" : "FAIL", menu.slots.size(), expectedTotal, menu.taskActive(),
                noOverlapPass, quickMoveOutPass);
        if (viewer != null) {
            BotManager.remove(viewer);
        }
        return pass;
    }

    /** Verifies no two slots share the same (x,y) cell, i.e. the grid is non-overlapping. */
    private static boolean slotsDoNotOverlap(BotInventoryMenu menu) {
        boolean[] seen = new boolean[200 * 300];
        for (int i = 0; i < menu.slots.size(); i++) {
            var slot = menu.slots.get(i);
            if (slot.x < 0 || slot.y < 0) {
                return false;
            }
            int key = slot.y * 200 + slot.x;
            if (key < 0 || key >= seen.length) {
                return false;
            }
            if (seen[key]) {
                BotLog.info("BOT_INVENTORY_FIXTURE overlapping slot at x={} y={}", slot.x, slot.y);
                return false;
            }
            seen[key] = true;
        }
        return true;
    }

    /**
     * Places a real stack in a bot slot and runs quickMoveStack on that index to assert the
     * vanilla ChestMenu semantics: the pre-move stack copy is returned (non-empty), the item
     * lands in the independent viewer grid, and the source bot slot is cleared/synced. The
     * earlier buggy path returned the cleared source stack or failed to sync the slot.
     */
    private static boolean quickMoveFromBotSucceeds(BotInventoryMenu menu, BotPlayer bot, Inventory viewerInv) {
        Inventory inv = bot.getInventory();
        for (int i = 0; i < 3; i++) {
            inv.setItem(i, ItemStack.EMPTY);
            viewerInv.setItem(i, ItemStack.EMPTY);
        }
        inv.setItem(0, new ItemStack(Items.IRON_INGOT, 5));
        // Find the menu slot index that maps to bot inventory index 0 (the hotbar slot 0).
        int botSlotIndex = -1;
        for (int i = 0; i < menu.slots.size(); i++) {
            if (menu.slots.get(i).getContainerSlot() == 0) {
                botSlotIndex = i;
                break;
            }
        }
        if (botSlotIndex < 0) {
            BotLog.info("BOT_INVENTORY_FIXTURE quickMoveOut FAIL no_slot_for_bot_0");
            inv.setItem(0, ItemStack.EMPTY);
            return false;
        }
        ItemStack returned = menu.quickMoveStack(bot, botSlotIndex);
        // Semantic 1: the returned stack is the pre-move copy (still 5 iron_ingot), not cleared.
        boolean returnedCopyPass = returned != null && !returned.isEmpty() && returned.getCount() == 5;
        // Semantic 2: the item landed in the independent viewer grid.
        boolean landed = false;
        for (int i = 0; i < viewerInv.getContainerSize(); i++) {
            if (viewerInv.getItem(i).is(Items.IRON_INGOT)) {
                landed = true;
                break;
            }
        }
        // Semantic 3: the source bot slot was cleared/synced (no lingering stack).
        boolean sourceCleared = inv.getItem(0).isEmpty();
        boolean pass = returnedCopyPass && landed && sourceCleared;
        BotLog.info("BOT_INVENTORY_FIXTURE quickMoveOut {} returnedCopy={} landed={} sourceCleared={}",
                pass ? "PASS" : "FAIL", returnedCopyPass, landed, sourceCleared);
        inv.setItem(0, ItemStack.EMPTY);
        return pass;
    }
}
