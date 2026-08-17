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
 *   <li>Mek GUI 页签:化学能力/红石控制/升级/安全/传输配置(颜色槽↔面→IO)。</li>
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

    /** Mek GUI 页签 → 语义接口(见 docs/MEK_GUI_SEMANTICS.md v0.2)。
     * <p>结论:Mek 没有「单一统一接口」,每个 GUI 页签对应一个独立扩展点——
     * capability(物品/能量/流体/化学) + instanceof 接口(面配置/传输/升级/安全/红石)。
     * 但所有机器都继承 TileEntityMekanism,这些接口会「同时实现」,
     * 所以一次 instanceof 基类即可全量读取(这就是适配器收敛的意义)。 */
    private static void scanMek(StringBuilder sb, BlockEntity be) {
        appendIfPresent(sb, be, mekanism.common.capabilities.Capabilities.GAS_HANDLER, "【气体】有气体接口(内容读取待适配器)");
        appendIfPresent(sb, be, mekanism.common.capabilities.Capabilities.INFUSION_HANDLER, "【灌注】有灌注接口(待适配器)");
        appendIfPresent(sb, be, mekanism.common.capabilities.Capabilities.PIGMENT_HANDLER, "【颜料】有颜料接口(待适配器)");
        appendIfPresent(sb, be, mekanism.common.capabilities.Capabilities.SLURRY_HANDLER, "【浆液】有浆液接口(待适配器)");
        appendIfPresent(sb, be, mekanism.common.capabilities.Capabilities.HEAT_HANDLER, "【热量】有热接口(待适配器)");
        appendIfPresent(sb, be, mekanism.common.capabilities.Capabilities.STRICT_ENERGY, "【精确能量】有 Mek 精确能量接口(待适配器)");
        scanMekRedstone(sb, be);
        scanMekUpgrades(sb, be);
        scanMekSecurity(sb, be);
        scanMekSideConfig(sb, be);
    }

    /** 红石控制页签:ITileRedstone(extends IRedstoneControl)。 */
    private static void scanMekRedstone(StringBuilder sb, BlockEntity be) {
        if (be instanceof mekanism.common.tile.interfaces.ITileRedstone redstone) {
            sb.append("【红石控制】模式=").append(redstone.getControlType())
                    .append(" 当前供电=").append(redstone.isPowered() ? "是" : "否").append('\n');
        }
    }

    /** 升级页签:ITileUpgradable(extends IUpgradeTile)。支持/已装/上限全部可读。 */
    private static void scanMekUpgrades(StringBuilder sb, BlockEntity be) {
        if (!(be instanceof mekanism.common.tile.interfaces.ITileUpgradable upgradable)) {
            return;
        }
        var component = upgradable.getComponent();
        if (component == null) {
            return;
        }
        StringBuilder line = new StringBuilder("【升级】");
        var installed = component.getInstalledTypes();
        if (installed.isEmpty()) {
            line.append("未安装任何升级");
        } else {
            line.append("已安装: ");
            boolean first = true;
            for (mekanism.api.Upgrade u : installed) {
                if (!first) {
                    line.append(", ");
                }
                line.append(u.name()).append('×').append(component.getUpgrades(u));
                first = false;
            }
        }
        line.append(" | 支持: ");
        boolean any = false;
        for (mekanism.api.Upgrade u : component.getSupportedTypes()) {
            if (any) {
                line.append(", ");
            }
            line.append(u.name()).append("(上限").append(u.getMax()).append(')');
            any = true;
        }
        if (!any) {
            line.append("无");
        }
        sb.append(line).append('\n');
    }

    /** 安全页签:ISecurityTile(extends ISecurityObject)。 */
    private static void scanMekSecurity(StringBuilder sb, BlockEntity be) {
        if (be instanceof mekanism.common.lib.security.ISecurityTile security
                && security.hasSecurity()) {
            sb.append("【安全】模式=").append(security.getSecurityMode())
                    .append(" 所有者=").append(security.getOwnerName()).append('\n');
        }
    }

    /** 传输配置页签(颜色槽↔面→IO 类型):ISideConfiguration.getConfig()。
     * 每个 TransmissionType 一组:相对面 → DataType(输入/输出/能量/…),即 GUI 里颜色槽对应关系。 */
    private static void scanMekSideConfig(StringBuilder sb, BlockEntity be) {
        if (!(be instanceof mekanism.common.tile.interfaces.ISideConfiguration side)) {
            return;
        }
        var config = side.getConfig();
        sb.append("【传输配置】共 ").append(config.getTransmissions().size()).append(" 类传输\n");
        for (mekanism.common.lib.transmitter.TransmissionType tt : config.getTransmissions()) {
            var info = config.getConfig(tt);
            if (info == null) {
                continue;
            }
            StringBuilder line = new StringBuilder("  ").append(tt.name()).append(": ");
            boolean any = false;
            for (mekanism.api.RelativeSide rs : mekanism.api.RelativeSide.values()) {
                if (!info.isSideEnabled(rs)) {
                    continue;
                }
                var dt = info.getDataType(rs);
                if (dt == null || dt == mekanism.common.tile.component.config.DataType.NONE) {
                    continue;
                }
                if (any) {
                    line.append(" ");
                }
                // DataType 的颜色 = GUI 里那个颜色槽;面 = 该颜色对应的机器面
                line.append(rs.name()).append('=').append(dt.name())
                        .append('(').append(dt.getColor().getEnglishName()).append(')');
                any = true;
            }
            if (!any) {
                line.append("(无已启用面)");
            }
            line.append(" 自动弹出=").append(info.canEject() && info.isEjecting() ? "是" : "否");
            sb.append(line).append('\n');
        }
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
