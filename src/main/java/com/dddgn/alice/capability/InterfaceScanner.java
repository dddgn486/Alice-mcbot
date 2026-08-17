package com.dddgn.alice.capability;

import com.dddgn.alice.log.BotLog;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.energy.IEnergyStorage;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * 服务端接口扫描器(设计文档 §5「只读接口自动生成」v1)。
 * <p>
 * 扫描指定方块的 capability,输出结构化接口清单:
 * <ul>
 *   <li>物品槽:每个槽位的内容 + 通用语义(输入/输出/能量,按 Mek 机器惯例);</li>
 *   <li>能量:FE 储量/容量;</li>
 *   <li>流体:各 tank 内容;</li>
 *   <li>Mek 特殊能力:气体/灌注/颜料/浆液/热/精确能量/面配置——识别存在性,内容读取待适配器。</li>
 * </ul>
 * ⚠️ 审查点 R12:槽位语义目前是「Mek 机器通用惯例」(槽0输入/槽1输出/末位能量),
 * 不同机器有差异,精确语义需 per-machine 适配器(见 docs/MEK_GUI_SEMANTICS.md)。</p>
 */
public final class InterfaceScanner {

    private InterfaceScanner() {
    }

    public static String scan(ServerLevel level, BlockPos pos) {
        String blockName = ForgeRegistries.BLOCKS.getKey(level.getBlockState(pos).getBlock()).toString();
        StringBuilder sb = new StringBuilder();
        sb.append("方块: ").append(blockName).append(" @ ").append(pos.toShortString()).append('\n');

        BlockEntity be = level.getBlockEntity(pos);
        if (be == null) {
            sb.append("(无方块实体 → 无接口)");
            return sb.toString();
        }

        scanItems(sb, be);
        scanEnergy(sb, be);
        scanFluid(sb, be);
        scanMek(sb, be);
        return sb.toString();
    }

    private static void scanItems(StringBuilder sb, BlockEntity be) {
        be.getCapability(ForgeCapabilities.ITEM_HANDLER, null).ifPresent(handler -> {
            int slots = handler.getSlots();
            sb.append("【物品槽】共 ").append(slots).append(" 槽\n");
            for (int i = 0; i < slots; i++) {
                ItemStack stack = handler.getStackInSlot(i);
                sb.append("  槽").append(i).append('[').append(slotHint(i, slots)).append("]: ");
                if (stack.isEmpty()) {
                    sb.append("空\n");
                } else {
                    sb.append(ForgeRegistries.ITEMS.getKey(stack.getItem()))
                            .append('×').append(stack.getCount()).append('\n');
                }
            }
        });
    }

    private static void scanEnergy(StringBuilder sb, BlockEntity be) {
        be.getCapability(ForgeCapabilities.ENERGY, null).ifPresent(energy -> {
            sb.append("【能量】").append(energy.getEnergyStored())
                    .append(" / ").append(energy.getMaxEnergyStored()).append(" FE\n");
        });
    }

    private static void scanFluid(StringBuilder sb, BlockEntity be) {
        be.getCapability(ForgeCapabilities.FLUID_HANDLER, null).ifPresent(handler -> {
            int tanks = handler.getTanks();
            sb.append("【流体】共 ").append(tanks).append(" tank\n");
            for (int i = 0; i < tanks; i++) {
                FluidStack fluid = handler.getFluidInTank(i);
                sb.append("  tank").append(i).append(": ");
                if (fluid.isEmpty()) {
                    sb.append("空\n");
                } else {
                    sb.append(ForgeRegistries.FLUIDS.getKey(fluid.getFluid()))
                            .append('×').append(fluid.getAmount()).append("mb\n");
                }
            }
        });
    }

    /** Mek 特殊能力:识别存在性(内容读取方法待适配器逐一确认)。 */
    private static void scanMek(StringBuilder sb, BlockEntity be) {
        appendIfPresent(sb, be, mekanism.common.capabilities.Capabilities.GAS_HANDLER, "【气体】有气体接口(内容读取待适配器)");
        appendIfPresent(sb, be, mekanism.common.capabilities.Capabilities.INFUSION_HANDLER, "【灌注】有灌注接口(待适配器)");
        appendIfPresent(sb, be, mekanism.common.capabilities.Capabilities.PIGMENT_HANDLER, "【颜料】有颜料接口(待适配器)");
        appendIfPresent(sb, be, mekanism.common.capabilities.Capabilities.SLURRY_HANDLER, "【浆液】有浆液接口(待适配器)");
        appendIfPresent(sb, be, mekanism.common.capabilities.Capabilities.HEAT_HANDLER, "【热量】有热接口(待适配器)");
        appendIfPresent(sb, be, mekanism.common.capabilities.Capabilities.STRICT_ENERGY, "【精确能量】有 Mek 精确能量接口(待适配器)");
        appendIfPresent(sb, be, mekanism.common.capabilities.Capabilities.CONFIGURABLE, "【面配置】可配置输入/输出面");
    }

    private static <T> void appendIfPresent(StringBuilder sb, BlockEntity be,
                                            net.minecraftforge.common.capabilities.Capability<T> cap, String text) {
        if (be.getCapability(cap, null).isPresent()) {
            sb.append(text).append('\n');
        }
    }

    /** 槽位语义(Mek 机器通用惯例,精确语义见 docs/MEK_GUI_SEMANTICS.md)。 */
    private static String slotHint(int slot, int total) {
        if (total >= 3) {
            if (slot == 0) {
                return "输入";
            }
            if (slot == 1) {
                return "输出";
            }
            if (slot == total - 1) {
                return "能量";
            }
        } else if (total == 2) {
            if (slot == 0) {
                return "输入";
            }
            if (slot == 1) {
                return "输出";
            }
        }
        return "?";
    }
}
