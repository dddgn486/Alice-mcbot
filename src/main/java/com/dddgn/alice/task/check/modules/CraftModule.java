package com.dddgn.alice.task.check.modules;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.task.CraftActionCheckTask;
import com.dddgn.alice.task.CraftCheckTask;
import com.dddgn.alice.task.CraftFurnaceCheckTask;
import com.dddgn.alice.task.CraftGoalCheckTask;
import com.dddgn.alice.task.CraftGridProbeTask;
import com.dddgn.alice.task.CraftStationCheckTask;
import com.dddgn.alice.task.CraftStationCraftCheckTask;
import com.dddgn.alice.task.CraftStationProvisionCheckTask;
import com.dddgn.alice.task.CraftTableCheckTask;
import com.dddgn.alice.task.check.CheckContext;
import com.dddgn.alice.task.check.CheckModule;
import com.dddgn.alice.task.check.CheckProfile;
import com.dddgn.alice.task.check.CheckStep;
import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.Set;

/**
 * **合成 / 工作站模块（R-2：最大的一个模块，12 步）**：把阶段 3-A 的整条合成链从电池里搬出来。
 *
 * <p>为什么它值得**单独一个模块**：① 它是历史上"单跑必红"最疼的一段（旧电池里 `single:craft_table`
 * 依赖前序步骤把区块热起来 ⇒ 单跑就假红 ✓，正是 R-2 要消灭的东西）；② 12 步覆盖四种"执行形状"
 * ——只读查询（`craft_check`）、随身 2×2（`craft_action`）、**自放工作站**（`craft_station`，
 * 第一次真正写世界的合成路径）、**按时间工作**（`craft_furnace` / `craft_cooking`）；
 * ③ 网格发现器的三个入口（随身 / 原版台 / 模组升级页签）在这里统一可读 ✓。
 *
 * <p>**模块独立性的两处硬要求**（否则 `module:craft` 单跑会红）：
 * <ul>
 *   <li>每步自带 `scenes`（`craft_table_course` / `craft_station_course` / `craft_tab_course` /
 *       `furnace_course`）⇒ 编排器在**跑场景之前先热区块**（T-1 顺序修正）✓；</li>
 *   <li>每步自带 `provision`（传送回统一起点；网格发现器还要先 `CraftStation.select` 指定入口 ✓）
 *       ⇒ 不依赖前一步把 bot 留在哪 ✗。</li>
 * </ul>
 *
 * <p>**依赖模组的四步**（`craft_probe_upgradetab` / `craft_station_provision` / `craft_station_craft` /
 * `craft_cooking`）标 `skippable`：模组不在或站点找不到 ⇒ `SKIP`（环境不具备，**不判红也绝不假绿** ✓）。
 */
public final class CraftModule implements CheckModule {

    @Override
    public String id() {
        return "craft";
    }

    @Override
    public String title() {
        return "合成 / 工作站（只读查询 → 随身 2×2 → 工作台 → 自放站 → 网格发现 → 熔炉）";
    }

    @Override
    public List<CheckStep> steps(CheckContext ctx) {
        BotPlayer bot = ctx.bot();
        var observer = ctx.observer();
        return List.of(
                // 阶段 3-A / A1（D-185）：只读配方查询（正例/缺料/3×3/无配方/机器专属 + 背包未变硬断言）
                CheckStep.of("craft_check", CheckProfile.MAIN, List.of(), null,
                        () -> new CraftCheckTask(bot, observer), 200),
                // 阶段 3-A / A2（D-186）：随身 2×2 合成（真消耗真产物 + 缺料如实失败 + 网格清理）
                CheckStep.of("craft_action", CheckProfile.MAIN, List.of(), null,
                        () -> new CraftActionCheckTask(bot, observer), 200),
                // 阶段 3-A / A3（D-188）：现成工作台 3×3 合成（找台→走位→开菜单→合成 + 零写入断言）
                CheckStep.of("craft_table", CheckProfile.MAIN,
                        List.of("alice_test:craft_table_course"),
                        () -> to(bot, CraftTableCheckTask.START),
                        () -> new CraftTableCheckTask(bot, observer), 900),
                // 阶段 3-A / A3b（D-190）：**自放工作站**（第一次真正写世界的合成路径）+ 建拆同权
                CheckStep.of("craft_station", CheckProfile.MAIN,
                        List.of("alice_test:craft_station_course"),
                        () -> to(bot, CraftTableCheckTask.START),
                        () -> new CraftStationCheckTask(bot, observer), 2600),
                // 阶段 3-A / S1-3（D-192）：**通用网格发现**的回归三连 ——
                // ① 随身 2×2（Auto⇒inventory）② 原版工作台 3×3（零模组依赖）③ 模组"升级页签"（不可用则 SKIP）
                CheckStep.of("craft_probe_inventory", CheckProfile.MAIN, List.of(),
                        () -> select(bot, "inventory"),
                        () -> new CraftGridProbeTask(bot, observer, 2, 2), 300),
                CheckStep.of("craft_probe_table", CheckProfile.MAIN,
                        List.of("alice_test:craft_table_course"),
                        () -> select(bot, "table"),
                        () -> new CraftGridProbeTask(bot, observer, 3, 3), 300),
                CheckStep.skippable("craft_probe_upgradetab", CheckProfile.MAIN,
                        List.of("alice_test:craft_tab_course"),
                        () -> select(bot, "upgradetab"),
                        () -> new CraftGridProbeTask(bot, observer), 400,
                        task -> task.failureReason().contains("station_opened")),
                // 阶段 3-A / L2（D-194）：**工作站装配**（装升级 → 能力验证 3×3 → 取回复原）
                // 依赖精妙存储：模组不在或站点不在 ⇒ SKIP（环境不具备，不判红）
                CheckStep.skippable("craft_station_provision", CheckProfile.MAIN,
                        List.of("alice_test:craft_tab_course"),
                        () -> to(bot, CraftGridProbeTask.START),
                        () -> new CraftStationProvisionCheckTask(bot, observer), 900,
                        task -> task.failureReason().contains("mod_present")
                                || task.failureReason().contains("station_found")),
                // 阶段 3-A / C（D-195）：**模组站点真合成**（装升级 → 用页签 3×3 合成 → 拆回）
                CheckStep.skippable("craft_station_craft", CheckProfile.MAIN,
                        List.of("alice_test:craft_tab_course"),
                        () -> to(bot, CraftGridProbeTask.START),
                        () -> new CraftStationCraftCheckTask(bot, observer), 1200,
                        task -> task.failureReason().contains("mod_present")
                                || task.failureReason().contains("station_found")),
                // 阶段 3-A / A4（D-196）：**熔炉**（"按时间工作"的另一种执行形状：放料→等烧→取产物→不留半成品）
                CheckStep.of("craft_furnace", CheckProfile.MAIN,
                        List.of("alice_test:furnace_course"),
                        () -> to(bot, CraftFurnaceCheckTask.START),
                        () -> new CraftFurnaceCheckTask(bot, observer), 1000),
                // 阶段 3-A / A4b（D-198）：**菜单型炉子**（"熔炼升级页签"）—— 复用同一发现器，装升级→烧→取→拆回
                CheckStep.skippable("craft_cooking", CheckProfile.MAIN,
                        List.of("alice_test:craft_tab_course"),
                        () -> to(bot, CraftFurnaceCheckTask.START),
                        () -> new CraftFurnaceCheckTask(bot, observer, true), 1600,
                        task -> task.failureReason().contains("mod_present")
                                || task.failureReason().contains("station_found")),
                // 阶段 3-A / A5（D-199）：**决策层合成自检**（可做清单 + 严格解析 + 生产路径 CraftJob）；
                // **不需要场景**（随身 2×2 用背包里的 4 块木板做工作台）
                CheckStep.of("craft_goal", CheckProfile.MAIN, List.of(), () -> { },
                        () -> new CraftGoalCheckTask(bot, observer), 600));
    }

    /** 传送到统一起点（与电池 `teleportBot` 逐字段一致 ✓；模块不能调它的私有方法 ✗ ⇒ 这里复制同一套 ✓）。 */
    private static void to(BotPlayer bot, BlockPos foot) {
        bot.teleportTo(bot.serverLevel(), foot.getX() + 0.5D, foot.getY(), foot.getZ() + 0.5D,
                Set.of(), bot.getYRot(), bot.getXRot());
        bot.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        bot.controller().stopMovement();
    }

    /** 网格发现器的入口指定：**先传送再指定**（与电池里的顺序一致 ✓，顺序反了会让选择被传送清掉的风险）。 */
    private static void select(BotPlayer bot, String station) {
        to(bot, CraftGridProbeTask.START);
        com.dddgn.alice.task.craft.CraftStation.select(bot, station);
    }
}
