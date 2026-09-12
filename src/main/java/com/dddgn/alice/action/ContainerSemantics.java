package com.dddgn.alice.action;

import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.DropperBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * **容器语义表 v0**（L2 生产化，2026-09-13）。用户裁定：v0 **只做纯物流容器**。
 *
 * <p>它回答两个问题（菜单路线必需）：
 * <ol>
 *   <li>这个方块**能不能走菜单路线**（有没有我们已知的容器语义）；</li>
 *   <li>它的**容器槽位有多少个**（`MenuSession` 需要"前 N 个槽属于容器"这个信息）。</li>
 * </ol>
 *
 * <p>**纪律（对齐项目"未知模组能力默认只读、不猜语义"）**：表里没有的方块一律**不支持**
 * —— 调用方必须**如实失败**（`unsupported_container`）或走显式声明的 A 路线（capability），
 * **绝不允许猜**槽位数或槽位含义。
 *
 * <p>v0 收录（都是原版、语义确定）：
 * 箱子 27 / 陷阱箱 27 / 木桶 27 / 潜影盒（任意颜色）27 / 漏斗 5 / 发射器 9 / 投掷器 9。
 * “槽位**角色**”（输入/燃料/输出）留给 v1：那需要每个机器的具体语义，原版熔炉类才有意义，
 * 且容易与模组机器混淆 —— 用户裁定先不做。
 */
public final class ContainerSemantics {

    /** 一个容器的语义（v0 只有槽位数 + 可读标签）。 */
    public record Info(int slotCount, String label) {
    }

    private ContainerSemantics() {
    }

    /**
     * 查这个方块能不能走菜单路线。
     *
     * @return 语义信息；**null = 表里没有 ⇒ 不支持**（调用方必须如实失败，不许猜）
     */
    public static Info of(BlockState state) {
        if (state.getBlock() instanceof ChestBlock) {
            return new Info(27, "chest");
        }
        if (state.getBlock() instanceof BarrelBlock) {
            return new Info(27, "barrel");
        }
        if (state.getBlock() instanceof ShulkerBoxBlock) {
            return new Info(27, "shulker_box");
        }
        if (state.getBlock() instanceof HopperBlock) {
            return new Info(5, "hopper");
        }
        if (state.getBlock() instanceof DispenserBlock) {
            return new Info(9, "dispenser");
        }
        if (state.getBlock() instanceof DropperBlock) {
            return new Info(9, "dropper");
        }
        // 熔炉类：菜单路线**可以做**，但 v0 不声明（我们没有输入/燃料/输出语义表）⇒ 如实"不支持"
        if (state.getBlock() instanceof AbstractFurnaceBlock) {
            return null;
        }
        return null;
    }

    public static boolean supportsMenuRoute(BlockState state) {
        return of(state) != null;
    }

    /** 自检/汇报用：表里收录了几类容器。 */
    public static int tableSize() {
        return 7;
    }
}
