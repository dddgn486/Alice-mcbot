package com.dddgn.alice.job;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;

import java.util.Objects;

/**
 * 目标级任务的**全部外部输入**（D-080）：一个 {@code GoalSpec} 驱动一个 {@link Job}。
 *
 * <p>设计依据 `docs/JOB_LAYER_DESIGN.md` §4。要点：
 * <ul>
 *   <li>**完成判据 = 产物入包**（原始设计 §4.2 标准 3），故配额以"物品数/单位数"表达，不用"动作发生过"；</li>
 *   <li>`maxTicks` **必须存在**：禁止长任务空转（终止语义是 L3 的验收项之一）；</li>
 *   <li>v1 支持 {@link Kind#COLLECT_ITEMS} 与 {@link Kind#HARVEST_UNITS}；{@link Kind#UNTIL_FULL} 已定义但未实现（§11-②）。</li>
 * </ul>
 */
public record GoalSpec(Kind kind, int quota, BlockPos center, int radius, int maxTicks,
                       boolean stopWhenFull, TagKey<Item> productTag) {

    public enum Kind {
        /** 产物入包数量达到配额（按 `productTag` 统计背包增量）。 */
        COLLECT_ITEMS,
        /** 完成的"单位"数达到配额（伐木：整棵砍完才算 1 个单位）。 */
        HARVEST_UNITS,
        /** 砍到背包满（已定义，v1 未实现）。 */
        UNTIL_FULL
    }

    public GoalSpec {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(center, "center");
        center = center.immutable();
        if (quota <= 0) {
            throw new IllegalArgumentException("quota must be > 0");
        }
        if (radius <= 0) {
            throw new IllegalArgumentException("radius must be > 0");
        }
        if (maxTicks <= 0) {
            throw new IllegalArgumentException("maxTicks must be > 0");
        }
    }

    /** 伐木：在 center 半径内砍完 quota 棵。 */
    public static GoalSpec harvestUnits(BlockPos center, int radius, int units, int maxTicks) {
        return new GoalSpec(Kind.HARVEST_UNITS, units, center, radius, maxTicks, false, null);
    }

    /** 采集：在 center 半径内收集 quota 个匹配 `tag` 的产物。 */
    public static GoalSpec collectItems(BlockPos center, int radius, int items,
                                        TagKey<Item> tag, int maxTicks) {
        return new GoalSpec(Kind.COLLECT_ITEMS, items, center, radius, maxTicks, false, tag);
    }

    public String describe() {
        return kind + " quota=" + quota + " center=" + center.toShortString()
                + " radius=" + radius + " maxTicks=" + maxTicks
                + (productTag == null ? "" : " product=" + productTag.location());
    }
}
