package com.dddgn.alice.action;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.log.BotLog;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.function.Predicate;

/**
 * **菜单会话**（L2，2026-09-13）：把"真实右键开菜单 → 用菜单点击操作 → 干净关闭"沉淀成**可复用、可测试**的组件。
 *
 * <p>为什么需要它（对比文档 §3 第 1/3 项）：B 路线（通用菜单驱动器）的价值在于**用模组自己的接口**做事，
 * 代价是引入了"**菜单开着**"这个新状态。探针（D-163）已经证明技术可行，并在用户实测中立刻暴露了
 * "**任务终止时箱子还开着**"的生命周期漏洞 ⇒ 生命周期必须**由组件本身保证**，不能靠每个调用点自觉。
 *
 * <p>本类的契约：
 * <ul>
 *   <li>**打开是异步的**（服务端 `openMenu` 到 `containerMenu` 生效可能跨 tick）⇒ {@link #tick()} 推进，
 *       超时给 {@link MenuCodes#MENU_OPEN_TIMEOUT}；</li>
 *   <li>**任何失败路径都会关闭菜单**（`close(...)` 幂等），并与 `MenuLifecycle` 的收尾/看门狗互补；</li>
 *   <li>操作只走**菜单协议**（`clicked` + `ClickType` / `quickMoveStack` + `broadcastChanges`），
 *       **不碰 capability** —— 这样模组的权限、统计、GUI 钩子都会如实触发（这正是 B 路线选它的理由）；</li>
 *   <li>不猜槽位语义：本类只提供"**容器槽位区间**"与"按谓词找槽"，语义由上层（或未来的语义表）决定。</li>
 * </ul>
 */
public final class MenuSession {

    /** 打开菜单最多等多少 tick（服务端同步通常 0~2 tick；给足余量）。 */
    public static final int OPEN_TIMEOUT_TICKS = 20;

    public enum State {
        /** 还没开始。 */
        IDLE,
        /** 已发出右键，等 `containerMenu` 生效。 */
        OPENING,
        /** 菜单可用。 */
        OPEN,
        /** 失败（原因在 {@link #failure()}）。 */
        FAILED,
        /** 已关闭。 */
        CLOSED
    }

    private final BotPlayer bot;
    private final BlockPos target;
    private final int containerSlotCount;
    private State state = State.IDLE;
    private int ticks;
    private String failure = "";
    private String menuType = "-";
    private int menuSlots = -1;
    private boolean closed;

    private MenuSession(BotPlayer bot, BlockPos target, int containerSlotCount) {
        this.bot = bot;
        this.target = target;
        this.containerSlotCount = containerSlotCount;
    }

    private static int blockedAirborne;
    private static int openWhileMoving;

    /**
     * 开始一次会话（立刻发出真实右键；随后每 tick 调 {@link #tick()}）。
     *
     * <p>**K-3 门**（对齐 Baritone `InventoryPauserProcess:53`：`safeToCancel && 站定 ≥2 tick` 才允许停下来开背包）：
     * ① **空中一律硬拒**（打开容器菜单会让物品移动，半空中做既不合逻辑也不安全）；
     * ② **控制器仍有输入**先告警 + 计数（段结束时可能有 1~2 tick 残留，数据不足不硬判 —— 不猜）。
     */
    public static MenuSession open(BotPlayer bot, BlockPos target, int containerSlotCount) {
        MenuSession session = new MenuSession(bot, target, containerSlotCount);
        if (!bot.onGround()) {
            blockedAirborne++;
            BotLog.warn("[Menu] 拒绝在空中开菜单（累计 {}）target={} bot={}",
                    blockedAirborne, target.toShortString(), bot.blockPosition().toShortString());
            session.fail(MenuCodes.MENU_NOT_SETTLED);
            return session;
        }
        if (bot.controller() != null && bot.controller().hasActiveMovement()) {
            openWhileMoving++;
            BotLog.warn("[Menu] 开菜单时控制器仍有输入（累计 {}）target={} —— 频繁出现则应升为硬门",
                    openWhileMoving, target.toShortString());
        }
        session.state = State.OPENING;
        session.swingAndUse();
        return session;
    }

    /** 自检/汇报用：K-3 门的计数。 */
    public static String describeGates() {
        return "blockedAirborne=" + blockedAirborne + " openWhileMoving=" + openWhileMoving;
    }

    /** 推进打开过程。返回当前状态。 */
    public State tick() {
        if (state != State.OPENING) {
            return state;
        }
        if (++ticks > OPEN_TIMEOUT_TICKS) {
            fail(MenuCodes.MENU_OPEN_TIMEOUT);
            return state;
        }
        AbstractContainerMenu menu = bot.containerMenu;
        if (menu == null || menu == bot.inventoryMenu) {
            return state;   // 还没生效
        }
        this.menuType = menu.getClass().getSimpleName();
        this.menuSlots = menu.slots.size();
        if (containerSlotCount > 0 && menuSlots < containerSlotCount) {
            // 开出来的容器比预期小 ⇒ 不是我们想点的那个方块（例如点到了别的容器）
            fail(MenuCodes.MENU_TARGET_MISMATCH);
            return state;
        }
        state = State.OPEN;
        BotLog.info("[Menu] opened target={} type={} slots={} ticks={}",
                target.toShortString(), menuType, menuSlots, ticks);
        // P3（用户裁定）：**打开菜单 = 读取事件** ⇒ 入事件环（S4 可见），**不占写入预算**（打开不改世界）。
        // 只入环、**不通知决策层** —— 菜单打开是高频常规动作，通知会平白招来 LLM 调用。
        com.dddgn.alice.decision.DecisionEvents.record(bot, "MENU_OPEN", "info",
                "打开容器菜单 " + menuType, "target=" + target.toShortString() + " slots=" + menuSlots);
        return state;
    }

    /** 真实右键（对齐真人客户端：转向 + 挥手 + `gameMode.useItemOn`）。 */
    private void swingAndUse() {
        BlockInteraction.faceTowards(bot, Vec3.atCenterOf(target));
        bot.swing(InteractionHand.MAIN_HAND);
        Direction face = faceTowardsBot();
        Vec3 hitPoint = Vec3.atCenterOf(target)
                .add(face.getStepX() * 0.5D, face.getStepY() * 0.5D, face.getStepZ() * 0.5D);
        BlockHitResult hit = new BlockHitResult(hitPoint, face, target, false);
        InteractionResult result;
        try {
            result = bot.gameMode.useItemOn(bot, bot.serverLevel(), bot.getMainHandItem(),
                    InteractionHand.MAIN_HAND, hit);
        } catch (RuntimeException | Error thrown) {
            fail(MenuCodes.MENU_OPEN_FAILED);
            BotLog.warn("[Menu] useItemOn 抛异常 target={} {}", target.toShortString(), thrown.toString());
            return;
        }
        BotLog.info("[Menu] use_item_on target={} result={}", target.toShortString(), result);
        if (result == InteractionResult.FAIL) {
            fail(MenuCodes.MENU_OPEN_FAILED);
        }
    }

    /** bot 站在方块的哪一侧 ⇒ 打到的是那一面（`ServerPlayerGameMode` 会校验距离）。 */
    private Direction faceTowardsBot() {
        Vec3 botPos = bot.position();
        Vec3 center = Vec3.atCenterOf(target);
        double dx = botPos.x - center.x;
        double dz = botPos.z - center.z;
        if (Math.abs(dx) >= Math.abs(dz)) {
            return dx >= 0 ? Direction.EAST : Direction.WEST;
        }
        return dz >= 0 ? Direction.SOUTH : Direction.NORTH;
    }

    // ==================== 槽位访问（不猜语义，只给区间与查找） ====================

    public boolean isOpen() {
        return state == State.OPEN && bot.containerMenu != bot.inventoryMenu;
    }

    /** 容器自己的槽位区间（前 N 个；N = 打开时声明的容器槽位数）。 */
    public int containerSlotCount() {
        return containerSlotCount;
    }

    /** 在容器槽位里找第一个满足条件的槽（-1 = 没有）。 */
    public int findInContainer(Predicate<ItemStack> match) {
        AbstractContainerMenu menu = bot.containerMenu;
        if (!isOpen()) {
            return -1;
        }
        for (int slot = 0; slot < Math.min(containerSlotCount, menu.slots.size()); slot++) {
            if (match.test(menu.slots.get(slot).getItem())) {
                return slot;
            }
        }
        return -1;
    }

    /** 在玩家背包槽位里找第一个空槽（-1 = 满）。 */
    public int findEmptyPlayerSlot() {
        AbstractContainerMenu menu = bot.containerMenu;
        if (!isOpen()) {
            return -1;
        }
        for (int slot = containerSlotCount; slot < menu.slots.size(); slot++) {
            if (menu.slots.get(slot).getItem().isEmpty()) {
                return slot;
            }
        }
        return -1;
    }

    /**
     * **背包索引 → 菜单槽位号**（原版物流容器布局）。
     *
     * <p>为什么必须有这个转换（2026-09-13 实测踩坑）：`Inventory` 的索引（0..8 快捷栏 / 9..35 主背包）
     * 与**菜单槽位号**是**两个索引空间**。目标腿我把背包索引直接当菜单槽位号去 `click`，
     * 结果点在箱子里的空格上 ⇒ 物品没动、校验读到 0、`unknown_discrepancy`
     * （而源腿用的是 `findEmptyPlayerSlot()`，本来就返回菜单槽位号，所以它一直是对的）。
     *
     * <p>布局（原版 `ChestMenu`/`HopperMenu`/`DispenserMenu` 一致）：
     * `0..containerSlotCount-1` = 容器；其后 27 格 = 主背包（索引 9..35）；最后 9 格 = 快捷栏（索引 0..8）。
     * **模组菜单可能不同** ⇒ 这正是"槽位语义表"要编码的东西（v0 只覆盖原版物流容器）。
     *
     * @return 菜单槽位号；越界/布局不符 ⇒ -1（调用方必须如实失败，不许猜）
     */
    public int playerMenuSlotFor(int inventoryIndex) {
        AbstractContainerMenu menu = bot.containerMenu;
        if (menu == null || inventoryIndex < 0 || inventoryIndex > 35) {
            return -1;
        }
        int slot = inventoryIndex < 9
                ? containerSlotCount + 27 + inventoryIndex          // 快捷栏：容器 + 主背包 27 格之后
                : containerSlotCount + (inventoryIndex - 9);        // 主背包
        return slot < menu.slots.size() ? slot : -1;
    }

    /** 玩家快捷栏区间（ChestMenu 语义：最后 9 格）—— 落在手上才好观察。 */
    public int firstHotbarSlot() {
        AbstractContainerMenu menu = bot.containerMenu;
        return menu == null ? -1 : Math.max(containerSlotCount, menu.slots.size() - 9);
    }

    public ItemStack carried() {
        AbstractContainerMenu menu = bot.containerMenu;
        return menu == null ? ItemStack.EMPTY : menu.getCarried();
    }

    // ==================== 操作（只走菜单协议） ====================

    /** 左键点击（拿起/放下整堆）——真人用鼠标左键做的同一件事。 */
    public boolean click(int slot, ClickType type) {
        AbstractContainerMenu menu = bot.containerMenu;
        if (!isOpen()) {
            fail(MenuCodes.MENU_CLOSED_EARLY);
            return false;
        }
        if (slot < 0 || slot >= menu.slots.size()) {
            fail(MenuCodes.MENU_SLOT_MISMATCH);
            return false;
        }
        try {
            menu.clicked(slot, 0, type, bot);
            menu.broadcastChanges();
            return true;
        } catch (RuntimeException | Error thrown) {
            fail(MenuCodes.MENU_SLOT_MISMATCH);
            BotLog.warn("[Menu] clicked(slot={}, type={}) 抛异常 {}", slot, type, thrown.toString());
            return false;
        }
    }

    /** 菜单是否仍然有效（玩家走远/容器被拆 ⇒ 服务端会判定失效）。 */
    public boolean stillValid() {
        AbstractContainerMenu menu = bot.containerMenu;
        return isOpen() && menu != null && menu.stillValid(bot);
    }

    // ==================== 关闭（幂等；失败路径也走它） ====================

    /** 关闭会话。**幂等**：重复调用安全；任何失败/中断路径都应调用它。 */
    public void close(String reason) {
        if (closed) {
            return;
        }
        closed = true;
        if (bot.containerMenu != null && bot.containerMenu != bot.inventoryMenu) {
            try {
                bot.closeContainer();
                BotLog.info("[Menu] closed reason={} type={}", reason, menuType);
            } catch (RuntimeException | Error thrown) {
                BotLog.warn("[Menu] 关闭失败 reason={} {}", reason, thrown.toString());
            }
        }
        if (state != State.FAILED) {
            state = State.CLOSED;
        }
    }

    private void fail(String code) {
        if (failure.isEmpty()) {
            failure = code;
            state = State.FAILED;
            BotLog.warn("[Menu] failed code={} target={} ticks={}", code, target.toShortString(), ticks);
        }
        close("failure:" + code);   // **失败路径也必须收尾**（生命周期由组件自己保证）
    }

    public String failure() {
        return failure;
    }

    public String menuType() {
        return menuType;
    }

    public int menuSlots() {
        return menuSlots;
    }

    @Override
    public String toString() {
        return "MenuSession[state=" + state + " target=" + target.toShortString() + " type=" + menuType
                + " slots=" + menuSlots + (failure.isEmpty() ? "" : " failure=" + failure) + "]";
    }
}
