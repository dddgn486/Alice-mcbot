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

    /** R3 规划内核测试工具：右键方块即规划，无需输入坐标。 */
    public static final RegistryObject<Item> PATHING_PLANNER =
            ITEMS.register("pathing_planner", () -> new PathingPlannerItem(new Item.Properties()));

    /** R3 一键自检电池：右键即跑完规划 + 全部 Movement + 链。 */
    public static final RegistryObject<Item> PATHING_BATTERY =
            ITEMS.register("pathing_battery", () -> new PathingBatteryItem(new Item.Properties()));

    /** R4 路径会话测试器：规划 + 逐段执行（场景专属，固定起终点）。 */
    public static final RegistryObject<Item> PATHING_SESSION =
            ITEMS.register("pathing_session", () -> new PathingSessionItem(new Item.Properties()));

    /** R5-2 破坏通行测试器：规划穿墙路径 + 破坏 + 通过（场景专属）。 */
    public static final RegistryObject<Item> PATHING_BREAKER =
            ITEMS.register("pathing_breaker", () -> new PathingBreakerItem(new Item.Properties()));

    /** R5-3 放置台阶通行测试器：跨缺口/下台阶时放置方块（场景专属）。 */
    public static final RegistryObject<Item> PATHING_PLACER =
            ITEMS.register("pathing_placer", () -> new PathingPlacerItem(new Item.Properties()));

    /** R4 自愈验证夹具：执行中途平移 bot 1 格，验证 snipsnap/重规划（场景专属）。 */
    public static final RegistryObject<Item> PATHING_DISTURBER =
            ITEMS.register("pathing_disturber", () -> new PathingDisturberItem(new Item.Properties()));

    /** 内核对齐验证：流体屏障检查器（场景专属）。 */
    public static final RegistryObject<Item> PATHING_FLUID_GUARD =
            ITEMS.register("pathing_fluid_guard", () -> new PathingFluidGuardItem(new Item.Properties()));

    /** Q7 验收：路线偏好检查器（1 格深坑 vs 同层绕路，场景专属）。 */
    public static final RegistryObject<Item> PATHING_DIP_ROUTE =
            ITEMS.register("pathing_dip_route", () -> new PathingDipRouteItem(new Item.Properties()));

    /** 内核对齐验证：站立判定检查器（栅栏不可站，场景专属）。 */
    public static final RegistryObject<Item> PATHING_FENCE_GUARD =
            ITEMS.register("pathing_fence_guard", () -> new PathingFenceGuardItem(new Item.Properties()));


    private AliceItems() {
    }
}
