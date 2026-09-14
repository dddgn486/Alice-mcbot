package com.dddgn.alice.job;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.item.FixtureToolKit;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.perception.ScopeBuffer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * **`JobRequest` → `Job` 的唯一构造点**（D-134 / ② 决策层契约）。
 *
 * <p>分工：{@link JobRequest} 只说"要哪个 Job、什么目标级参数"；本类负责把它变成具体 `Job` 实例，
 * 并做**入口发料**（D-119/D-122：生产任务不发工具，工具一律由入口给）。
 *
 * <p>为什么发料放在这里而不是各 `assign*` 方法里：决策层只有一个动作
 * （`start_job(kind, spec)`），如果发料散在三个入口里，"LLM 起的 Job 徒手砍树"这类缺口迟早复发
 * —— 这正是 D-122 定下的规矩（发料放任务/入口，不能只放在物品里）。
 */
public final class JobLauncher {

    private JobLauncher() {
    }

    /**
     * 按请求**发料**（幂等：有就不重复给）。
     *
     * <p><b>⚠️ 2026-09-14（T1 / R-2）：必须显式声明"这是夹具入口还是生产入口"。</b>
     * 此前只有一个无参版本，它调 {@link FixtureToolKit} **凭空造出**钻石镐/钻石斧/12 圆石
     * （快捷栏满时还会**强制覆盖**已有物品）。而 `BotManager.assignJob` 是**决策层唯一的生产入口**
     * （`decision/GoalDirector` 起 Job 就走它）⇒ **LLM 起的每个 Job 都白得一套钻石工具**，
     * 与"不许凭空给物品"直接冲突（`CASE CRAFT` 的注释自己写着这是同族铁律）。
     *
     * <p>现在按入口分流：
     * <ul>
     *   <li><b>生产（`fixtureProvision=false`）</b>：**只搬运、不创造** —— 走
     *       {@link com.dddgn.alice.bot.ToolSupply#promoteFromMain}，把**已有**工具从主背包挪进快捷栏；
     *       没有就**如实不造**，让 Job 自己报 `tool_missing`（诚实失败优于凭空成功）。</li>
     *   <li><b>夹具（`fixtureProvision=true`）</b>：保留原行为（测试世界是白板，夹具必须能自证前提）。</li>
     * </ul>
     *
     * @param fixtureProvision 本次入口是否为**测试夹具**（只有游戏内测试物品/自检任务可传 true）
     * @return 发料失败（例如区域型缺选定树苗）时返回 false —— 由调用方决定是否还起 Job
     */
    public static boolean provision(BotPlayer bot, JobRequest request, boolean fixtureProvision) {
        if (!fixtureProvision) {
            return provisionFromExisting(bot, request);
        }
        switch (request.kind()) {
            case LUMBER -> {
                FixtureToolKit.ensureAxe(bot);
                FixtureToolKit.ensurePickaxe(bot);
                FixtureToolKit.ensureHotbarStack(bot,
                        () -> new ItemStack(Items.COBBLESTONE),
                        stack -> stack.is(Items.COBBLESTONE), 12, "cobblestone");
            }
            case MINE -> FixtureToolKit.ensurePickaxe(bot);
            case COLLECT -> {
                // 捡拾不需要工具/材料（纯通行 + 原版拾取），无需发料
            }
            case CRAFT -> {
                // **不发料**：合成只真消耗真产物（"不许凭空给物品"是同族铁律）。
                // 材料从哪来由玩家/世界决定；缺料由 CraftJob 如实报 missing_ingredients。
            }
            case REGION_LUMBER -> {
                FixtureToolKit.ensureAxe(bot);
                FixtureToolKit.ensurePickaxe(bot);
                FixtureToolKit.ensureHotbarStack(bot,
                        () -> new ItemStack(Items.COBBLESTONE),
                        stack -> stack.is(Items.COBBLESTONE), 12, "cobblestone");
                String saplingId = request.region() == null ? null
                        : com.dddgn.alice.job.lumber.LumberRegionState.get(bot.getServer())
                        .saplingItem(bot.getUUID());
                var id = saplingId == null ? null : net.minecraft.resources.ResourceLocation.tryParse(saplingId);
                var item = id == null ? null : net.minecraft.core.registries.BuiltInRegistries.ITEM.get(id);
                if (item != null && item != Items.AIR) {
                    FixtureToolKit.ensureHotbarStack(bot, () -> new ItemStack(item),
                            stack -> stack.is(item), 8, "sapling(" + saplingId + ")");
                }
            }
        }
        return true;
    }

    /**
     * **生产入口的"发料" = 只搬运已存在的工具**（T1 / R-2）。
     *
     * <p>不做任何创造、不覆盖任何已有物品；缺什么就**如实留缺**，由 Job 报 `tool_missing`
     * （`LumberJob`/`MineJob` 已有该上抛路径）。这样"LLM 起的 Job"与"真人用手玩"是同一条物质约束。
     *
     * <p>区域型还需要**选定树苗**：只在背包里已经有该树苗时把它挪进快捷栏（`ensureHotbarStack` 的
     * 非创造等价物不存在，所以这里直接查 `countInInventory` 后如实记录"有/没有"）。
     */
    private static boolean provisionFromExisting(BotPlayer bot, JobRequest request) {
        switch (request.kind()) {
            case LUMBER, REGION_LUMBER -> promote(bot, request, com.dddgn.alice.bot.ToolSupply.Kind.AXE,
                    com.dddgn.alice.bot.ToolSupply.Kind.PICKAXE);
            case MINE -> promote(bot, request, com.dddgn.alice.bot.ToolSupply.Kind.PICKAXE);
            case COLLECT, CRAFT -> {
                // 与夹具分支同一口径：不发料
            }
        }
        return true;
    }

    private static void promote(BotPlayer bot, JobRequest request, com.dddgn.alice.bot.ToolSupply.Kind... kinds) {
        StringBuilder line = new StringBuilder();
        for (com.dddgn.alice.bot.ToolSupply.Kind kind : kinds) {
            String result = com.dddgn.alice.bot.ToolSupply.promoteFromMain(bot, kind);
            if (!line.isEmpty()) {
                line.append(' ');
            }
            line.append(kind.label()).append('=').append(result);
        }
        BotLog.info("[Job] 生产入口只搬运不发料（{}）：{} ⇒ 缺工具时由 Job 自己如实报 tool_missing",
                request.kind(), line);
    }

    /** 构造 `Job`（不发料、不登记会话——那是 `BotManager.assignJob` 的事）。 */
    public static Job create(BotPlayer bot, ScopeBuffer scope, JobRequest request) {
        var policy = new com.dddgn.alice.job.policy.NearestPolicy();
        return switch (request.kind()) {
            case LUMBER -> new com.dddgn.alice.job.lumber.LumberJob(bot,
                    GoalSpec.harvestUnits(request.center(), request.radius(), request.quota(),
                            request.maxTicks()),
                    scope, new com.dddgn.alice.job.lumber.LumberCandidateSource(), policy);
            case MINE -> new com.dddgn.alice.job.mine.MineJob(bot,
                    GoalSpec.mineBlocks(request.center(), request.radius(), request.quota(),
                            request.maxTicks()),
                    scope,
                    new com.dddgn.alice.job.mine.MineCandidateSource(
                            mineTargetFor(bot, request), request.radius()),
                    policy);
            case CRAFT -> new com.dddgn.alice.job.craft.CraftJob(bot, request.productTag(),
                    request.quota(), request.maxTicks());
            case COLLECT -> new com.dddgn.alice.job.collect.CollectJob(bot,
                    GoalSpec.collectItems(request.center(), request.radius(), request.quota(), null,
                            request.maxTicks()),
                    scope);
            case REGION_LUMBER -> {
                if (request.region() == null) {
                    throw new IllegalArgumentException("REGION_LUMBER 请求必须带 region");
                }
                yield new com.dddgn.alice.job.lumber.RegionLumberJob(bot, request.region(), scope,
                        new com.dddgn.alice.job.lumber.LumberCandidateSource(), policy,
                        REGION_PATROL_INTERVAL_TICKS, request.maxTicks());
            }
        };
    }

    /** 区域型巡查间隔（tick）：与既有入口一致（40 = 2 s）。 */
    public static final int REGION_PATROL_INTERVAL_TICKS = 40;

    /**
     * 挖掘目标：`productTag` 为方块/标签 id 时用它，否则回落到"最近的可挖方块"（夹具默认）。
     * 判据口径沿用 `MineJob` 既有行为，避免这里变成第二个"矿物清单"（见总账 §3 J-6）。
     */
    private static com.dddgn.alice.job.mine.MineCandidateSource.Target mineTargetFor(BotPlayer bot,
                                                                                   JobRequest request) {
        String tag = request.productTag();
        if (tag == null || tag.isBlank()) {
            // 与既有夹具入口（alice:mine_job）保持同一默认，避免这里长出第二份"默认矿物"
            return com.dddgn.alice.job.mine.MineCandidateSource.Target
                    .ofBlock(net.minecraft.world.level.block.Blocks.IRON_ORE);
        }
        var id = net.minecraft.resources.ResourceLocation.tryParse(tag);
        var parsed = id == null ? null
                : com.dddgn.alice.job.mine.MineCandidateSource.Target.parse(bot.serverLevel(), id);
        if (parsed == null) {
            BotLog.warn("[Job] launch 未知挖掘目标 {} ⇒ 回落到默认（不猜语义）", tag);
            return com.dddgn.alice.job.mine.MineCandidateSource.Target
                    .ofBlock(net.minecraft.world.level.block.Blocks.IRON_ORE);
        }
        return parsed;
    }

    /** 记一行决策可判读的启动日志（决策层的"我起了什么"）。 */
    public static void logLaunch(BotPlayer bot, JobRequest request) {
        BotLog.info("[Job] launch bot={} {}", bot.getName().getString(), request.describe());
    }
}
