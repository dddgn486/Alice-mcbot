package com.dddgn.alice.gui;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

/**
 * Alice custom container menu types. The menu is opened over the requesting player's
 * real connection via {@code player.openMenu(...)}; the registered factory only needs
 * to rebuild a menu from server state (e.g. on resize), so it reads the first bot in
 * the executor's level. This is a read-heavy/informational menu; it never writes the
 * bot inventory outside the read-only task guard.
 */
public final class ModMenuTypes {

    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(Registries.MENU, "alice");

    public static final RegistryObject<MenuType<BotInventoryMenu>> BOT_INVENTORY_MENU =
            MENUS.register("bot_inventory",
                    () -> IForgeMenuType.create((id, inv, buf) ->
                            BotInventoryMenu.fromServer(id, inv)));

    private ModMenuTypes() {
    }
}
