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
            // Mek 探查已**整体移除**（2026-09-13 用户裁定）：不为可选模组保留编译依赖。
            // 若将来要在装了 Mek 的客户端做只读探查，用**反射/独立可选模块**实现（见 docs/MEK_GUI_SEMANTICS.md）。
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

}
