package com.dddgn.alice.gui;

import com.dddgn.alice.bot.BotManager;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.log.BotLog;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Inventory;

/**
 * Focused server evidence for the bot inventory menu: asserts the menu can be constructed
 * over a running bot, exposes the expected 36-ordinary + 4-armor bot slots plus the player
 * slots, and that the busy/read-only flag reflects {@link BotManager#isBusy}. It does not
 * open a real client screen and never mutates the bot inventory.
 */
public final class BotInventoryFixture {
    private BotInventoryFixture() {
    }

    public static boolean run(ServerLevel level, BotPlayer bot) {
        int botSlotCount = 40; // 36 ordinary + 4 armor
        int playerSlotCount = 36; // 27 main + 9 hotbar
        int expectedTotal = botSlotCount + playerSlotCount;

        // playerInv is only used to register the player grid; reuse bot inventory as a stand-in
        // for the fixture (no slot mutation occurs on construction).
        BotInventoryMenu menu = new BotInventoryMenu(1, bot.getInventory(), bot);
        boolean countPass = menu.slots.size() == expectedTotal;
        boolean busyFlagPass = (menu.taskActive() == BotManager.isBusy(bot));
        boolean idleLockFree = !menu.taskActive();
        boolean pass = countPass && busyFlagPass && idleLockFree;
        BotLog.info("BOT_INVENTORY_FIXTURE_SUITE {} slotCount={} expected={} busyFlag={} botBusy={}",
                pass ? "PASS" : "FAIL", menu.slots.size(), expectedTotal, menu.taskActive(), BotManager.isBusy(bot));
        return pass;
    }
}
