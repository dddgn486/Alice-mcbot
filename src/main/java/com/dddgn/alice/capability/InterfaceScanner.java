package com.dddgn.alice.capability;

import com.dddgn.alice.log.BotLog;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.energy.IEnergyStorage;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.List;

/**
 * 服务端 C1 只读接口扫描器(设计文档 §5「只读接口自动生成」v1)。
 * <p>
 * 捕获指定方块位置的 <b>unsided raw readonly facts</b>(不推断输入/输出/能量槽位角色):
 * <ul>
 *   <li>物品槽:按槽位索引输出内容(无输入/输出/能量语义猜测);</li>
 *   <li>能量:FE 储量/容量/可提取/可接收;</li>
 *   <li>流体:各 tank 索引与内容;</li>
 *   <li>Mek GUI 页签投影:仅作为显式 <b>非通用 legacy</b> 投影保留,不属于 C1 通用事实。</li>
 * </ul>
 * C1 语义边界:只读取、不写入;不进行传输/插入/抽取模拟;不推断 per-machine 槽位角色
 * (精确语义需 per-machine 适配器,见 docs/MEK_GUI_SEMANTICS.md)。
 * 未加载位置以保守常量 {@code unknown} 作为 blockId,不访问目标世界状态。</p>
 */
public final class InterfaceScanner {

    private InterfaceScanner() {
    }

    public static String scan(ServerLevel level, BlockPos pos) {
        return format(capture(level, pos));
    }

    /** 未加载/未知目标方块的保守 block id:不访问目标世界状态推导。 */
    private static final String UNKNOWN_BLOCK_ID = "unknown";

    /** Capture all C1 facts synchronously and copy every mutable capability value. */
    public static InterfaceSnapshot capture(ServerLevel level, BlockPos pos) {
        String dimension = level.dimension().location().toString();
        long tick = level.getGameTime();
        // 必须先于任何目标 getBlockState/getBlockEntity:未加载位置不得访问目标世界状态
        // (可能同步解析/加载 chunk 或返回 fallback state)。
        if (!level.hasChunkAt(pos)) {
            return new InterfaceSnapshot(1, dimension, pos, UNKNOWN_BLOCK_ID, null, tick,
                    ObservationStatus.CHUNK_NOT_LOADED, List.of(), null, List.of(), "");
        }
        String blockName = ForgeRegistries.BLOCKS.getKey(level.getBlockState(pos).getBlock()).toString();
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null) {
            return new InterfaceSnapshot(1, dimension, pos, blockName, null, tick,
                    ObservationStatus.NO_BLOCK_ENTITY, List.of(), null, List.of(), "");
        }
        try {
            List<InterfaceSnapshot.ItemFact> items = captureItems(be);
            InterfaceSnapshot.EnergyFact energy = captureEnergy(be);
            List<InterfaceSnapshot.FluidFact> fluids = captureFluids(be);
            StringBuilder legacy = new StringBuilder();
            scanMek(legacy, be);
            String type = ForgeRegistries.BLOCK_ENTITY_TYPES.getKey(be.getType()).toString();
            return new InterfaceSnapshot(1, dimension, pos, blockName, type, tick,
                    ObservationStatus.OK, items, energy, fluids, legacy.toString());
        } catch (RuntimeException exception) {
            BotLog.warn("接口快照捕获失败: pos={} reason={}", pos.toShortString(), exception.toString());
            return new InterfaceSnapshot(1, dimension, pos, blockName, null, tick,
                    ObservationStatus.CAPTURE_ERROR, List.of(), null, List.of(), "");
        }
    }

    /** Human-readable projection of an already captured immutable snapshot. */
    public static String format(InterfaceSnapshot snapshot) {
        StringBuilder sb = new StringBuilder();
        sb.append("schema=v").append(snapshot.schemaVersion())
                .append(" dimension=").append(snapshot.dimensionId())
                .append(" block=").append(snapshot.blockId())
                .append(" @ ").append(snapshot.position().toShortString())
                .append(" tick=").append(snapshot.observedServerTick())
                .append(" status=").append(snapshot.status()).append('\n');
        if (snapshot.blockEntityTypeId() != null) {
            sb.append("block_entity=").append(snapshot.blockEntityTypeId()).append('\n');
        }
        if (snapshot.status() == ObservationStatus.NO_BLOCK_ENTITY) {
            sb.append("(无方块实体 → 无接口)\n");
        }
        if (!snapshot.items().isEmpty()) {
            sb.append("【物品槽事实】共 ").append(snapshot.items().size()).append(" 槽\n");
            for (InterfaceSnapshot.ItemFact item : snapshot.items()) {
                sb.append("  槽").append(item.index()).append(": ")
                        .append(item.itemId()).append('×').append(item.count())
                        .append(" damage=").append(item.damage()).append('\n');
            }
        }
        if (snapshot.energy() != null) {
            sb.append("【能量事实】").append(snapshot.energy().stored()).append(" / ")
                    .append(snapshot.energy().capacity()).append(" FE extract=")
                    .append(snapshot.energy().canExtract()).append(" receive=")
                    .append(snapshot.energy().canReceive()).append('\n');
        }
        if (!snapshot.fluids().isEmpty()) {
            sb.append("【流体事实】共 ").append(snapshot.fluids().size()).append(" tank\n");
            for (InterfaceSnapshot.FluidFact fluid : snapshot.fluids()) {
                sb.append("  tank").append(fluid.index()).append(": ")
                        .append(fluid.fluidId()).append('×').append(fluid.amount())
                        .append("mb capacity=").append(fluid.capacity()).append('\n');
            }
        }
        if (!snapshot.legacyProjection().isBlank()) {
            sb.append("【legacy Mek projection; 非 C1 通用事实】\n")
                    .append(snapshot.legacyProjection());
        }
        if (snapshot.status() == ObservationStatus.OK && snapshot.items().isEmpty()
                && snapshot.energy() == null && snapshot.fluids().isEmpty()
                && snapshot.legacyProjection().isBlank()) {
            sb.append("(未发现 unsided C1 capability)\n");
        }
        return sb.toString();
    }

    private static List<InterfaceSnapshot.ItemFact> captureItems(BlockEntity be) {
        return be.getCapability(ForgeCapabilities.ITEM_HANDLER, null)
                .map(handler -> {
                    List<InterfaceSnapshot.ItemFact> facts = new java.util.ArrayList<>();
                    for (int i = 0; i < handler.getSlots(); i++) {
                        ItemStack stack = handler.getStackInSlot(i).copy();
                        String id = ForgeRegistries.ITEMS.getKey(stack.getItem()).toString();
                        CompoundTag tag = stack.getTag();
                        facts.add(new InterfaceSnapshot.ItemFact(i, id, stack.getCount(), stack.getDamageValue(),
                                tag == null ? "" : tag.copy().toString()));
                    }
                    return List.copyOf(facts);
                }).orElse(List.of());
    }

    private static InterfaceSnapshot.EnergyFact captureEnergy(BlockEntity be) {
        return be.getCapability(ForgeCapabilities.ENERGY, null)
                .map(energy -> new InterfaceSnapshot.EnergyFact(energy.getEnergyStored(),
                        energy.getMaxEnergyStored(), energy.canExtract(), energy.canReceive()))
                .orElse(null);
    }

    private static List<InterfaceSnapshot.FluidFact> captureFluids(BlockEntity be) {
        return be.getCapability(ForgeCapabilities.FLUID_HANDLER, null)
                .map(handler -> {
                    List<InterfaceSnapshot.FluidFact> facts = new java.util.ArrayList<>();
                    for (int i = 0; i < handler.getTanks(); i++) {
                        FluidStack stack = handler.getFluidInTank(i).copy();
                        String id = ForgeRegistries.FLUIDS.getKey(stack.getFluid()).toString();
                        facts.add(new InterfaceSnapshot.FluidFact(i, id, stack.getAmount(),
                                handler.getTankCapacity(i), stack.getTag() == null ? "" : stack.getTag().copy().toString()));
                    }
                    return List.copyOf(facts);
                }).orElse(List.of());
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
}
