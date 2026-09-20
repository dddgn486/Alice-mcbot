package com.dddgn.alice.job;

import net.minecraft.core.BlockPos;

/**
 * **起一个 Job 的请求**（D-134 / ② 决策层契约）：把"起哪个 Job + 什么参数"变成**一个数据对象**。
 *
 * <p>为什么必须有它：在此之前 `BotManager` 是按领域**逐域硬编码**入口
 * （`assignLumberJob` / `assignMineJob` / `assignRegionLumber`），调用方必须自己 new 出具体的 Job 类
 * ⇒ 决策层（LLM）没法"用**一个动作**起任意 Job"，只能为每种 Job 写一份硬编码分支。
 * 有了 `JobRequest` + {@link JobLauncher}，"决策层的动作词汇表"就能收敛成
 * `start_job(kind, spec)` 一条。
 *
 * <p>**约束**：这里只放"选哪个 Job、什么范围/配额/产物"这类**目标级**参数；
 * 具体怎么走、怎么挖、要不要清障属于 L1/L2 的能力信封，不进这个对象（否则决策层又开始管执行细节）。
 */
public record JobRequest(
        Kind kind,
        /** 目标中心（Job 的搜索中心；夹具里就是起点/树桩附近）。 */
        BlockPos center,
        /** 候选搜索半径（格）。 */
        int radius,
        /** 配额（砍几棵 / 挖几个）。 */
        int quota,
        /** 该 Job 的 tick 上限。 */
        int maxTicks,
        /** 产物标签（挖掘用，如 `#forge:ores/iron`）；null = 不限。 */
        String productTag,
        /** 区域型专用：水平区域（玩家划定；null = 非区域型）。 */
        com.dddgn.alice.job.lumber.LumberRegionState.Region region,
        /**
         * **种类分配**（挖掘用，`D-361`）：每条形如 `"<键>=<数量>"`，键 = **标签或方块 id**。
         *
         * <p>空 = 今天的行为（单一总配额）。与 `quota` 的关系：**`quota` 仍是硬上限**，
         * 调用方通常令 `quota = sum(各条数量)`（见 {@code MineKindPlan.sumQuota()}）。
         */
        java.util.List<String> kindQuotas
) {

    public enum Kind {
        /** 一次性伐木（J1–J4/J7）。 */
        LUMBER,
        /** 一次性挖掘（J5）。 */
        MINE,
        /** 可持续伐木区（J8 / MAINTAIN）。 */
        REGION_LUMBER,
        /** 掉落物搜索 + 捡拾（用户 2026-09-12 要求；默认只捡我方掉落物）。 */
        COLLECT,
        /**
         * **合成 / 熔炼**（A5 / D-199）：`productTag` = 要产出的物品 id，`quota` = 产物数量。
         *
         * <p>为什么复用这两个字段：它们本来就是"目标级"的（要什么、要几个）；
         * 用哪个工作站**不进请求** —— 那是**玩家**的切换（用户裁定），Job 只读当前选择。
         */
        CRAFT
    }

    public JobRequest {
        center = center == null ? BlockPos.ZERO : center.immutable();
        radius = Math.max(1, radius);
        quota = Math.max(1, quota);
        maxTicks = Math.max(20, maxTicks);
        kindQuotas = kindQuotas == null ? java.util.List.of() : java.util.List.copyOf(kindQuotas);
    }

    public static JobRequest lumber(BlockPos center, int radius, int quota, int maxTicks) {
        return new JobRequest(Kind.LUMBER, center, radius, quota, maxTicks, null, null, null);
    }

    public static JobRequest mine(BlockPos center, int radius, int quota, int maxTicks, String productTag) {
        return new JobRequest(Kind.MINE, center, radius, quota, maxTicks, productTag, null, null);
    }

    /** 挖掘 + **种类分配**（`D-361`；键 = 标签或方块 id）。 */
    public static JobRequest mineKinds(BlockPos center, int radius, int quota, int maxTicks,
                                       String productTag, java.util.List<String> kindQuotas) {
        return new JobRequest(Kind.MINE, center, radius, quota, maxTicks, productTag, null,
                kindQuotas);
    }

    public static JobRequest region(com.dddgn.alice.job.lumber.LumberRegionState.Region region,
                                    int radius, int quota, int maxTicks) {
        return new JobRequest(Kind.REGION_LUMBER, region.center(), radius, quota, maxTicks, null, region,
                null);
    }

    /**
     * 掉落物搜索 + 捡拾（S3.5：`anyDrops` 布尔**已退役** —— 能不能捡由**归属 + `DropPolicy`**决定：
     * 我方（直接/间接）与**玩家授权区**默认 AUTO；`FOREIGN` 默认 ASK ⇒ 收集路径直接过滤掉）。
     */
    public static JobRequest collect(BlockPos center, int radius, int quota, int maxTicks) {
        return new JobRequest(Kind.COLLECT, center, radius, quota, maxTicks, null, null, null);
    }

    /** 合成 / 熔炼请求（A5）：`itemId` = 产物 id，`count` = 产物数量。 */
    public static JobRequest craft(BlockPos center, String itemId, int count, int maxTicks) {
        return new JobRequest(Kind.CRAFT, center, 1, Math.max(1, count), maxTicks, itemId, null, null);
    }

    /** 一行摘要（决策日志用）。 */
    public String describe() {
        return "kind=" + kind + " center=" + center.toShortString() + " radius=" + radius
                + " quota=" + quota + " maxTicks=" + maxTicks
                + (productTag == null ? "" : " product=" + productTag)
                + (region == null ? "" : " region=" + region.describe())
                + (kindQuotas.isEmpty() ? "" : " kinds=" + kindQuotas);
    }
}
