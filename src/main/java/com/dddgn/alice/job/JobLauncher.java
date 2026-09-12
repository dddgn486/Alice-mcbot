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
     * @return 发料失败（例如区域型缺选定树苗）时返回 false —— 由调用方决定是否还起 Job
     */
    public static boolean provision(BotPlayer bot, JobRequest request) {
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
