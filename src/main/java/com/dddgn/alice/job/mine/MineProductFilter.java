package com.dddgn.alice.job.mine;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * **挖掘产物的判定口径**（J-6：取代 `MineJob` 里那份硬编码矿物清单）。
 *
 * <p>为什么必须改（2026-09-12 复核 §3 J-6）：
 * <ol>
 *   <li>**模组矿物统计不到** —— 硬编码只认 8 个原版标签 + 8 个原版物品 ⇒ 装了模组后
 *       "挖到配额"永远不满足，任务只能跑到超时（真实后果：`partial_quota`/`goal_timeout`）；</li>
 *   <li>**无视调用方给的目标** —— LLM 可以指定 `productTag`（如 `#forge:ores/iron`），
 *       但配额统计仍在数煤/钻石 ⇒ "要铁的配额被煤顶满"，语义错位。</li>
 * </ol>
 *
 * <p>本类的口径（**目标驱动 + 标签族兜底**，两条都写死在这里、不再散落）：
 * <ul>
 *   <li>指定了 `productTag` ⇒ **只认它**；若它是 `forge:ores/<material>`，**同时认**
 *       `forge:raw_materials/<material>`（Forge 约定：矿掉原矿）—— 否则"挖铁"会因为统计不到原铁而永远差一个；</li>
 *   <li>未指定 ⇒ 认**标签族** `forge:ores/*` + `forge:raw_materials/*`（模组矿物天然覆盖），
 *       外加原版掉落物兜底集合（煤/钻石/绿宝石/青金石/红石/石英、三种原矿）——
 *       因为原版这类掉落不一定挂在 `raw_materials` 下。</li>
 * </ul>
 *
 * <p>纯函数、无世界依赖 ⇒ 可在自检里直接断言（"标签族真的覆盖模组矿物"这件事不该靠读代码相信）。
 */
public final class MineProductFilter {

    /** 模组/原版通用的矿物标签族前缀（Forge 约定）。 */
    private static final String ORE_PREFIX = "ores/";
    private static final String RAW_PREFIX = "raw_materials/";

    /** 原版掉落物兜底（这些不一定挂在 Forge 的 `raw_materials` 下）。 */
    private static final Set<Item> VANILLA_FALLBACK = Set.of(
            Items.COAL, Items.DIAMOND, Items.EMERALD, Items.LAPIS_LAZULI, Items.REDSTONE, Items.QUARTZ,
            Items.RAW_IRON, Items.RAW_COPPER, Items.RAW_GOLD,
            Items.COAL_ORE, Items.IRON_ORE, Items.COPPER_ORE, Items.GOLD_ORE, Items.DIAMOND_ORE,
            Items.EMERALD_ORE, Items.LAPIS_ORE, Items.REDSTONE_ORE, Items.NETHER_QUARTZ_ORE,
            Items.DEEPSLATE_COAL_ORE, Items.DEEPSLATE_IRON_ORE, Items.DEEPSLATE_COPPER_ORE,
            Items.DEEPSLATE_GOLD_ORE, Items.DEEPSLATE_DIAMOND_ORE, Items.DEEPSLATE_EMERALD_ORE,
            Items.DEEPSLATE_LAPIS_ORE, Items.DEEPSLATE_REDSTONE_ORE);

    private final TagKey<Item> declared;
    private final TagKey<Item> declaredSibling;

    private MineProductFilter(TagKey<Item> declared, TagKey<Item> declaredSibling) {
        this.declared = declared;
        this.declaredSibling = declaredSibling;
    }

    /** 按 `GoalSpec.productTag` 构造（null = 未指定 ⇒ 走标签族兜底）。 */
    public static MineProductFilter forTag(TagKey<Item> productTag) {
        if (productTag == null) {
            return new MineProductFilter(null, null);
        }
        ResourceLocation id = productTag.location();
        if (id.getPath().startsWith(ORE_PREFIX)) {
            String material = id.getPath().substring(ORE_PREFIX.length());
            TagKey<Item> sibling = TagKey.create(Registries.ITEM,
                    new ResourceLocation(id.getNamespace(), RAW_PREFIX + material));
            return new MineProductFilter(productTag, sibling);
        }
        return new MineProductFilter(productTag, null);
    }

    public boolean matches(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        if (declared != null) {
            return stack.is(declared) || (declaredSibling != null && stack.is(declaredSibling));
        }
        if (VANILLA_FALLBACK.contains(stack.getItem())) {
            return true;
        }
        // 标签族：任一 `forge:ores/*` 或 `forge:raw_materials/*`（模组矿物靠这条覆盖）
        for (TagKey<Item> key : familyTags()) {
            if (stack.is(key)) {
                return true;
            }
        }
        return false;
    }

    public String describe() {
        if (declared != null) {
            return "#" + declared.location()
                    + (declaredSibling == null ? "" : " | #" + declaredSibling.location());
        }
        return "默认：forge:ores/* + forge:raw_materials/* + 原版掉落兜底"
                + "（familyTags=" + familyTags().size() + "）";
    }

    /** `forge:ores/*` 与 `forge:raw_materials/*` 的实际标签集合（从注册表枚举，不写死路径）。 */
    private static Set<TagKey<Item>> familyTags() {
        return FamilyTags.HOLDER;
    }

    private static final class FamilyTags {
        private static final Set<TagKey<Item>> HOLDER = build();

        private static Set<TagKey<Item>> build() {
            Set<TagKey<Item>> keys = new LinkedHashSet<>();
            try {
                BuiltInRegistries.ITEM.getTagNames().forEach(key -> {
                    ResourceLocation id = key.location();
                    String path = id.getPath();
                    if ("forge".equals(id.getNamespace())
                            && (path.startsWith(ORE_PREFIX) || path.startsWith(RAW_PREFIX))) {
                        keys.add(key);
                    }
                });
            } catch (RuntimeException exception) {
                // 注册表未就绪（极早期调用）：退回原版矿石标签，保证不炸
                keys.add(ItemTags.COAL_ORES);
                keys.add(ItemTags.IRON_ORES);
                keys.add(ItemTags.COPPER_ORES);
                keys.add(ItemTags.GOLD_ORES);
                keys.add(ItemTags.DIAMOND_ORES);
                keys.add(ItemTags.EMERALD_ORES);
                keys.add(ItemTags.LAPIS_ORES);
                keys.add(ItemTags.REDSTONE_ORES);
            }
            return Set.copyOf(keys);
        }
    }

    /** 自检用：标签族规模（=0 说明注册表里没有 Forge 约定标签，兜底口径已退回原版）。 */
    public static int familyTagCount() {
        return familyTags().size();
    }
}
