package com.dddgn.alice.task;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.job.lumber.LumberRegionState;
import com.dddgn.alice.job.lumber.RegionLumberJob;
import com.dddgn.alice.log.BotLog;
import net.minecraft.nbt.CompoundTag;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * **区域"扫地面"判定的自检夹具**（`D-344` 片 A/B 的证据）。
 *
 * <p><b>它断言哪一层</b>（技能 §6.9.1 ③「层归属」——答不上来就别写夹具）：
 * <b>判定层</b>（{@link RegionLumberJob#sweepDecision} 的四态判定表）与
 * <b>配置层</b>（{@code LumberRegionState} 的"可配置拾取清单"：派生默认值 + NBT 持久化）。
 * **不**断言端到端（"真去把地面上的苗捡回来"）—— 那需要"零树苗 + 区域地面有苗"的场景与真实跑动，
 * 属**下一步**（登记在 `docs/REGION_REPLANT_ASYNC_DESIGN.md` §7），本夹具**不假装**覆盖了它。
 *
 * <p><b>为什么能零副作用</b>（技能 §陷阱#6：判据提成**纯函数**，夹具别去启动真任务）：
 * 四态判定是纯函数（只吃三个整数）⇒ 直接喂数断言整张表，**不写世界、不派任务**；
 * 清单部分只动 {@code SavedData} 的内存态 + 一次 NBT 往返。**唯一的副作用是"改了区域状态"**
 * ⇒ 因此本夹具**先快照、结束时（含失败路径）原样还原**（技能 §6.9.2「失败必清理」）。
 *
 * <p><b>几何前提</b>：本夹具**不依赖任何世界几何**（不量方块、不站位）—— 这是有意的：
 * 它是"判定表 + 配置"的夹具，几何相关的端到端证据由后续场景夹具负责。
 *
 * <p>输出：`[RegionSweep] CHECK 判据=? 失败=? … → PASS|FAIL`。
 */
public final class RegionSweepCheckTask implements Task {

    private final BotPlayer bot;
    /** 只跑一次（幂等）：断言跑完后不再重复执行（技能「相位状态机」：处理完必须前进）。 */
    private boolean ran;
    private Task.Status terminal = Task.Status.DONE;
    private String failure = "";
    private int checks;
    private final List<String> failed = new ArrayList<>();

    public RegionSweepCheckTask(BotPlayer bot) {
        this.bot = bot;
    }

    @Override
    public String taskName() {
        return "RegionSweepCheck";
    }

    @Override
    public TaskTarget target() {
        return TaskTarget.block(bot.blockPosition());
    }

    @Override
    public String failureReason() {
        return failure;
    }

    @Override
    public Task.Status tick() {
        if (!ran) {
            ran = true;
            runChecks();
        }
        return terminal;
    }

    private void runChecks() {
        LumberRegionState state = LumberRegionState.get(bot.getServer());
        UUID owner = bot.getUUID();
        // 快照（还原用）：本夹具借用户区域状态做断言 ⇒ 结束后必须原样还回去
        String savedSapling = state.saplingItem(owner);
        List<String> savedExplicit = new ArrayList<>(state.pickupItems(owner));
        try {
            checkDecisionTable();
            checkListDerivation(state, owner);
            checkNbtRoundTrip(state, owner);
        } finally {
            for (String item : state.pickupItems(owner)) {
                state.removePickupItem(owner, item);
            }
            for (String item : savedExplicit) {
                state.addPickupItem(owner, item);
            }
            state.setSaplingItem(owner, savedSapling);
        }

        boolean pass = failed.isEmpty();
        BotLog.info("[RegionSweep] CHECK 判据={} 失败={} {} → {}",
                checks, failed.size(), pass ? "-" : String.join(" | ", failed), pass ? "PASS" : "FAIL");
        if (!pass) {
            failure = "REGION_SWEEP_CHECK_FAILED " + String.join(" | ", failed);
            terminal = Task.Status.FAILED;
        }
    }

    private void check(String name, boolean ok) {
        checks++;
        if (!ok) {
            failed.add(name);
        }
    }

    /**
     * **判定表**（`D-344` ③ 的四态 + 优先级的**行为**证据）。
     *
     * <p>⚠️ 与内核规则 `rule_replant_sweep_bounded` ① 互补而不重复：内核断言**结构顺序**
     * （"四态的先后"），本夹具断言**行为**（"换序后会喂出不同答案"）——
     * 前者防"悄悄改顺序"，后者防"改了条件表达式"。
     */
    private void checkDecisionTable() {
        var none = RegionLumberJob.SweepDecision.NO_DEFICIT;
        var has = RegionLumberJob.SweepDecision.HAS_SAPLINGS;
        var nothing = RegionLumberJob.SweepDecision.NOTHING_TO_SWEEP;
        var enter = RegionLumberJob.SweepDecision.ENTER;

        check("不欠树 ⇒ 不扫（即使地上有 9 件）",
                RegionLumberJob.sweepDecision(0, 0, 9) == none);
        check("负欠树（不该发生）⇒ 同样不扫",
                RegionLumberJob.sweepDecision(-2, 0, 9) == none);
        check("有苗 ⇒ 不扫（细则⑥「身上有苗砍完立刻补」，即使地上有 9 件）",
                RegionLumberJob.sweepDecision(3, 8, 9) == has);
        check("**欠树 + 没苗 + 地上没有 ⇒ 不进扫描**（绝不空转；走既有 tool_missing 如实失败）",
                RegionLumberJob.sweepDecision(3, 0, 0) == nothing);
        check("欠树 + 没苗 + 地上有 ⇒ 进扫描",
                RegionLumberJob.sweepDecision(3, 0, 2) == enter);
        // 优先级（顺序即优先级）：**必须用"两种顺序会给出不同答案"的输入**才具判别力。
        // ⚠️ 这里踩过一次真坑（2026-09-19，反向对照实测）：我最初挑的 `(0,0,0)` 与 `(3,1,5)`
        // 在"正确顺序"和"换序后"**答案完全相同** ⇒ 把生产代码的两条判定换序，本夹具**照样 PASS**
        // （假绿）。补上这两条**两解不同**的用例后，换序才会真的红。
        check("优先级：不欠树 **压过**「手里有苗」（换序后会变成 HAS_SAPLINGS ⇒ 红）",
                RegionLumberJob.sweepDecision(0, 5, 9) == none);
        check("优先级：手里有苗 **压过**「地上没东西」（换序后会变成 NOTHING_TO_SWEEP ⇒ 红）",
                RegionLumberJob.sweepDecision(3, 4, 0) == has);
        check("优先级：不欠树 **压过**「地上有东西」（换序后会变成 NOTHING_TO_SWEEP ⇒ 红）",
                RegionLumberJob.sweepDecision(0, 0, 5) == none);
    }

    /**
     * **清单的"派生默认值"**（`D-344` ④ / 细则⑦「不许硬编码树苗」）。
     *
     * <p>核心断言：默认项是**算出来的**（显式项 ∪ 选定树苗）⇒ 换树苗时它**自动跟随**，
     * 代码里没有任何一处写死 `minecraft:oak_sapling`。
     */
    private void checkListDerivation(LumberRegionState state, UUID owner) {
        for (String item : state.pickupItems(owner)) {
            state.removePickupItem(owner, item);
        }
        state.setSaplingItem(owner, null);
        check("树苗未选定、也没显式加过 ⇒ 生效清单**为空**（不猜用户要捡什么）",
                state.effectivePickupItems(owner).isEmpty());

        state.setSaplingItem(owner, "minecraft:oak_sapling");
        check("选定橡树苗 ⇒ 生效清单 = 它本身（**派生**；没有任何代码显式加过它）",
                state.effectivePickupItems(owner).equals(List.of("minecraft:oak_sapling")));
        check("派生项被标为「默认」而不是「手动加」",
                state.pickupItemIsDefault(owner, "minecraft:oak_sapling"));

        state.setSaplingItem(owner, "minecraft:spruce_sapling");
        check("换树苗 ⇒ 生效清单**跟着换**、无 oak 残留（细则⑦：不许硬编码树苗）",
                state.effectivePickupItems(owner).equals(List.of("minecraft:spruce_sapling")));

        state.addPickupItem(owner, "minecraft:stick");
        check("显式加树枝 ⇒ 生效清单 = 派生项 ∪ 显式项",
                new HashSet<>(state.effectivePickupItems(owner))
                        .equals(Set.of("minecraft:spruce_sapling", "minecraft:stick")));
        check("显式项**不**被标为「默认」（`pickup list` 要能区分来源）",
                !state.pickupItemIsDefault(owner, "minecraft:stick"));

        state.removePickupItem(owner, "minecraft:stick");
        check("移除显式项 ⇒ 回到只派生",
                state.effectivePickupItems(owner).equals(List.of("minecraft:spruce_sapling")));
    }

    /**
     * **NBT 往返**（`D-344` 夹具 #5）：显式项要留存；**派生项不落盘**（否则换树苗后会残留旧默认项）。
     */
    private void checkNbtRoundTrip(LumberRegionState state, UUID owner) {
        state.setSaplingItem(owner, "minecraft:spruce_sapling");
        state.addPickupItem(owner, "minecraft:stick");
        CompoundTag tag = state.save(new CompoundTag());
        LumberRegionState reloaded = LumberRegionState.load(tag);

        check("NBT 往返：**显式项留存**（重启后清单还在）",
                reloaded.pickupItems(owner).contains("minecraft:stick"));
        check("NBT 往返：**派生项不落盘**（落盘了就会在换树苗后变成幽灵残留）",
                !reloaded.pickupItems(owner).contains("minecraft:spruce_sapling"));
        check("NBT 往返：树苗选择留存",
                "minecraft:spruce_sapling".equals(reloaded.saplingItem(owner)));
        check("NBT 往返后**生效清单**仍等于 派生项 ∪ 显式项",
                new HashSet<>(reloaded.effectivePickupItems(owner))
                        .equals(Set.of("minecraft:spruce_sapling", "minecraft:stick")));
    }
}
