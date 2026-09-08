package com.dddgn.alice.item;

import net.minecraft.world.item.Item;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * Alice 自注册物品。
 * <ul>
 *   <li>{@code target_selector}: 目标指定器，右键方块 → 派挖掘任务给 bot。
 *       贴图直接引用原版钻石斧(见 assets/alice/models/item/target_selector.json)。</li>
 * </ul>
 */
public final class AliceItems {

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, "alice");

    /** 目标指定器: 方块目标(挖掘) / 实体目标(攻击)。 */
    public static final RegistryObject<Item> TARGET_SELECTOR =
            ITEMS.register("target_selector", () -> new TargetSelector(new Item.Properties()));

    /** 道路规划工具：贴图使用原版钻石锄。 */
    public static final RegistryObject<Item> ROAD_PLANNER =
            ITEMS.register("road_planner", () -> new RoadPlannerItem(new Item.Properties()));

    /** A1.1 endpoint selector. */
    public static final RegistryObject<Item> TRANSFER_ENDPOINT_SELECTOR =
            ITEMS.register("transfer_endpoint_selector", () -> new TransferEndpointSelector(new Item.Properties()));

    /** Bot 遥控器：右键进入控制模式，WASD 操控 bot 移动。贴图使用原版钟。 */
    public static final RegistryObject<Item> BOT_REMOTE_CONTROL =
            ITEMS.register("bot_remote_control", () -> new BotRemoteControl(new Item.Properties()));

    /** 伐木规划器：选择区域并分配伐木任务。贴图使用原版铁斧。 */
    public static final RegistryObject<Item> LUMBER_PLANNER =
            ITEMS.register("lumber_planner", () -> new LumberPlanner(new Item.Properties()));

    /** 自动伐木器：持续自动砍树。贴图使用原版钻石斧。 */
    public static final RegistryObject<Item> AUTO_LUMBERER =
            ITEMS.register("auto_lumberer", () -> new AutoLumberer(new Item.Properties()));

    /** 开发期 MineTask 场景 A 一键启动器。 */
    public static final RegistryObject<Item> MINING_SCENE_TESTER =
            ITEMS.register("mining_scene_tester", () -> new MiningSceneTester(new Item.Properties()));

    /** 开发期 MineTask 场景 B 一键启动器。 */
    public static final RegistryObject<Item> MINING_SCENE_B_TESTER =
            ITEMS.register("mining_scene_b_tester", () -> new MiningSceneBTester(new Item.Properties()));

    /** 开发期场景 C 动态障碍启动器。 */
    public static final RegistryObject<Item> MINING_SCENE_C_TESTER =
            ITEMS.register("mining_scene_c_tester", () -> new MiningSceneCTester(new Item.Properties()));

    /** 开发期动态障碍/重规划测试器。 */
    public static final RegistryObject<Item> MINING_REPLAN_TESTER =
            ITEMS.register("mining_replan_tester", () -> new MiningReplanTester(new Item.Properties()));


    private AliceItems() {
    }
}
