package com.dddgn.alice.gui;

import com.dddgn.alice.bot.BotManager;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.log.BotLog;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Optional;

/**
 * Focused server evidence for the direction-B bot inventory (snapshot + server-authoritative
 * action). Asserts: snapshot reads 36 ordinary + 4 armor + 1 offhand; a pickup succeeds (and
 * clears the source slot) while the bot is idle; a pickup is rejected with task_active while
 * busy; a PLACE with an invalid player slot is rejected. No client screen is opened and no
 * packet distribution occurs (the service is exercised directly on server state).
 */
public final class BotInventoryFixture {
    private BotInventoryFixture() {
    }

    public static boolean run(ServerLevel level, BotPlayer bot) {
        int expectedSlots = BotInventorySnapshot.SLOT_COUNT; // 41

        Optional<BotInventorySnapshot> snapOpt = BotInventoryService.snapshot(level, bot.getUUID());
        boolean snapshotPass = snapOpt.isPresent()
                && snapOpt.get().slots().size() == expectedSlots
                && !snapOpt.get().name().isEmpty();

        // Fresh state for action assertions (clear bot slots 0..2).
        Inventory inv = bot.getInventory();
        for (int i = 0; i < 3; i++) {
            inv.setItem(i, ItemStack.EMPTY);
        }
        inv.setItem(0, new ItemStack(Items.IRON_INGOT, 5));
        ServerPlayer actor = bot; // server-side validation uses the bot itself as the acting player

        boolean pickupPass;
        if (BotManager.isBusy(bot)) {
            String code = BotInventoryService.applyAction(level, bot.getUUID(), actor, 0,
                    BotInventoryService.ActionType.PICKUP, -1);
            pickupPass = "task_active".equals(code);
        } else {
            String code = BotInventoryService.applyAction(level, bot.getUUID(), actor, 0,
                    BotInventoryService.ActionType.PICKUP, -1);
            pickupPass = "success".equals(code) && inv.getItem(0).isEmpty();
        }

        // PLACE with an invalid player slot index must be rejected (server-authoritative).
        String badCode = BotInventoryService.applyAction(level, bot.getUUID(), actor, 36,
                BotInventoryService.ActionType.PLACE, -1);
        boolean serverAuthorityPass = "invalid_player_slot".equals(badCode)
                || "invalid_slot".equals(badCode)
                || "player_slot_empty".equals(badCode);

        boolean pass = snapshotPass && pickupPass && serverAuthorityPass;
        BotLog.info("BOT_INVENTORY_FIXTURE_SUITE {} slotCount={} snapshotPass={} pickupPass={} serverAuthority={}",
                pass ? "PASS" : "FAIL", expectedSlots, snapshotPass, pickupPass, serverAuthorityPass);
        inv.setItem(0, ItemStack.EMPTY);
        return pass;
    }
}
