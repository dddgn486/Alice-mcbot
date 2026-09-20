package com.dddgn.alice.job.mine;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * **矿物价值表 = 数据包标签**（用户 2026-09-20 裁定其一：表放标签，权重放配置）。
 *
 * <p>为什么是标签而不是硬编码表：① **数据驱动**（存档/整合包自己加，不用改代码）；② 别的模组要接进来
 * 只需往 `#alice:mine_value/tierN` 里加自己的方块 ⇒ 与"模组适配需求驱动（`D-219`）"一致；
 * ③ 不必猜模组语义（标签是标准机制，而"这块矿值多少"本来就是**用户的口径**）。
 *
 * <p>⭐⭐ **未知方块一律 0**（= 没有价值加成，**不是**"低级"也**不是**"高级"）：<b>不猜</b>是这条线的底线
 * （与"未知模组能力默认只读"同一口径）。所以一个没被登记过的矿石既不会被优先，也不会被歧视 ——
 * 它按**成本**参与竞争。
 *
 * <p>⚠️ **默认表只覆盖原版**。模组矿石要由使用者加标签（需求驱动）；缺了就按 0 处理并**如实**在
 * 决策日志里显示 `value=0(unknown)`，不静默假设。
 */
public final class MineValueTable {

    /** 低价值档（煤/铜/铁/青金石/石英）。 */
    public static final TagKey<Block> TIER1 = tag("tier1");
    /** 中价值档（金/红石）。 */
    public static final TagKey<Block> TIER2 = tag("tier2");
    /** 高价值档（钻石/绿宝石/下界合金）。 */
    public static final TagKey<Block> TIER3 = tag("tier3");

    private MineValueTable() {
    }

    private static TagKey<Block> tag(String path) {
        return TagKey.create(Registries.BLOCK, new ResourceLocation("alice", "mine_value/" + path));
    }

    /** 档位：`0` = 未登记（未知），`1..3` = 已登记档位。 */
    public static int tier(BlockState state) {
        if (state.is(TIER3)) {
            return 3;
        }
        if (state.is(TIER2)) {
            return 2;
        }
        if (state.is(TIER1)) {
            return 1;
        }
        return 0;
    }

    /**
     * **归一化价值** `0..1`（tier3 = 1.0，tier2 = 2/3，tier1 = 1/3，未登记 = 0）。
     *
     * <p>归一化是为了让配置项有**可解释的单位**：`valueWeight` 就是"最高档矿最多值多少格的额外路程"
     * （见 {@code MineCostConfig}）。⇒ 权重小 ⇒ 仍然挖最近的；权重大 ⇒ 才愿意绕路。
     */
    public static double normalized(BlockState state) {
        int tier = tier(state);
        return tier <= 0 ? 0.0D : tier / 3.0D;
    }

    /**
     * **按方块 id 取归一化价值**（候选的 `block` **特征**口径）。
     *
     * <p>⭐ 为什么价值取自**候选快照**而不是"现场读世界"：候选集本身就是**决策时刻的快照**
     * （`MineJob` 选完还会做身份复检），价值只影响**排序**、不影响安全 ⇒ 用快照让策略**完全不读世界**
     * （可注入脚本化候选做确定性判据，也不需要额外方块读）。世界若已变，由身份复检如实处理
     * （`target_replaced`），不是靠"偷偷按新方块算钱"。
     *
     * <p>解析不出来 ⇒ **0**（不猜；`-` 是 `Candidate.feature` 的缺省值）。
     */
    public static double normalizedById(String blockId) {
        if (blockId == null || blockId.isBlank() || "-".equals(blockId)) {
            return 0.0D;
        }
        ResourceLocation id = ResourceLocation.tryParse(blockId);
        if (id == null) {
            return 0.0D;
        }
        Block block = net.minecraftforge.registries.ForgeRegistries.BLOCKS.getValue(id);
        return block == null ? 0.0D : normalized(block.defaultBlockState());
    }

    /** 决策日志里要能看出"这块矿认没认出来"（`unknown` 必须显式，不许静默当 0 而又不说）。 */
    public static String describe(BlockState state) {
        int tier = tier(state);
        return tier <= 0 ? "value=0(unknown)" : ("value=" + String.format(java.util.Locale.ROOT, "%.2f",
                normalized(state)) + "(tier" + tier + ")");
    }
}
