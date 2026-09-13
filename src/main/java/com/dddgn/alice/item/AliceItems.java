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

    /** 串联回归启动器：一次右键跑完所有寻路场景。 */
    public static final RegistryObject<Item> PATHING_REGRESSION =
            ITEMS.register("pathing_regression", () -> new PathingRegressionItem(new Item.Properties()));

    /** D-043 重规划验证：前方封路器（场景专属）。 */
    public static final RegistryObject<Item> PATHING_WALLER =
            ITEMS.register("pathing_waller", () -> new PathingWallerItem(new Item.Properties()));

    /** D-049 连续行动对照：直线跑器（场景专属）。 */
    public static final RegistryObject<Item> PATHING_STRAIGHT =
            ITEMS.register("pathing_straight", () -> new PathingStraightItem(new Item.Properties()));

    /** D-048 垂直下落验证器（场景专属）。 */
    public static final RegistryObject<Item> PATHING_DOWNWARD =
            ITEMS.register("pathing_downward", () -> new PathingDownwardItem(new Item.Properties()));

    /** D-062 Follow 迁移入口（跟随点击的玩家；再点一次停止）。 */
    public static final RegistryObject<Item> FOLLOW_RUNNER =
            ITEMS.register("follow_runner", () -> new FollowRunnerItem(new Item.Properties()));

    /** D-060 WalkTo 迁移自检（新内核任务层，场景专属）。 */
    public static final RegistryObject<Item> WALK_TO_RUNNER =
            ITEMS.register("walk_to_runner", () -> new WalkToRunnerItem(new Item.Properties()));

    /** D-070 挖掘站位选优自检器（两模式，场景专属）。 */
    public static final RegistryObject<Item> MINE_COURSE_RUNNER =
            ITEMS.register("mine_course_runner", () -> new MineCourseRunnerItem(new Item.Properties()));

    /** 伐木 Job（L3/D-080，切片 J1，场景专属）。 */
    public static final RegistryObject<Item> LUMBER_JOB =
            ITEMS.register("lumber_job", () -> new LumberJobItem(new Item.Properties()));

    /** J3 决策缝自检：同场景下两策略对比（只规划、不执行）。 */
    public static final RegistryObject<Item> LUMBER_POLICY_CHECK =
            ITEMS.register("lumber_policy_check", () -> new LumberPolicyCheckItem(new Item.Properties()));

    /** J6-b2 容器绕行自检（零参数）：断言 bot 不为取目标而拆箱子（D-095）。 */
    public static final RegistryObject<Item> CLEAR_GUARD_CHECK =
            ITEMS.register("clear_guard_check", () -> new ClearGuardCheckItem(new Item.Properties()));

    /** J7 Step 1 脚手架生命周期自检（零参数）：建 N 拆 N、账本清空、无残留。 */
    public static final RegistryObject<Item> SCAFFOLD_CHECK =
            ITEMS.register("scaffold_check", () -> new ScaffoldCheckItem(new Item.Properties()));

    /** D-106 写入预算自检（零参数）：预算用满后不再改世界、如实失败。 */
    public static final RegistryObject<Item> WRITE_BUDGET_CHECK =
            ITEMS.register("write_budget_check", () -> new WriteBudgetCheckItem(new Item.Properties()));

    /** J8 可持续伐木区（零参数右键）：设定测试区域并起区域型 Job（巡查 → 砍 → 继续巡查）。 */
    public static final RegistryObject<Item> REGION_LUMBER =
            ITEMS.register("region_lumber", () -> new RegionLumberItem(new Item.Properties()));

    /** 串联回归电池（零参数）：一次右键跑完 26 项常用回归（含决策层 6 步），末尾一行 SUMMARY。 */
    public static final RegistryObject<Item> REGRESSION_BATTERY =
            ITEMS.register("regression_battery", () -> new RegressionBatteryItem(new Item.Properties()));

    /** R2 限次清障"换候选"自检（零参数）：一个候选失败要换下一个，而不是放弃整棵树。 */
    public static final RegistryObject<Item> CLEAR_RETRY_CHECK =
            ITEMS.register("clear_retry_check", () -> new ClearRetryCheckItem(new Item.Properties()));

    /** J6-b1b 恢复自检（零参数）：把 bot 送到待恢复方块旁再跑恢复任务。 */
    public static final RegistryObject<Item> RESTORE_CHECK =
            ITEMS.register("restore_check", () -> new RestoreCheckItem(new Item.Properties()));

    /** J5 挖掘 Job 夹具入口（零参数，ore_course 场景）。 */
    public static final RegistryObject<Item> MINE_JOB =
            ITEMS.register("mine_job", () -> new MineJobItem(new Item.Properties()));

    /** J4 伐木失败语义自检：五条终止路径各一个用例。 */
    public static final RegistryObject<Item> LUMBER_FAILURE_CHECK =
            ITEMS.register("lumber_failure_check", () -> new LumberFailureCheckItem(new Item.Properties()));

    /** S3.5 收集授权选区器（零参数）：右键记 pos1、潜行右键记 pos2 ⇒ 生成 GRANTED_AREA 授权。 */
    public static final RegistryObject<Item> COLLECT_GRANT =
            ITEMS.register("collect_grant", () -> new CollectGrantItem(new Item.Properties()));

    /** S3.5 被动拾取闸门自检（零参数）：我方掉落物该捡、外来掉落物该被拦下。 */
    public static final RegistryObject<Item> PICKUP_GATE_CHECK =
            ITEMS.register("pickup_gate_check", () -> new PickupGateCheckItem(new Item.Properties()));

    /** L2 菜单协议最小验证探针（零参数，约 8 秒，故意放慢便于观察开盖/音效/逐次点击）。 */
    public static final RegistryObject<Item> MENU_PROBE =
            ITEMS.register("menu_probe", () -> new MenuProbeItem(new Item.Properties()));

    /** K-3 安全点停止自检（零参数，约 5 秒）：升空后请求停止 ⇒ 延后到安全点 / 超时强停。 */
    public static final RegistryObject<Item> K3_STOP_CHECK =
            ITEMS.register("k3_stop_check", () -> new K3StopCheckItem(new Item.Properties()));

    /** R2 传输模块自检（零参数，约 1~2 秒）：主流程 / 端点选择 / 选择器事件 / 命令解析。 */
    public static final RegistryObject<Item> TRANSFER_CHECK =
            ITEMS.register("transfer_check", () -> new TransferCheckItem(new Item.Properties()));

    /** 基-7 前缀搜索自检（零参数，约 1 秒，纯规划）：PARTIAL 前缀 / 同目标可达 / 真失败不给前缀。 */
    public static final RegistryObject<Item> PARTIAL_SEARCH_CHECK =
            ITEMS.register("partial_search_check", () -> new PartialSearchCheckItem(new Item.Properties()));

    /** 基-8 能力闸门自检（零参数，约 1 秒，纯逻辑）：保护区/资源/工具/预算/声明一致性。 */
    public static final RegistryObject<Item> CAPABILITY_GATE_CHECK =
            ITEMS.register("capability_gate_check", () -> new CapabilityGateCheckItem(new Item.Properties()));

    /** 阶段 3-A / A1（D-185）**只读配方查询自检**（零参数，约 1 秒）：正例/缺料/3×3/无配方/机器专属 + 只读断言。 */
    public static final RegistryObject<Item> CRAFT_CHECK =
            ITEMS.register("craft_check", () -> new CraftCheckItem(new Item.Properties()));

    /** 阶段 3-A / A2（D-186）**随身 2×2 合成自检**（零参数，约 1 秒）：真消耗真产物 + 缺料如实失败 + 网格清理。 */
    public static final RegistryObject<Item> CRAFT_ACTION_CHECK =
            ITEMS.register("craft_action_check", () -> new CraftActionCheckItem(new Item.Properties()));

    /** 阶段 3-A / A3（D-188）**现成工作台 3×3 合成自检**（零参数，约 3 秒）：找台→走过去→开菜单→合成，**零世界写入**。 */
    public static final RegistryObject<Item> CRAFT_TABLE_CHECK =
            ITEMS.register("craft_table_check", () -> new CraftTableCheckItem(new Item.Properties()));

    /** 阶段 3-A / A3b（D-190）**自放工作站合成自检**（零参数）：放台→合成→**拆回**（建拆同权）。 */
    public static final RegistryObject<Item> CRAFT_STATION_CHECK =
            ITEMS.register("craft_station_check", () -> new CraftStationCheckItem(new Item.Properties()));

    /** 阶段 3-A / S1-3（D-192）**合成网格探针**（零参数、只读）：看任一站点菜单里的网格/结果槽事实。 */
    public static final RegistryObject<Item> CRAFT_GRID_PROBE =
            ITEMS.register("craft_grid_probe", () -> new CraftGridProbeItem(new Item.Properties()));

    /** 阶段 3-A / L2（D-194）**工作站装配自检**（零参数）：装升级→能力验证→取回复原。 */
    public static final RegistryObject<Item> CRAFT_STATION_PROVISION_CHECK =
            ITEMS.register("craft_station_provision_check",
                    () -> new CraftStationProvisionCheckItem(new Item.Properties()));

    /** 阶段 3-A / C（D-195）**模组站点真合成自检**（零参数）：装升级→用页签合成→拆回。 */
    public static final RegistryObject<Item> CRAFT_STATION_CRAFT_CHECK =
            ITEMS.register("craft_station_craft_check",
                    () -> new CraftStationCraftCheckItem(new Item.Properties()));

    /** 基-9 工具供给自检（零参数，约 2 秒）：换更好的 / 没得换如实报 / **不能凭空变出工具**。 */
    public static final RegistryObject<Item> TOOL_SUPPLY_CHECK =
            ITEMS.register("tool_supply_check", () -> new ToolSupplyCheckItem(new Item.Properties()));

    /** 基-5 LLM 上抛契约自检（零参数，约 1 秒）：Job 失败报告 / 产物判定口径 / 结构化拒绝回读。 */
    public static final RegistryObject<Item> LLM_CONTRACT_CHECK =
            ITEMS.register("llm_contract_check", () -> new LlmContractCheckItem(new Item.Properties()));

    /** 基-4 决策 trace / 跨重启语义自检（零参数，约 1 秒）：落盘、内存尾、NBT 往返、"只报一次"。 */
    public static final RegistryObject<Item> DECISION_TRACE_CHECK =
            ITEMS.register("decision_trace_check", () -> new DecisionTraceCheckItem(new Item.Properties()));

    /** 基-1 可回收性自检（零参数，约 1 秒，纯计算）：验等级真的被算出来、且准入校验不是恒假。 */
    public static final RegistryObject<Item> RECOVERABILITY_CHECK =
            ITEMS.register("recoverability_check", () -> new RecoverabilityCheckItem(new Item.Properties()));

    /** S4 事件阈值自检（零参数，约 25 秒）：工具见底/卡住两类病症"该报时报、只报一次"。 */
    public static final RegistryObject<Item> EVENT_THRESHOLD_CHECK =
            ITEMS.register("event_threshold_check", () -> new EventThresholdCheckItem(new Item.Properties()));

    /** S3 请示通道演示（零参数）：发起 demo_ask 请示，验"允许执行/超时自动返回"。 */
    public static final RegistryObject<Item> PERMISSION_DEMO =
            ITEMS.register("permission_demo", () -> new PermissionDemoItem(new Item.Properties()));

    /** D-137 掉落物搜索+捡拾自检（零参数）：夹具生成掉落物并登记为我方 ⇒ 起 COLLECT Job。 */
    public static final RegistryObject<Item> COLLECT_JOB =
            ITEMS.register("collect_job", () -> new CollectJobItem(new Item.Properties()));

    /** S1 状态汇报（零参数）：任务树 + 生存 + 背包 + 最近事件 + 账本（确定性事实）。 */
    public static final RegistryObject<Item> BOT_REPORT =
            ITEMS.register("bot_report", () -> new BotReportItem(new Item.Properties()));

    /** D-135 决策层自检（零参数）：打印 LLM 配置 + 权威快照 + 强制一次目标级决策。 */
    public static final RegistryObject<Item> GOAL_DIRECTOR =
            ITEMS.register("goal_director", () -> new GoalDirectorItem(new Item.Properties()));

    /** D-134 统一 Job 入口自检（零参数，`lumber_course` 场景）：验 `JobRequest → JobLauncher → assignJob`。 */
    public static final RegistryObject<Item> JOB_LAUNCHER =
            ITEMS.register("job_launcher", () -> new JobLauncherItem(new Item.Properties()));

    /** S-2 未加载区块/世界边界门控自检（零参数，无头规划三用例）。 */
    public static final RegistryObject<Item> CHUNK_GUARD_CHECK =
            ITEMS.register("chunk_guard_check", () -> new ChunkGuardCheckItem(new Item.Properties()));

    /** S-4 挖掘前流体风险自检（零参数，`fluid_mine_course` 场景）。 */
    public static final RegistryObject<Item> FLUID_MINE_CHECK =
            ITEMS.register("fluid_mine_check", () -> new FluidMineCheckItem(new Item.Properties()));

    /** S-1 维生出口自检（零参数，`survival_course` 场景）：否决之后必须给出一次明确出口。 */
    public static final RegistryObject<Item> SURVIVAL_EXIT_CHECK =
            ITEMS.register("survival_exit_check", () -> new SurvivalExitCheckItem(new Item.Properties()));

    /** 挖掘专项串联回归（批次 5，场景专属）。 */
    public static final RegistryObject<Item> MINE_REGRESSION =
            ITEMS.register("mine_regression", () -> new MineRegressionItem(new Item.Properties()));

    /** 模组兼容自检：Ore Excavation 连锁挖掘的掉落物捕获与收集（场景专属）。 */
    public static final RegistryObject<Item> CHAIN_TEST_RUNNER =
            ITEMS.register("chain_test_runner", () -> new ChainTestRunnerItem(new Item.Properties()));

    /** D-068 破坏进入验证器（BREAK_AND_ENTER，场景专属）。 */
    public static final RegistryObject<Item> PATHING_BREAK_ENTER =
            ITEMS.register("pathing_break_enter", () -> new PathingBreakEnterItem(new Item.Properties()));

    /** D-058 落差验证器（FALL 2~3 格，场景专属）。 */
    public static final RegistryObject<Item> PATHING_FALL =
            ITEMS.register("pathing_fall", () -> new PathingFallItem(new Item.Properties()));

    /** D-057 岩浆路线安全检查器（场景专属）。 */
    public static final RegistryObject<Item> PATHING_LAVA_GUARD =
            ITEMS.register("pathing_lava_guard", () -> new PathingLavaGuardItem(new Item.Properties()));

    /** D-055 垂直上升验证器（PILLAR，场景专属）。 */
    public static final RegistryObject<Item> PATHING_PILLAR =
            ITEMS.register("pathing_pillar", () -> new PathingPillarItem(new Item.Properties()));


    private AliceItems() {
    }
}
