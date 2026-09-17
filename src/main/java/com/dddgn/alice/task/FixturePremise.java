package com.dddgn.alice.task;

import com.dddgn.alice.bot.BotPlayer;
import net.minecraft.world.inventory.InventoryMenu;

/**
 * **夹具前提自证**（2026-09-13，D-201 附注一/二）：把"环境是干净的"从**隐含假设**变成**显式断言**。
 *
 * <p>为什么要有这个类（两次实测事故的教训）：
 * <ul>
 *   <li>一次 CORE 瘦身撤走 8 步后，`craft_furnace`/`craft_cooking`/`transfer` **可复现变红**，
 *       而它们在 FULL 里全绿 ⇒ 撤走的是它们的**隐含前置/清场**；</li>
 *   <li>`capability_gate` 曾在 `ticks=1` 假红：命令入口没传 observer ⇒ 用例**静默退化**成一个必然失败的变体，
 *       失败原因却长得像"归因坏了"。</li>
 * </ul>
 *
 * <p>所以规矩是：**每个夹具在自己开始动手之前，把自己依赖的前提逐条断言出来**；失败时报**有名字的前提码**
 * （`premise_*`），而不是含糊的 FAIL，也不许默认"上一步已经帮我清好了"。
 */
public final class FixturePremise {

    /** 一条前提事实（夹具直接 `check(fact.name(), fact.ok(), fact.detail())` 即可）。 */
    public record Fact(String name, boolean ok, String detail) {
    }

    private FixturePremise() {
    }

    /**
     * **前提：当前没有别人的容器菜单挂着**（只能是自己`InventoryMenu`或没有）。
     *
     * <p>为什么这是最容易踩的一条：上游把页签槽位建在 `menu.slots` 之外、且**地址会重叠**
     * （例：精妙容器菜单的 64..66 既是合成页签的格子、也是熔炼页签的格子）⇒ 若夹具在**错误的菜单**上点击，
     * 点击**会被接受**、但东西落到了别处，症状是"料放进去了却不烧"。
     */
    public static Fact ownMenu(BotPlayer bot) {
        var menu = bot.containerMenu;
        if (menu == null || menu instanceof InventoryMenu) {
            return new Fact("premise_own_menu", true,
                    "menu=" + (menu == null ? "null" : menu.getClass().getSimpleName()));
        }
        return new Fact("premise_own_menu", false,
                "当前挂着别的容器菜单 " + menu.getClass().getSimpleName() + " slots=" + menu.slots.size()
                        + " ⇒ 先关掉它再动手（否则点击可能落到错误菜单上）");
    }

    /**
     * **前提：站点（容器）菜单确实打开了** —— 与 {@link #ownMenu} **相反**的场景。
     *
     * <p>2026-09-13 实测教训：`CraftFurnaceCheckTask.discover()` 里我一开始写的是 `ownMenu`
     * （要求"菜单是玩家自带的"）⇒ 对"**本步刚自己打开了熔炉/熔炼页签菜单**"的夹具来说**断言写反了**，
     * 于是 `craft_furnace`/`craft_cooking` 双双以 `premise_own_menu` 假红。
     * ⇒ 前提必须**按调用点写**：动手**之前**用 `ownMenu`，打开站点**之后**用本方法。
     */
    public static Fact stationMenuOpen(BotPlayer bot) {
        var menu = bot.containerMenu;
        boolean open = menu != null && !(menu instanceof InventoryMenu);
        return new Fact("premise_station_menu_open", open,
                open ? "menu=" + menu.getClass().getSimpleName() + " slots=" + menu.slots.size()
                        : "站点菜单没打开（menu=" + (menu == null ? "null" : menu.getClass().getSimpleName())
                                + "）⇒ 后续点击会落到玩家背包上");
    }

    /** **前提：bot 站在地上**（不许在空中开始"放料/点菜单"这类动作）。 */
    public static Fact onGround(BotPlayer bot) {
        return new Fact("premise_on_ground", bot.onGround(),
                "onGround=" + bot.onGround() + " pos=" + bot.blockPosition().toShortString());
    }

    /**
     * **传送之后至少经过的物理结算 tick 数**（D-187 §6.9；2026-09-17 三个夹具实测）。
     *
     * <p>为什么是常量、而且必须共享：`teleportTo` 的**那一 tick**，`bot.onGround()` 读到的仍是
     * **上一处**的状态（物理要到下一 tick 才重算）⇒ 同一个错误读法会给出**两种相反的假判决**：
     * <ul>
     *   <li>**假绿**：上一处站在地上 ⇒ 读到 true ⇒ "前提自证"通过，可下一步真开菜单时
     *       `MenuSession` 的 K-3 门硬拒（`menu_not_settled`，`machine_station` 单跑就是这个 ✗）；</li>
     *   <li>**假红**：上一处在空中/刚被传送 ⇒ 读到 false ⇒ 前提当场判红，而 bot 其实好好站在地上
     *       （`machine_cycle` / `craft_machine` 在 CORE 里就是这个 ✗，且**只在模块化之后**才出现 ——
     *       旧电池恰好让"上一步"把 bot 留成了站姿，纯属运气 ✓）。</li>
     * </ul>
     *
     * <p>⇒ **夹具纪律**：传送的那一 tick 只 `record` 原始读数（可 grep 留痕），**不作为通过判据**；
     * 落地前提一律用 {@link #settledOnGround} 在后续 tick 复核。
     */
    public static final int SETTLE_TICKS = 2;

    /** **传送后的落地前提**：必须"过了 {@link #SETTLE_TICKS} tick"**且**真的站在地上（事故记录见那里）。 */
    public static boolean settledOnGround(BotPlayer bot, int ticksSinceTeleport) {
        return ticksSinceTeleport > SETTLE_TICKS && bot.onGround();
    }
}
