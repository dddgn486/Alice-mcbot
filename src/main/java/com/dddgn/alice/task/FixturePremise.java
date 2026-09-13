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

    /** **前提：bot 站在地上**（不许在空中开始"放料/点菜单"这类动作）。 */
    public static Fact onGround(BotPlayer bot) {
        return new Fact("premise_on_ground", bot.onGround(),
                "onGround=" + bot.onGround() + " pos=" + bot.blockPosition().toShortString());
    }
}
