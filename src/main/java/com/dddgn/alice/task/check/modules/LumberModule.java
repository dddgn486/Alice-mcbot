package com.dddgn.alice.task.check.modules;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.item.FixtureToolKit;
import com.dddgn.alice.job.GoalSpec;
import com.dddgn.alice.job.lumber.LumberCandidateSource;
import com.dddgn.alice.job.lumber.LumberJob;
import com.dddgn.alice.job.lumber.LumberRegionState;
import com.dddgn.alice.job.lumber.RegionLumberJob;
import com.dddgn.alice.job.policy.NearestPolicy;
import com.dddgn.alice.task.FixtureThirdParty;
import com.dddgn.alice.task.LumberCourseAnchor;
import com.dddgn.alice.task.LumberFailureCheckTask;
import com.dddgn.alice.task.PremiseGateTask;
import com.dddgn.alice.task.RegionSweepCheckTask;
import com.dddgn.alice.task.RegionSweepE2ECheckTask;
import com.dddgn.alice.task.RegionMaintainUnmaintainableCheckTask;
import com.dddgn.alice.task.Task;
import com.dddgn.alice.task.check.CheckContext;
import com.dddgn.alice.task.check.CheckModule;
import com.dddgn.alice.task.check.CheckProfile;
import com.dddgn.alice.task.check.CheckStep;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * **伐木模块（R-2 第六片，6 步）**：`lumber_failure` · `lumber_job` · `region_maintain`
 * · `region_sweep` · `region_sweep_e2e` · ⭐ `region_maintain_unmaintainable`（`D-349`）。
 *
 * <p>三态覆盖"伐木"这条链的**失败归因 / 生产闭环 / 可持续巡查**：
 * <ul>
 *   <li>`lumber_failure`：把**五类失败**（没候选 / 背包满 / 目标超时 / 原木被换掉 / 缺工具）逐个造出来，
 *       断言归因码**逐条正确**；</li>
 *   <li>`lumber_job`：生产入口真砍（`LumberJob` + 配额，跑完看终态）；</li>
 *   <li>`region_maintain`：**常驻**区域作业（巡查 → 砍 → 补种 → 继续巡查）——
 *       判据是 `doneWhen`（砍到 ≥1 棵且补种 ≥1 棵 ⇒ 按**达成**判过，之后它继续巡查是**正常**的 ✓）。</li>
 * </ul>
 *
 * <p>**为什么 `region_maintain` 是 `doneWhen` 的又一个真实消费者**：区域型 Job 的设计就是"一直干下去"，
 * 若按"跑完看终态"判，只能等预算耗尽 ⇒ 把"本来就该常驻"误报成失败 ✗（D-300/301 已把编排器的
 * `doneWhen` 语义补齐，本模块正是它的验收对象之一 ✓）。
 *
 * <p>**本模块自带前提**：三步都先传送到课程起点（`lumber_failure` 的 `provision` 只做这一件事
 * —— 它**自带地形函数**，但顺序是"函数 → 传送"，所以必须由模块**先把区块热起来**，否则 `/fill`
 * 会落在冷区块上 ✗，D-296 记过这个坑 ✓）。
 *
 * <p>⭐ **另一条前提：第三方保护**（`D-409`/`D-410`）。无头电池的世界母本是**玩家真实存档的副本**
 * ⇒ 夹具会**继承玩家的 FTB Chunks 认领**，而伐木课程的坐标恰恰就在玩家基地里 ⇒ 认领内的
 * 破坏/放置会被 FTB **静默取消**，夹具却报成内核失败码（`lumber_job` 连红 35 轮的真因）。
 * ⇒ 四个用到该课程的步（`lumber_failure` / `lumber_job` / `region_maintain` / `region_sweep_e2e`）
 * 一律挂 {@code FixtureThirdParty} 前提，不成立就**记 `SKIP`（结论不作数）**，绝不假装绿。
 */
public final class LumberModule implements CheckModule {

    @Override
    public String id() {
        return "lumber";
    }

    @Override
    public String title() {
        return "伐木（失败归因五连 / 生产闭环 / 区域常驻 + 补种）";
    }

    @Override
    public List<CheckStep> steps(CheckContext ctx) {
        BotPlayer bot = ctx.bot();
        var observer = ctx.observer();
        var scope = ctx.scope();
        List<String> course = List.of("alice_test:lumber_course_terrain",
                "alice_test:lumber_course_trees");
        Runnable tools = () -> {               // 复刻 `LumberJobItem` 的发料（斧 + 镐 + 垫脚圆石）
            to(bot, LumberCourseAnchor.START_FOOT);
            FixtureToolKit.resetInventory(bot);
            FixtureToolKit.ensureAxe(bot);
            FixtureToolKit.ensurePickaxe(bot);
            FixtureToolKit.ensureHotbarStack(bot, () -> new ItemStack(Items.COBBLESTONE),
                    stack -> stack.is(Items.COBBLESTONE), 12, "cobblestone");
        };
        return List.of(
                // 失败归因五连（自带地形函数 ⇒ 模块只负责"先把区块热起来"）
                CheckStep.skippable("lumber_failure", CheckProfile.EXTRA, List.of(),
                        () -> to(bot, LumberCourseAnchor.START_FOOT),
                        guarded(bot, "lumber_failure", () -> new LumberFailureCheckTask(bot, scope)),
                        1800, LumberModule::premiseFailed),
                // 伐木 Job：手动场景（terrain + 手写树）⇒ 电池自己跑场景函数 + 复刻 LumberJobItem 的发料
                CheckStep.skippable("lumber_job", CheckProfile.BASELINE, course, tools,
                        guarded(bot, "lumber_job", () -> new LumberJob(bot,
                                GoalSpec.harvestUnits(LumberCourseAnchor.START_FOOT, 16, 4, 3600),
                                scope, new LumberCandidateSource(), new NearestPolicy())),
                        1500, LumberModule::premiseFailed),
                // J8 可持续伐木区（MAINTAIN）：同一个伐木场景，但走"巡查 → 砍 → 继续巡查"的区域型 Job
                CheckStep.skippable("region_maintain", CheckProfile.EXTRA, course, () -> {
                    tools.run();
                    // Slice B：区域欠树要补种 ⇒ 夹具发**选定的那种**树苗（未选则默认橡树苗）
                    var state = LumberRegionState.get(bot.getServer());
                    if (state.saplingItem(bot.getUUID()) == null) {
                        state.setSaplingItem(bot.getUUID(), "minecraft:oak_sapling");
                    }
                    var saplingId = ResourceLocation.tryParse(state.saplingItem(bot.getUUID()));
                    var sapling = saplingId == null ? null : BuiltInRegistries.ITEM.get(saplingId);
                    if (sapling != null && sapling != Items.AIR) {
                        FixtureToolKit.ensureHotbarStack(bot, () -> new ItemStack(sapling),
                                stack -> stack.is(sapling), 8, "sapling");
                    }
                }, guarded(bot, "region_maintain", () -> new RegionLumberJob(bot,
                        LumberCourseAnchor.region(),
                        scope, new LumberCandidateSource(), new NearestPolicy(), 20, 8000)),
                        2000, LumberModule::premiseFailed)
                        // 常驻任务：砍到 ≥1 棵且补种 ≥1 棵即算本步通过（之后它会继续巡查等苗长大）
                        // ⚠️ 判据拿到的是**前提闸门**（`D-409`）⇒ 必须 `unwrap` 到内层再看类型。
                        .withDoneWhen(task -> unwrap(task) instanceof RegionLumberJob region
                                && region.treesChopped() >= 1 && region.plantedSomething()),
                // `D-344` 片 A/B：区域**"扫地面"判定 + 可配置拾取清单**的自检。
                // 判据是**纯函数**（`sweepDecision` 只吃三个整数）⇒ 夹具**不写世界、不派真任务**
                // （技能 `alice-scene-based-testing` 陷阱#6 的硬要求）；因此**不需要场景、也不需要 provision**。
                // ⚠️ 本步**不覆盖**端到端（"真去把地面的苗捡回来"）—— 那要"零树苗 + 地面有苗"的场景，
                // 属下一步（见 `docs/REGION_REPLANT_ASYNC_DESIGN.md` §7）。
                CheckStep.of("region_sweep", CheckProfile.EXTRA, List.of(), null,
                        () -> new RegionSweepCheckTask(bot), 200),
                // `D-344` 片 A 的**端到端**一环：真跑一个 `RegionLumberJob`，看它"扫地面 → 捡苗 → 补种"。
                // 场景与状态由夹具自己在 SETUP 里造（含地形函数、传送、清背包），结束**还原**
                // ⇒ provision 传 `null`（技能：夹具自己负责传送与复位）。
                // ⚠️ 本步**也在伐木课程的第三方认领盒内**（它用 `LumberCourseAnchor.region()`，与
                //    `region_maintain` 同一个区域）：**建场景**的命令源不受第三方保护约束，但**bot 自己去
                //    补种**会 —— 2026-09-23 实测 `[WRITE-REFUSED] plant pos=23, 64, 211 by=region_lumber…`
                //    ×113 ⇒ `saplingsPlanted` 不增 ⇒ **假红** `REGION_SWEEP_E2E_FAILED`。
                //    ⇒ 同样挂第三方前提（`D-410`）。⭐ 教训：只看**数据包场景函数**的坐标会漏掉
                //    **在代码里自建场景**的夹具（本步就是被漏掉的那个）。
                CheckStep.skippable("region_sweep_e2e", CheckProfile.EXTRA, List.of(), null,
                        guarded(bot, "region_sweep_e2e", () -> new RegionSweepE2ECheckTask(bot, scope)),
                        3000, LumberModule::premiseFailed),
                // ⭐ `D-349`（勘测侧 Pit 2）：**`MAINTAIN` 的"不可维持"判据** —— 常驻区域作业
                // 不许"看起来在跑、其实终态已不可达"。判据：① 触发（事实被登记 + 上报"可做什么"）；
                // ② **不越权**（登记时仍 RUNNING，收工只由玩家/决策层打断）；③ **恢复**（注入欠树+苗 ⇒ 清除）。
                // 自建空盒（草方块地板、无树无苗）+ 自己 tick 真 `RegionLumberJob` + 收尾还原共享区域状态。
                CheckStep.of("region_maintain_unmaintainable", CheckProfile.EXTRA, List.of(), null,
                        () -> new RegionMaintainUnmaintainableCheckTask(bot, observer, scope), 1200));
    }

    // ==================== 世界前提（`D-409`） ====================

    /**
     * **前提盒**：伐木课程的实际范围 —— **与 `LumberCourseAnchor` 的区域常量同源**（不另写一套，
     * 免得将来场景搬了而前提盒没跟着搬）。
     */
    private static final BlockPos PREMISE_MIN = new BlockPos(
            LumberCourseAnchor.REGION_MIN_X, LumberCourseAnchor.REGION_BASE_Y, LumberCourseAnchor.REGION_MIN_Z);
    private static final BlockPos PREMISE_MAX = new BlockPos(
            LumberCourseAnchor.REGION_MAX_X,
            LumberCourseAnchor.REGION_BASE_Y + LumberCourseAnchor.REGION_MAX_HEIGHT,
            LumberCourseAnchor.REGION_MAX_Z);

    /**
     * ⭐ **世界前提：这段范围不能落在「别人的保护」里**（`D-409`）。
     *
     * <p>为什么必须有这条：无头电池的世界母本是**玩家真实存档的副本** ⇒ 夹具会**继承玩家的 FTB Chunks
     * 认领**；而 `lumber_course_*` 恰恰是从真实存档抓下来的（坐标就在玩家基地里）。2026-09-23 实测：
     * 认领内的破坏会被 FTB **静默取消**（`gameMode.destroyBlock` 返回 false），夹具拿到"树砍不动"的世界，
     * 却报成内核失败码 `no_reachable_candidate` ⇒ **这一步连红 35 轮，所有人都以为内核坏了**。
     * ⇒ 前提不成立时**当场以专属码收场**（一个字的世界操作都不做），让电池记 `SKIP`（"结论不作数"），
     * 而不是一个**误导性的功能失败**。
     */
    private static Supplier<Task> guarded(BotPlayer bot, String owner, Supplier<Task> factory) {
        return () -> new PremiseGateTask(owner,
                () -> FixtureThirdParty.refusal(bot, PREMISE_MIN, PREMISE_MAX), factory.get());
    }

    /** `skipWhen`：前提不成立 ⇒ 记 `SKIP`（**SKIP 不计入 PASS** ⇒ 整轮转 `DEGRADED`，绝不假绿）。 */
    private static boolean premiseFailed(Task task) {
        return FixtureThirdParty.CODE.equals(task.failureReason());
    }

    /** 前提闸门会**包住**内层任务 ⇒ 按内层类型写的判据（`doneWhen`）必须先穿透包装。 */
    private static Task unwrap(Task task) {
        return task instanceof PremiseGateTask gate ? unwrap(gate.inner()) : task;
    }

    /** 传送到统一起点（与电池 `teleportBot` 逐字段一致 ✓；顺带起"先热区块再 fill"的作用 ✓）。 */
    private static void to(BotPlayer bot, BlockPos foot) {
        bot.teleportTo(bot.serverLevel(), foot.getX() + 0.5D, foot.getY(), foot.getZ() + 0.5D,
                Set.of(), bot.getYRot(), bot.getXRot());
        bot.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        bot.controller().stopMovement();
    }
}
