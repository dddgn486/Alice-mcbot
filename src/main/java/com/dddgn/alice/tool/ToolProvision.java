package com.dddgn.alice.tool;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.bot.ToolSupply;
import com.dddgn.alice.job.JobRequest;
import com.dddgn.alice.log.BotLog;
import net.minecraft.world.item.ItemStack;

import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * **发料策略 —— 具名载体**（`D-512`，2026-09-28 用户逐字：⛔「**入口既不是夹具口也不是生产口**」；
 * ✅「**入口不分类，`fixtureProvision` 由调用方传**」）。
 *
 * <p><b>为什么需要它（取代裸 `boolean fixtureProvision`）</b>：
 * <ul>
 *   <li>裸布尔只能表达"要不要造物"，**表达不了"不造物时干什么"**（= 搬运已有的）⇒ 语义全靠调用方脑补；</li>
 *   <li>裸布尔必须由**被调方**解释 ⇒ `bot/` 里就得存在"夹具分支"的代码 ⇒ **`bot/` 反向依赖 `fixture/`**
 *       （刀 2 实测：40 处 `fixture/` import ＋ 6 处 `debug/` import ＋ 47 个构造点）；
 *   <li>⭐ 具名策略把**决策权交回调用方**：`job/` 与 `bot/` 只认识这个接口，
 *       ⛔ 不认识 `fixture/`，也⛔ 不认识 {@link com.dddgn.alice.item.FixtureToolKit}。</li>
 * </ul>
 *
 * <p><b>两个实现（缺一不可）</b>：
 * <ul>
 *   <li>✅ {@link #PROMOTE_ONLY}（**本包**，生产）：**只搬运已存在的**工具（{@link ToolSupply#promoteFromMain}），
 *       缺就如实留缺 ⇒ 由 Job 自己报 `tool_missing`（诚实失败优于凭空成功）。</li>
 *   <li>⚠️ `com.dddgn.alice.fixture.DevCreateProvision.INSTANCE`（**开发期包**）：**凭空造**。
 *       ⛔ 它的实现**必须**住开发期包（{@link com.dddgn.alice.item.FixtureToolKit} 是开发期物）—— 这正是"策略由调用方传"的
 *       结构性理由：生产包**没法**表达"凭空造物"。</li>
 * </ul>
 *
 * <p>⚠️ **本接口今天不做分类判断**：谁传哪个策略是**调用方**的事（`D-512` 字面）。
 * 今天的调用方实况（刀 2 实测）：`decision/GoalDirector` 传 `PROMOTE_ONLY`；
 * `item/`（`/give` 物品 = 开发期入口，`D-554`）与 `debug/`（`D-560`：现有 `/alice` 命令全是开发期入口）
 * 传 `DEV_CREATE` ⇒ **行为与刀 2 之前逐字相同**（`I1`）。
 *
 * <p>⚠️ **回收条件**（`D-560` 第 4 条）：一旦 `debug/` 里出现**够格给发行包当严格调试工具**的指令，
 * 那些指令**不许**再默认 `DEV_CREATE` —— 否则"调试结果被发料行为污染"。判据的**可执行形式** =
 * 发料事实必须进终态记录（`bot/TaskExecutionRecord` 的 `provision` 维，刀 4）。
 */
public interface ToolProvision {

    /** ✅ **生产**：只搬运已存在的工具（`ToolSupply.promoteFromMain`），⛔ 不创造、⛔ 不覆盖。 */
    ToolProvision PROMOTE_ONLY = new PromoteOnly();

    /** 人读名（进日志与终态记录；`PROMOTE_ONLY` / `DEV_CREATE`）。 */
    String label();

    /**
     * 按作业 kind 发料（`COLLECT`／`CRAFT` 是 no-op：捡拾不需要工具，合成只真消耗真产物）。
     *
     * @return `false` = 发料失败 ⇒ 调用方**不许**当成功（如实不起 Job）
     */
    boolean provisionFor(BotPlayer bot, JobRequest request);

    // ---- 下面四个是 **legacy 入口**（不走 `JobRequest` 的那几条）用的原语 ----
    // ⚠️ 语义 = 「按**本次入口允许的发料强度**满足这个前置」：PROMOTE_ONLY 只搬运，DEV_CREATE 造物。

    /** 保证快捷栏里有镐（拆我方临时方块 / 挖必须正确工具才掉落的方块）。 */
    void pickaxe(BotPlayer bot);

    /** 保证快捷栏里有斧（伐木；用镐砍原木慢 8 倍，`D-089`）。 */
    void axe(BotPlayer bot);

    /** 保证快捷栏里有 {@code count} 个圆石（垫脚/搭柱的一次性方块）。 */
    void cobblestone(BotPlayer bot, int count);

    /** 保证快捷栏里有 {@code count} 个匹配 {@code match} 的物品（如区域型补种用的选定树苗）。 */
    void hotbarStack(BotPlayer bot, Supplier<ItemStack> sample, Predicate<ItemStack> match,
                     int count, String what);

    /**
     * ✅ **生产策略**：只搬运、不创造（`T1` / `R-2` 红线②）。
     *
     * <p>本项目真实事故（三路审计 §3.1 R-2）：`BotManager.assignJob` 是决策层唯一的生产入口，
     * 而它一路调到 `FixtureToolKit`，把钻石镐/钻石斧/12 圆石**凭空塞进快捷栏**（快捷栏满时还会
     * **强制覆盖**已有物品）⇒ **LLM 起的每个 Job 都白得一套钻石工具**。
     *
     * <p>这条路径下"发料"= 把**已有**工具从主背包挪进快捷栏（背包内移动，不写世界、不耗资源）；
     * 缺什么就**如实留缺**，由 Job 报 `tool_missing`（`LumberJob`/`MineJob` 已有该上抛路径）。
     * 这样"LLM 起的 Job"与"真人用手玩"是同一条物质约束。
     */
    final class PromoteOnly implements ToolProvision {

        private PromoteOnly() {
        }

        @Override
        public String label() {
            return "PROMOTE_ONLY";
        }

        @Override
        public boolean provisionFor(BotPlayer bot, JobRequest request) {
            switch (request.kind()) {
                case LUMBER, REGION_LUMBER -> promote(bot, request, ToolSupply.Kind.AXE, ToolSupply.Kind.PICKAXE);
                case MINE -> promote(bot, request, ToolSupply.Kind.PICKAXE);
                case COLLECT, CRAFT -> {
                    // 与开发期分支同一口径：不发料
                }
            }
            return true;
        }

        @Override
        public void pickaxe(BotPlayer bot) {
            ToolSupply.promoteFromMain(bot, ToolSupply.Kind.PICKAXE);
        }

        @Override
        public void axe(BotPlayer bot) {
            ToolSupply.promoteFromMain(bot, ToolSupply.Kind.AXE);
        }

        @Override
        public void cobblestone(BotPlayer bot, int count) {
            // ⛔ 生产不发一次性方块（凭空造物的同族铁律）；缺料由任务如实失败。
        }

        @Override
        public void hotbarStack(BotPlayer bot, Supplier<ItemStack> sample, Predicate<ItemStack> match,
                                int count, String what) {
            // ⛔ 生产不发任意物品。⚠️ 与旧口径一致：区域型的"选定树苗"在生产路径下**不搬运**
            // （`ensureHotbarStack` 的非创造等价物不存在 ⇒ 旧代码也只查 `countInInventory` 后如实记录）。
        }

        private static void promote(BotPlayer bot, JobRequest request, ToolSupply.Kind... kinds) {
            StringBuilder line = new StringBuilder();
            for (ToolSupply.Kind kind : kinds) {
                String result = ToolSupply.promoteFromMain(bot, kind);
                if (!line.isEmpty()) {
                    line.append(' ');
                }
                line.append(kind.label()).append('=').append(result);
            }
            BotLog.info("[Job] 生产入口只搬运不发料（{}）：{} ⇒ 缺工具时由 Job 自己如实报 tool_missing",
                    request.kind(), line);
        }
    }
}
