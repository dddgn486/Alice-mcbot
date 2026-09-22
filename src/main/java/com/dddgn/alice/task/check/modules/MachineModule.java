package com.dddgn.alice.task.check.modules;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.task.MachineCycleCheckTask;
import com.dddgn.alice.task.MachineProbeTask;
import com.dddgn.alice.task.MachineStationProbeTask;
import com.dddgn.alice.task.check.CheckContext;
import com.dddgn.alice.task.check.CheckModule;
import com.dddgn.alice.task.check.CheckProfile;
import com.dddgn.alice.task.check.CheckStep;
import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.Set;

/**
 * **机器路线模块（R-2 第四片）**：`machine_route` · `machine_station` · `machine_cycle` · `craft_machine`。
 *
 * <p>为什么这四步归一类：它们都在回答同一个问题的**不同深度** ——
 * ① `machine_route` 只读"上游自述的配方"（查询层给 `MACHINE_ROUTE`）；
 * ② `machine_station` 只读"站点自述"（按 `MachineMap` 认机器 + 菜单类/配方类型一致）；
 * ③ `machine_cycle` **真把一台机器跑起来一次**（放料→等→取产物，第一次容器写入）；
 * ④ `craft_machine` 是它的**生产入口**（`CraftJob` 走数据驱动执行准入，同一份 `MachineCycle` 实现）。
 *
 * <p>**依赖模组**：四步全标 `skippable`（模组不在/该命名空间没有机器 ⇒ `machine_absent` ⇒ `SKIP`，
 * 不判红也绝不假绿 ✓）。
 *
 * <p>**本模块自带的前提（R-2 纪律）**：三步带场景的都先 `provision` 把 bot **传送到课程起点**
 * —— 编排器的顺序是「**先发料/传送（热区块）→ 再跑场景函数**」✓，不传送就会让 `/fill` 落在冷区块上，
 * 于是"机器找不到"（看起来像模组回归，其实是场景没落地 ✗）。
 */
public final class MachineModule implements CheckModule {

    @Override
    public String id() {
        return "machine";
    }

    @Override
    public String title() {
        return "机器路线（自述只读 → 站点只读 → 单机闭环 → 生产入口）";
    }

    @Override
    public List<CheckStep> steps(CheckContext ctx) {
        BotPlayer bot = ctx.bot();
        var observer = ctx.observer();
        List<String> course = List.of("alice_test:machine_course");
        return List.of(
                // 阶段 3-B / S1（D-204 / §6.51）：**机器配方只读**（问上游自述读输入/输出 + 查询层给 MACHINE_ROUTE）；
                // 模组不在/该命名空间没有机器类型 ⇒ SKIP（不判红）。零写入、无场景。
                CheckStep.skippable("machine_route", CheckProfile.EXTRA, List.of(), () -> { },
                        () -> new MachineProbeTask(bot, observer), 200,
                        task -> task.failureReason().contains("_absent")),
                // 阶段 3-B / S2+S3（D-206 / D-209）：**机器站点只读**，按 `MachineMap` 认机器
                // （半径内表里登记的方块每类一台 ⇒ 双机器场景也能自证点对了哪台）；
                // 断言菜单类与"方块实体自述配方类型 == 表里的类型"。零写入。
                CheckStep.skippable("machine_station", CheckProfile.EXTRA, course,
                        () -> to(bot, MachineStationProbeTask.START),
                        () -> new MachineStationProbeTask(bot, observer), 400,
                        task -> task.failureReason().contains("_absent")),
                // 阶段 3-B / S4（D-213）：**单机最小闭环** —— 真的把一台机器跑起来一次（放料 → 等 → 取产物）。
                // 第一次**容器写入**：`WriteBudget.consumeContainerWrite` + 理由 CONTAINER_TRANSFER +
                // requester `machine-cycle`（矩阵登记为 CONTAINER）；写入一律**按结果验证**，不猜槽位语义。
                // 预算 1600 > 任务自身 MAX_TICKS 1400（让任务的守卫先报**具体**失败原因）。
                CheckStep.skippable("machine_cycle", CheckProfile.EXTRA, course,
                        () -> to(bot, MachineCycleCheckTask.CYCLE_START),
                        () -> new MachineCycleCheckTask(bot, observer), 1600,
                        task -> task.failureReason().contains("_absent")),
                // 阶段 3-B / (c) 增量 2（D-217）：**机器路线的生产路径**（`CraftJob` 真的驱动一台机器）。
                // 与上一步**同一份闭环实现**、不同入口：查询层判 MACHINE_ROUTE → 执行准入 EXECUTABLE → 起真 `CraftJob`；
                // **不补电**：没电就是 `machine_no_energy` 如实失败（D-216 红线①）。
                // 预算 1800 > 夹具 MAX_TICKS 1600 > CraftJob 预算 1400（让任务先报**具体**失败原因）。
                CheckStep.skippable("craft_machine", CheckProfile.MAIN, course,
                        () -> to(bot, MachineCycleCheckTask.CYCLE_START),
                        () -> new com.dddgn.alice.task.CraftMachineCheckTask(bot, observer), 1800,
                        task -> task.failureReason().contains("_absent")));
    }

    /** 传送到课程起点（与电池 `teleportBot` 逐字段一致 ✓；同时起"**先热区块再 fill**"的作用 ✓）。 */
    private static void to(BotPlayer bot, BlockPos foot) {
        bot.teleportTo(bot.serverLevel(), foot.getX() + 0.5D, foot.getY(), foot.getZ() + 0.5D,
                Set.of(), bot.getYRot(), bot.getXRot());
        bot.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        bot.controller().stopMovement();
    }
}
