package com.dddgn.alice.fixture;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.item.FixtureToolKit;
import com.dddgn.alice.job.JobRequest;
import com.dddgn.alice.tool.ToolProvision;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * ⚠️ **开发期发料策略 = 凭空造物**（{@link ToolProvision} 的另一个实现）。
 *
 * <p><b>为什么它必须住 `fixture/`</b>：造物工具 {@link FixtureToolKit} 是**开发期物**（`item/`）。
 * 生产包（`tool/`、`job/`、`bot/`）**结构上没法**表达"凭空造物"——一写就反向依赖开发期包（`R3`）。
 * ⇒ 这正是 `D-512`「策略由**调用方**传」的结构性理由，⛔ 不是风格偏好。
 *
 * <p><b>谁传它</b>（刀 2 实测，逐条与刀 2 之前的行为**逐字相同**）：
 * <ul>
 *   <li>`item/*Item`（`/give` 物品 = 开发期入口，`D-554`）</li>
 *   <li>`debug/DebugCommands`（`D-560`：现有 `/alice` 命令**全是开发期入口**）</li>
 *   <li>`headless/HeadlessBattery`（无头电池 = 夹具驱动）</li>
 * </ul>
 * ⛔ **`decision/GoalDirector` 不许传它**（那是生产决策路径）—— 门禁
 * `tools/check-provision-containment.sh` 断言③ 钉住。
 *
 * <p><b>测试世界是白板</b>：夹具必须能自证前提（"有镐才能挖"这条判据，前提就得先给镐），
 * 所以开发期入口保留造物。⚠️ 代价与回收条件见 {@link ToolProvision} 类注释。
 */
public final class DevCreateProvision implements ToolProvision {

    /** 无状态 ⇒ 单例（调用方传 `DevCreateProvision.INSTANCE`）。 */
    public static final DevCreateProvision INSTANCE = new DevCreateProvision();

    private DevCreateProvision() {
    }

    @Override
    public String label() {
        return "DEV_CREATE";
    }

    @Override
    public boolean provisionFor(BotPlayer bot, JobRequest request) {
        switch (request.kind()) {
            case LUMBER -> {
                FixtureToolKit.ensureAxe(bot);
                FixtureToolKit.ensurePickaxe(bot);
                cobblestone(bot, 12);
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
                cobblestone(bot, 12);
                String saplingId = request.area() == null ? null
                        : com.dddgn.alice.job.lumber.LumberAreaState.get(bot.getServer())
                        .saplingItem(bot.getUUID());
                var id = saplingId == null ? null : net.minecraft.resources.ResourceLocation.tryParse(saplingId);
                var item = id == null ? null : net.minecraft.core.registries.BuiltInRegistries.ITEM.get(id);
                if (item != null && item != Items.AIR) {
                    hotbarStack(bot, () -> new ItemStack(item), stack -> stack.is(item), 8,
                            "sapling(" + saplingId + ")");
                }
            }
        }
        return true;
    }

    @Override
    public void pickaxe(BotPlayer bot) {
        FixtureToolKit.ensurePickaxe(bot);
    }

    @Override
    public void axe(BotPlayer bot) {
        FixtureToolKit.ensureAxe(bot);
    }

    @Override
    public void cobblestone(BotPlayer bot, int count) {
        hotbarStack(bot, () -> new ItemStack(Items.COBBLESTONE),
                stack -> stack.is(Items.COBBLESTONE), count, "cobblestone");
    }

    @Override
    public void hotbarStack(BotPlayer bot, Supplier<ItemStack> sample, Predicate<ItemStack> match,
                            int count, String what) {
        FixtureToolKit.ensureHotbarStack(bot, sample, match, count, what);
    }
}
