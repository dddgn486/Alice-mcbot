package com.dddgn.alice.item;

import net.minecraft.world.item.Item;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * Alice 自注册物品(测试工具都走这里,不套原版工具——只套贴图)。
 * <ul>
 *   <li>{@code target_selector}:目标指定器,右键方块 → 派挖掘任务给 bot。
 *       贴图直接引用原版钻石斧(见 assets/alice/models/item/target_selector.json)。</li>
 * </ul>
 */
public final class AliceItems {

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, "alice");

    /** 独立 C1 只读接口扫描器；不继承原版铲子行为。 */
    public static final RegistryObject<Item> INTERFACE_SCANNER =
            ITEMS.register("interface_scanner", () -> new Item(new Item.Properties()));

    /** 目标指定器:方块目标(挖掘) / 实体目标(攻击,下一步实现)。 */
    public static final RegistryObject<Item> TARGET_SELECTOR =
            ITEMS.register("target_selector", () -> new TargetSelector(new Item.Properties()));

    /** 软地面移动实验选择器：贴图使用原版金斧。 */
    public static final RegistryObject<Item> SOFT_MOVE_SELECTOR =
            ITEMS.register("soft_move_selector", () -> new SoftMoveSelector(new Item.Properties()));

    /** 独立软路径诊断工具：点击支撑方块，只启动 SoftPathProbeTask。 */
    public static final RegistryObject<Item> SOFT_PATH_PROBE_SELECTOR =
            ITEMS.register("soft_path_probe_selector", () -> new SoftPathProbeSelector(new Item.Properties()));

    /** 道路数学模型工具：贴图使用原版钻石锄。 */
    public static final RegistryObject<Item> ROAD_PLANNER =
            ITEMS.register("road_planner", () -> new RoadPlannerItem(new Item.Properties()));

    private AliceItems() {
    }
}
