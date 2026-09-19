package com.dddgn.alice.task;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.item.FixtureToolKit;
import com.dddgn.alice.job.GoalSpec;
import com.dddgn.alice.job.mine.MineCandidateSource;
import com.dddgn.alice.job.mine.MineJob;
import com.dddgn.alice.job.policy.NearestPolicy;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.perception.ScopeBuffer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.List;

/**
 * **挖矿容量守卫**夹具（`D-335`；对際伐木的 `LumberFailureCheckTask`）——电池步 `mine_inventory`（EXTRA）。
 *
 * <p><b>为什么要有它（2026-09-19 核查的**更正**结论）</b>：`MineJob` 的容量检查位于 `tick()` 里、
 * **相位切换之前、不受"只跑一次"约束** ⇒ ⭐ **它每 tick 都在跑**，因此它**同时就是"作业中守卫"**
 *（`MineJob:235-239`）。⇒ 勘测报告 §1.5 说的"挖到一半背包满却继续挖世界"**不成立**；
 * 我一度按报告给 `MineJob` 补了一条"作业中守卫"，**先红后绿实测证明它多余**（禁用后行为不变）⇒ 已撤掉。
 * <p>⇒ 本夹具的价值因此变成：**矿侧此前完全没有 `inventory_full` 覆盖**（伐木侧有），这里把既有行为**钉住**。
 *
 * <p><b>两个用例（判据都钉在"世界有没有被白改"上，不只看状态码）</b>：
 * <ul>
 *   <li>{@code PREFLIGHT}：开工前背包已满 ⇒ `DONE inventory_full` 且 ⭐ **矿石数一格不少**（未动世界）；</li>
 *   <li>{@code MIDJOB}：**只留一个空位**、配额 3 ⇒ 挖掉第 1 格后产物占满空位 ⇒ 守卫必须在
 *       **下一格之前**停下 ⇒ `DONE inventory_full` 且 ⭐ **矿石数恰好少 1**（不是少 3、也不是挖到超时）。</li>
 * </ul>
 *
 * <p><b>先红后绿</b>：临时去掉 `MineJob` 里那条作业中守卫 ⇒ `MIDJOB` 必红（会少 3 格或走别的终态）。
 *
 * <p><b>状态语义</b>：保持既有约定 `DONE` + `reason=inventory_full`（用户 2026-09-19 裁定 A；
 * 与伐木一致，不改状态码）。
 */
public final class MineInventoryCheckTask implements Task {

    /** 单个用例的 tick 上限（配额 3 足够）。 */
    private static final int CASE_TICKS = 300;   // 绿灯实测整步 29 tick ⇒ 300 足够；坏掉时给出**可读断言**而不是步骤超时

    /** 统计"场景里还剩几格铁矿"用的扫描半径（与 `mine_job` 同口径）。 */
    private static final int ORE_RADIUS = MineCandidateSource.SCAN_RADIUS;

    /** 用例内"是否已注入满包"用的**小半径**计数（每 tick 数一次也不能太贵）。 */
    private static final int ORE_RADIUS_TIGHT = 8;

    private enum Case { PREFLIGHT, MIDJOB }

    private final BotPlayer bot;
    private final ScopeBuffer scope;
    private final List<String> failures = new ArrayList<>();
    private final List<String> results = new ArrayList<>();

    private int index;
    private Case current;
    private int caseTicks;
    private int checks;
    private boolean done;
    private List<ItemStack> savedInventory;
    private MineJob job;
    private int oreBefore;
    private int oreBeforeTight;
    private boolean injected;

    public MineInventoryCheckTask(BotPlayer bot, ScopeBuffer scope) {
        this.bot = bot;
        this.scope = scope;
    }

    @Override
    public String taskName() {
        return "MineInventoryCheck";
    }

    @Override
    public TaskTarget target() {
        return TaskTarget.block(OreCourseAnchor.START_FOOT);
    }

    @Override
    public String failureReason() {
        return String.join(" | ", failures);
    }

    @Override
    public String terminalReason() {
        return done ? (failures.isEmpty() ? "passed" : "failed") : "";
    }

    @Override
    public Task.Status tick() {
        if (done) {
            return failures.isEmpty() ? Task.Status.DONE : Task.Status.FAILED;
        }
        if (current == null) {
            if (index >= Case.values().length) {
                return finish();
            }
            current = Case.values()[index];
            prepare();
            return Task.Status.RUNNING;
        }
        if (++caseTicks > CASE_TICKS) {
            record(current, false, "case_timeout ticks=" + caseTicks);
            endCase();
            return Task.Status.RUNNING;
        }
        // ⭐ **确定性注入**（用例 2 的关键）：矿石课程里 bot 是**隔 2~3 格远距离挖**，
        // 掉落物**不会**被自动拾取（要等 COLLECT 阶段）⇒ 空位永远填不上，作业中守卫就**永远测不到**。
        // 真实通道挖掘里 bot 是**穿过**自己挖开的格子（才会自动拾取）⇒ 这里由夹具替它把"最后一个空位"填上，
        // 从而把判据钉在守卫的语义上：**发现没空位 ⇒ 下一格之前停下**。
        if (current == Case.MIDJOB && !injected && hasEmptyAfterInject()
                && !scope.liveDrops().isEmpty()) {
            // 触发条件用**掉落实体出现**（挖掘的确定产物），而不是"矿石数下降"：
            // 第一版用小半径计数当触发，而小半径里未必包含被挖掉的那几格 ⇒ 触发不了 ⇒ 用例超时。
            injectFullInventory();
        }
        Task.Status status = job.tick();
        if (status == Task.Status.RUNNING) {
            return Task.Status.RUNNING;
        }
        assertCase(status);
        endCase();
        return Task.Status.RUNNING;
    }

    // ==================== 用例 ====================

    private void prepare() {
        recoverInventory();
        ServerLevel level = bot.serverLevel();
        FixtureToolKit.ensurePickaxe(bot);
        caseTicks = 0;
        oreBefore = countOre(level, OreCourseAnchor.START_FOOT);
        oreBeforeTight = countOre(level, OreCourseAnchor.START_FOOT, ORE_RADIUS_TIGHT);
        injected = false;
        if (current == Case.PREFLIGHT) {
            fillInventory(0);      // 一个空位都不留
        } else {
            fillInventory(1);      // 只留一个空位：第 1 格产物就能占满它
        }
        GoalSpec spec = GoalSpec.mineBlocks(OreCourseAnchor.START_FOOT,
                MineCandidateSource.SCAN_RADIUS, MINE_QUOTA, 3600);
        job = new MineJob(bot, spec, scope,
                new MineCandidateSource(MineCandidateSource.Target.ofBlock(Blocks.IRON_ORE),
                        MineCandidateSource.SCAN_RADIUS),
                new NearestPolicy());
        BotLog.info("[MineInv] case={} start 场景矿石={}（配额={} 空位={}）",
                current, oreBefore, MINE_QUOTA, current == Case.PREFLIGHT ? 0 : 1);
    }

    private void assertCase(Task.Status status) {
        String terminal = job.terminalReason();
        int oreAfter = countOre(bot.serverLevel(), OreCourseAnchor.START_FOOT);
        int removed = oreBefore - oreAfter;
        if (current == Case.PREFLIGHT) {
            check("① 开工前背包已满 ⇒ 必须 DONE + inventory_full（实际 status=" + status + " reason=" + terminal + "）",
                    status == Task.Status.DONE && "inventory_full".equals(terminal));
            check("① 前置满包**不许动世界**（矿石 前=" + oreBefore + " 后=" + oreAfter + "）", removed == 0);
        } else {
            check("② 作业中满包 ⇒ 必须 DONE + inventory_full（实际 status=" + status + " reason=" + terminal + "）",
                    status == Task.Status.DONE && "inventory_full".equals(terminal));
            check("② ⭐ 作业中守卫必须在**下一格之前**停下 ⇒ 矿石恰好少 1 格（前=" + oreBefore
                            + " 后=" + oreAfter + " 实际少=" + removed + "；去掉守卫会少 " + MINE_QUOTA + " 格）",
                    removed == 1);
        }
        record(current, failures.isEmpty(), "status=" + status + " reason=" + terminal
                + " oreRemoved=" + removed);
    }

    private void endCase() {
        job = null;
        current = null;
        index++;
        recoverInventory();
    }

    private Task.Status finish() {
        done = true;
        boolean pass = failures.isEmpty();
        check("两个用例都要跑到（结果数=" + results.size() + "/2）", results.size() == 2);
        BotLog.info("[MineInv] SUMMARY checks={} failures={} → {}｜{}",
                checks, failures.size(), pass ? "PASS" : "FAIL", results);
        if (bot != null && bot.getServer() != null && bot.level() instanceof ServerLevel) {
            for (ServerPlayer p : bot.getServer().getPlayerList().getPlayers()) {
                if (p != bot) {
                    p.sendSystemMessage(Component.literal("[alice] 挖矿容量守卫 "
                            + (pass ? "PASS" : "FAIL " + failures)));
                }
            }
        }
        return pass ? Task.Status.DONE : Task.Status.FAILED;
    }

    // ==================== 工具 ====================

    private static final int MINE_QUOTA = 3;

    /** 把背包塞满圆石（**保留镐子槽**，否则会先撞"缺工具"而不是"背包满"）。 */
    private void fillInventory(int emptySlots) {
        var inventory = bot.getInventory();
        savedInventory = new ArrayList<>(inventory.getContainerSize());
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            savedInventory.add(inventory.getItem(slot).copy());
        }
        // ⚠️ 保护槽也要算进去：第一版按 `size - emptySlots` 填，把**镐子占的那一格**忽略掉了
        // ⇒ 实际填满 36 格 = 一个空位都不剩 ⇒ 两个用例都撞**前置**守卫（`MIDJOB` 因此量不到作业中守卫）。
        int protectedSlots = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (inventory.getItem(slot).is(net.minecraft.tags.ItemTags.PICKAXES)) {
                protectedSlots++;
            }
        }
        int toFill = Math.max(0, inventory.getContainerSize() - emptySlots - protectedSlots);
        int filled = 0;
        for (int slot = 0; slot < inventory.getContainerSize() && filled < toFill; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.is(net.minecraft.tags.ItemTags.PICKAXES)) {
                continue;   // 镐子必须留着：本夹具验的是容量，不是工具归因
            }
            inventory.setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
            filled++;
        }
    }

    private void recoverInventory() {
        if (savedInventory == null) {
            return;
        }
        var inventory = bot.getInventory();
        for (int slot = 0; slot < savedInventory.size() && slot < inventory.getContainerSize(); slot++) {
            inventory.setItem(slot, savedInventory.get(slot));
        }
        savedInventory = null;
    }

    /** 注入是否已生效（注入是"把最后一个空位填满"）。 */
    private boolean hasEmptyAfterInject() {
        return !injected;
    }

    /** 把最后一个空位也填上圆石（模拟"自动拾取把背包塞满"）。 */
    private void injectFullInventory() {
        var inventory = bot.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (inventory.getItem(slot).isEmpty()) {
                inventory.setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
            }
        }
        injected = true;
        BotLog.info("[MineInv] 注入：已把最后一个空位填满（模拟自动拾取塞满背包）");
    }

    private static int countOre(ServerLevel level, BlockPos center) {
        return countOre(level, center, ORE_RADIUS);
    }

    /** 场景里还剩几格铁矿（"世界有没有被白改"的判据；只读已加载区，未加载不读方块）。 */
    private static int countOre(ServerLevel level, BlockPos center, int radius) {
        int found = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    if (!level.hasChunkAt(pos)) {
                        continue;
                    }
                    if (level.getBlockState(pos).is(Blocks.IRON_ORE)) {
                        found++;
                    }
                }
            }
        }
        return found;
    }

    private void record(Case which, boolean ok, String detail) {
        results.add(which + "=" + (ok ? "OK" : "BAD") + "(" + detail + ")");
        // ⚠️ 第一版忘了这句 ⇒ 用例 BAD 但 `failures` 为空 ⇒ **整个步假 PASS**（D-323 同类"谎报"）。
        if (!ok) {
            failures.add(which + "：" + detail);
        }
    }

    private void check(String what, boolean ok) {
        checks++;
        if (!ok) {
            failures.add(what);
        }
    }
}
