package com.dddgn.alice.task;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.decision.CandidateMenu;
import com.dddgn.alice.decision.GoalAction;
import com.dddgn.alice.job.JobLauncher;
import com.dddgn.alice.job.JobRequest;
import com.dddgn.alice.log.BotLog;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * **挖矿候选菜单的契约自检**（M1 / G1）：把"`mine` 不许猜位置"做成**纯逻辑断言**
 * （不改世界、不调 LLM）⇒ 能进串联回归电池，任何改动都跑得到。
 *
 * <p>背景（`survey/08` §5.7 审计 G1）：在此之前 `CandidateMenu` 只产 lumber/collect/region/craftable
 * 四类，`mine` **没有菜单条目**，而 `GoalAction.parseStartJob` 对 `mine` 也不校验 `target`
 * ⇒ `center` 只能是 `botPos` ⇒ **LLM 事实上在猜位置**（`[Job] launch kind=MINE` 后大概率
 * `no_reachable_candidate`）。本夹具把修法钉住：
 *
 * <pre>
 * 1 矿石场景里菜单**必须**含 mine 候选（正例；条目 id = `block@x,y,z`，与 MineJob 决策日志同口径）
 * 2 条目必须带 `block=`（方块 id 由**确定性层**算出）
 * 3 缺 target：{"kind":"mine"} ⇒ 必须 Refused（不猜坐标）
 * 4 未知 target：target="block@0,0,0"（不在菜单里）⇒ 必须 Refused
 * 5 命中菜单：target=<菜单里的 id> ⇒ StartJob 且 **productTag 逐字等于条目的 block=**
 * 6 半径自洽：报的 radius **必须覆盖**该目标到 bot 的距离（否则 Job 在半径外找不到它）
 * 7 冲突以菜单为准：LLM 自写一个不同的 productTag ⇒ 不采信它，且**有 clamp 记录**
 * 8 前置拒绝：空/未知 productTag 的 mine 请求由 `JobLauncher.refusalReason` **如实拒绝**
 *   （**不抛异常** —— 异常会穿过 assignJob 冒到调用方，可能打崩服务端 tick）
 * </pre>
 */
public class MineMenuCheckTask implements Task {

    private final BotPlayer bot;
    private final ServerPlayer observer;
    private final List<String> failures = new ArrayList<>();
    /** **真实执行过的 check 数**（2026-09-17 修：原先 SUMMARY 里是写死的字面量 `checks=11`，
     *  于是新增的 check **不会**被计入 ⇒ 那个数字在骗人）。 */
    private int checksRun;

    private boolean done;
    /** 是否已把 bot 挪到场景起点（**夹具自带传送**，不依赖电池的 provision；见 PLAYBOOK §5.0d）。 */
    private boolean moved;

    public MineMenuCheckTask(BotPlayer bot, ServerPlayer observer) {
        this.bot = bot;
        this.observer = observer;
    }

    @Override
    public String taskName() {
        return "MineMenuCheck";
    }

    @Override
    public TaskTarget target() {
        return TaskTarget.block(bot.blockPosition());
    }

    @Override
    public String failureReason() {
        return failures.isEmpty() ? "" : String.join(" | ", failures);
    }

    @Override
    public String terminalReason() {
        return done ? (failures.isEmpty() ? "passed" : "failed") : "";
    }

    @Override
    public Status tick() {
        if (done) {
            return failures.isEmpty() ? Status.DONE : Status.FAILED;
        }
        if (!moved) {
            // 本夹具依赖**矿石场景**（附近必须有矿）⇒ 先自己站到场景起点，**下一 tick 再干活**
            // （传送后立刻扫描会撞上"区块/实体还没就绪"⇒ 假失败）
            teleportToStart();
            moved = true;
            BotLog.info("[MineMenu] 已传送 bot 到矿石场景起点 {}（{}）",
                    OreCourseAnchor.START_FOOT.toShortString(), bot.blockPosition().toShortString());
            return Status.RUNNING;
        }
        done = true;

        CandidateMenu menu = CandidateMenu.build(bot);
        var mine = menu.entries().stream()
                .filter(e -> "mine".equals(e.kind())).findFirst().orElse(null);
        // **队列第②项判据（2026-09-17）**：矿扫描必须**一遍**完成 ——
        // 读取次数 ≈ 单遍体积 (2r+1)³，而**不是**「目标数 × 单遍」（原来 11 个目标各扫一整遍）。
        // 反向对照：改回"每目标扫一遍" ⇒ 本 check 必红。
        int reads = CandidateMenu.lastMineScanBlockReads();
        int targets = CandidateMenu.lastMineScanTargets();
        int r = CandidateMenu.mineScanRadius();
        long perScan = (long) (2 * r + 1) * (2 * r + 1) * (2 * r + 1);
        int unscanned = CandidateMenu.lastMineScanUnscanned();
        check("矿扫描一遍完成（读取=" + reads + " ≈ 单遍" + perScan + "，目标数=" + targets
                        + "；不许是 目标数×单遍=" + (perScan * Math.max(1, targets)) + "）",
                reads > 0 && reads <= perScan * 2);
        // ⭐ D-329 ①（用户裁定「只扫已加载」）+ D-331 同类：**不变式** = 读到的 + 未加载跳过的 = 扫描体积。
        // 这条同时防两个方向：读数少了必须是"被跳过"，不是"漏扫"；也不是"读了却没计数"。
        check("扫描不变式：block_reads + unscanned = 扫描体积（读=" + reads + " 未加载跳过=" + unscanned
                        + " 体积=" + perScan + "）",
                reads + unscanned == perScan);
        check("矿石场景在已加载区内 ⇒ 本次扫描不该有未加载跳过（unscanned=" + unscanned + "）",
                unscanned == 0);

        // ⭐ **"扫描不加载区块"门禁**（`D-329` ① / `D-331` 同类缺陷）：扫一个**远在加载半径之外**的中心，
        // 必须"未加载 ⇒ 跳过（只记未扫）"，且**扫描后该区块仍未加载**。
        // 反向对照（改回无守卫的 `getBlockState`）⇒ 这一条必红：区块会被**同步加载**进来。
        BlockPos farCenter = bot.blockPosition().offset(400, 0, 0);
        boolean farLoadedBefore = bot.serverLevel().hasChunkAt(farCenter);
        com.dddgn.alice.job.mine.MineCandidateSource.resetUnscanned();
        com.dddgn.alice.job.mine.MineCandidateSource.resetBlockReads();
        var farScan = com.dddgn.alice.job.mine.MineCandidateSource.candidatesForTargets(
                bot, com.dddgn.alice.job.GoalSpec.mineBlocks(farCenter, 8, 1, 3600), List.of(), 8);
        long farCells = 17L * 17L * 17L;
        check("远距离扫描必须只记「未扫」、一个方块都不读（读=" + farScan.blockReads()
                        + " 未加载跳过=" + farScan.unscanned() + " 体积=" + farCells
                        + "，且中心扫描前未加载=" + farLoadedBefore + "）",
                !farLoadedBefore && farScan.blockReads() == 0 && farScan.unscanned() == farCells);
        check("远距离扫描**不许把目标区块加载进来**（中心=" + farCenter.toShortString()
                        + " 扫描前=" + farLoadedBefore + " 扫描后=" + bot.serverLevel().hasChunkAt(farCenter) + "）",
                !bot.serverLevel().hasChunkAt(farCenter));
        check("矿石场景里菜单必须含 mine 候选", mine != null);

        // ⭐⭐ `D-329` §2 **S4 分片扫描** + **S3 预算受限**（2026-09-20 落地）。
        // 为什么放在本夹具：扫描的**不变量**（读+未扫=体积）本来就断言在这里；S4 只是把"一次全量"
        // 换成"多次分片"，判据必须跟着把**"分片后仍然等价"**这件事咬住 —— 否则分片很容易变成"少扫"。
        runScanContractChecks();

        // ⭐⭐ `D-361` **种类分配**（用户 2026-09-20：「挖一组煤炭和一组铁，煤炭多了就不要了」+
        // 「种类分配也不能限定成 ID，也要支持标签」）。
        runKindAllocationChecks();

        String block = CandidateMenu.extraValue(mine, "block");
        check("mine 条目必须带 block=（方块 id 由确定性层给出）", block != null && !block.isBlank());

        // 3/4：**没有菜单条目就不许起挖掘 Job**（这正是 M1 的核心）
        checkRefused(menu, "缺 target 的 mine", "{\"action\":\"start_job\",\"kind\":\"mine\"}");
        checkRefused(menu, "不在菜单里的 mine target",
                "{\"action\":\"start_job\",\"kind\":\"mine\",\"target\":\"block@0,0,0\"}");

        if (mine != null) {
            GoalAction action = GoalAction.parse(
                    "{\"action\":\"start_job\",\"kind\":\"mine\",\"target\":\"" + mine.id()
                            + "\",\"quota\":2}", bot, menu);
            if (action instanceof GoalAction.StartJob start) {
                check("命中菜单 ⇒ center 取候选位置",
                        start.request().center().equals(mine.pos()));
                check("productTag 逐字等于条目的 block=",
                        block != null && block.equals(start.request().productTag()));
                double distance = Math.sqrt(
                        mine.pos().distSqr(bot.blockPosition().immutable()));
                check("radius 覆盖目标距离（" + start.request().radius() + " >= "
                                + String.format(java.util.Locale.ROOT, "%.1f", distance) + "）",
                        start.request().radius() >= distance);
                check("该请求通过前置拒绝检查",
                        JobLauncher.refusalReason(bot, start.request()) == null);
            } else {
                failures.add("命中 mine 菜单却未 StartJob：" + action);
            }

            // 7：LLM 自写 productTag 与菜单冲突 ⇒ **以菜单为准**（不猜语义）
            GoalAction conflicted = GoalAction.parse(
                    "{\"action\":\"start_job\",\"kind\":\"mine\",\"target\":\"" + mine.id()
                            + "\",\"productTag\":\"minecraft:diamond_ore\"}", bot, menu);
            if (conflicted instanceof GoalAction.StartJob start) {
                check("冲突时以菜单为准",
                        block != null && block.equals(start.request().productTag()));
                check("冲突有 clamp 记录", !start.clamps().isEmpty());
            } else {
                failures.add("productTag 冲突却未 StartJob：" + conflicted);
            }
        }

        // 8：前置拒绝（**返回值**表达失败，不抛异常）
        check("空 productTag 被前置拒绝",
                JobLauncher.refusalReason(bot,
                        JobRequest.mine(bot.blockPosition(), 4, 1, 200, null)) != null);
        check("未知 productTag 被前置拒绝",
                JobLauncher.refusalReason(bot,
                        JobRequest.mine(bot.blockPosition(), 4, 1, 200, "alice:not_a_block")) != null);
        // 正例对照：合法的 mine 请求**不许**被拒（否则前置检查太宽会挡住正常挖掘）
        check("合法 mine 请求不被拒",
                JobLauncher.refusalReason(bot,
                        JobRequest.mine(bot.blockPosition(), 4, 1, 200, "minecraft:iron_ore")) == null);

        // ⭐⭐ `D-363` **break 分量**（成本场估算 → top-K 精算）—— 放在最后：它要临时改场景（给矿加盖子）
        runBreakCostChecks();
        runRefineAmortizationChecks();
        // ⭐⭐ `D-364` **垫方块只在「掉落物真会丢」时** —— 同样放最后（临时改场景，用完复位）
        runSupportTriggerChecks();
        // ⭐⭐ `D-365` **目标在视线内就地挖**（用户 2026-09-20 要求）
        runMineInPlaceChecks();

        boolean pass = failures.isEmpty();
        BotLog.info("[MineMenu] SUMMARY checks={} failures={} mineEntries={} {} → {}",
                checksRun,
                failures.size(), mine == null ? 0 : 1, failures, pass ? "PASS" : "FAIL");
        if (observer != null && !observer.hasDisconnected() && !observer.isRemoved()) {
            observer.sendSystemMessage(Component.literal("[alice] 挖矿菜单契约自检 "
                    + (pass ? "PASS" : "FAIL " + failures)));
        }
        // **结束复位**（PLAYBOOK §5.0d）：停输入 + 回到场景起点，失败路径同样走
        bot.controller().stopMovement();
        teleportToStart();
        BotLog.info("[MineMenu] 结束复位：bot 回到 {}（onGround={}）",
                bot.blockPosition().toShortString(), bot.onGround());
        return pass ? Status.DONE : Status.FAILED;
    }

    private void teleportToStart() {
        bot.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        bot.controller().stopMovement();
        bot.teleportTo(bot.serverLevel(),
                OreCourseAnchor.START_FOOT.getX() + 0.5D,
                OreCourseAnchor.START_FOOT.getY(),
                OreCourseAnchor.START_FOOT.getZ() + 0.5D,
                java.util.Set.of(), bot.getYRot(), bot.getXRot());
    }

    /**
     * ⭐ `D-361` **种类分配**（用户 2026-09-20）的判据组。
     *
     * <p>为什么必须断言（而不是"实现完看一眼"）：三件事**只有断言能证明**——
     * ① 键**既支持标签、也支持方块 ID**（用户追加约束）；② 多匹配时**按声明顺序**取第一条（确定性）；
     * ③ **满足了就不再选它**（`kind_quota_met`）而**不是**静默少挖，且空计划必须**惰性**
     * （否则"没配种类"的老行为会被悄悄改掉）。
     *
     * <p>反向对照（改法，逐条实测过）：`refusal` 的 `>=` 改 `>`、`indexOf` 改成从后往前找、
     * 解析去掉重复键去重、`sumQuota` 只返回第一条 —— 四条注入各自把对应判据打红。
     */
    private void runKindAllocationChecks() {
        // ① 键：标签 / 方块 id 都能解析
        var tagSpec = com.dddgn.alice.job.mine.MineKindPlan.parse(List.of("forge:ores/coal=64"));
        check("种类分配：**标签键**可解析（" + tagSpec.specs() + "）",
                tagSpec.specs().size() == 1 && tagSpec.clean()
                        && "forge:ores/coal".equals(tagSpec.specs().get(0).key())
                        && tagSpec.specs().get(0).count() == 64);
        var idSpec = com.dddgn.alice.job.mine.MineKindPlan.parse(List.of("minecraft:iron_ore=32"));
        check("种类分配：**方块 id 键**同样可解析（count="
                        + (idSpec.specs().isEmpty() ? "-" : idSpec.specs().get(0).count()) + "）",
                idSpec.specs().size() == 1 && idSpec.clean() && idSpec.specs().get(0).count() == 32);

        // ② 坏条目**一条都不许猜着收下**（少写数量 / 0 / 负数 / 非数字 / 空 / 缺键）
        var bad = com.dddgn.alice.job.mine.MineKindPlan.parse(
                List.of("forge:ores/coal", "x=0", "x=-2", "y=abc", "", "=5"));
        check("种类分配：坏条目全被拒（specs=" + bad.specs().size() + " problems="
                        + bad.problems().size() + "）",
                bad.specs().isEmpty() && bad.problems().size() >= 5);

        // ③ 重复键只留第一条（确定性；不然"优先级"就随文件顺序漂）
        var dup = com.dddgn.alice.job.mine.MineKindPlan.parse(
                List.of("minecraft:coal_ore=1", "minecraft:coal_ore=2"));
        check("种类分配：重复键只留第一条（count="
                        + (dup.specs().isEmpty() ? "-" : dup.specs().get(0).count()) + "）",
                dup.specs().size() == 1 && dup.specs().get(0).count() == 1 && dup.problems().size() == 1);

        // ④ 生产解析：标签解析成**标签**（不是被当成方块 id 丢掉）、方块解析成方块
        var plan = com.dddgn.alice.job.mine.MineKindPlan.resolve(bot.serverLevel(),
                List.of("forge:ores/coal=64", "minecraft:iron_ore=32"));
        check("种类分配：标签键解析成标签、id 键解析成方块（" + plan.describe() + "）",
                plan.active() && plan.entries().size() == 2
                        && plan.entries().get(0).target().describe().startsWith("#")
                        && "minecraft:iron_ore".equals(plan.entries().get(1).target().describe()));
        check("种类分配：总配额 = 各条之和（实测 " + plan.sumQuota() + "，应为 96）",
                plan.sumQuota() == 96);
        check("种类分配：未知键被拒且不进 entries",
                !com.dddgn.alice.job.mine.MineKindPlan
                        .resolve(bot.serverLevel(), List.of("alice:not_a_block=4")).active());

        // ⑤ 满足后不再要它 + 声明顺序优先（纯逻辑，反向对照的靶子）
        var ordered = com.dddgn.alice.job.mine.MineKindPlan.of(List.of(
                new com.dddgn.alice.job.mine.MineKindPlan.Entry("forge:ores", 8,
                        com.dddgn.alice.job.mine.MineCandidateSource.Target.ofTag(
                                net.minecraft.tags.TagKey.create(
                                        net.minecraft.core.registries.Registries.BLOCK,
                                        new net.minecraft.resources.ResourceLocation("forge", "ores")))),
                new com.dddgn.alice.job.mine.MineKindPlan.Entry("minecraft:coal_ore", 8,
                        com.dddgn.alice.job.mine.MineCandidateSource.Target.ofBlock(
                                net.minecraft.world.level.block.Blocks.COAL_ORE))));
        var coal = net.minecraft.world.level.block.Blocks.COAL_ORE.defaultBlockState();
        var iron = net.minecraft.world.level.block.Blocks.IRON_ORE.defaultBlockState();
        var stone = net.minecraft.world.level.block.Blocks.STONE.defaultBlockState();
        check("种类分配：多匹配时按**声明顺序**取第一条（coal index=" + ordered.indexOf(coal) + "）",
                ordered.indexOf(coal) == 0);
        // ⭐ 标签条目的**覆盖面**也要咬住：`#forge:ores` 里当然含铁矿石 ⇒ 铁落到第 0 条（标签真的在生效）。
        // （第一版这里写错成"铁应该不被要" ⇒ 夹具当场红 —— 那是判据写错，不是代码错：铁确实被 `#forge:ores` 覆盖。）
        check("种类分配：标签条目**真的覆盖**其成员（iron index=" + ordered.indexOf(iron) + "，应为 0）",
                ordered.indexOf(iron) == 0);
        check("种类分配：没够 ⇒ 还要（refusal=" + ordered.refusal(0, new int[] {3, 0}) + "）",
                ordered.refusal(0, new int[] {3, 0}) == null);
        check("种类分配：够了 ⇒ **不再选它**（refusal=" + ordered.refusal(0, new int[] {8, 0}) + "）",
                "kind_quota_met".equals(ordered.refusal(0, new int[] {8, 0})));
        check("种类分配：**不在分配里**的方块 ⇒ 不要（stone index=" + ordered.indexOf(stone)
                        + " refusal=" + ordered.refusal(ordered.indexOf(stone), new int[] {0, 0}) + "）",
                ordered.indexOf(stone) < 0
                        && "kind_not_wanted".equals(
                                ordered.refusal(ordered.indexOf(stone), new int[] {0, 0})));
        check("种类分配：**空计划必须惰性**（active=" + com.dddgn.alice.job.mine.MineKindPlan.NONE.active()
                        + " refusal="
                        + com.dddgn.alice.job.mine.MineKindPlan.NONE.refusal(-1, new int[0]) + "）",
                !com.dddgn.alice.job.mine.MineKindPlan.NONE.active()
                        && com.dddgn.alice.job.mine.MineKindPlan.NONE.refusal(-1, new int[0]) == null);

        // ⑥ 配置 plumbing（零参数入口靠它：口径写在 config/alice-mine.json 里）
        var config = com.dddgn.alice.job.mine.MineCostConfig.of(0.0D, List.of("forge:ores/coal=64"));
        check("种类分配：可随配置携带（" + config.describe() + "）",
                config.kindQuotas().size() == 1 && config.kindQuotas().get(0).endsWith("=64"));
    }

    /**
     * ⭐ `D-363` **`break` 分量**（用户 2026-09-20：「`break` 分量我觉得可以马上做」）的判据组。
     *
     * <p>缺口（真机 A 路线第一轮）：选择成本原本只算**纯通行**（现成可站的站位点）⇒ 真实地形里矿体嵌在地表、
     * 一个合格站位点都没有 ⇒ 全部候选 `∞`（实测 `cells=0`）⇒ 排序退化成欧氏最近；而执行器用的是 `TUNNEL`。
     *
     * <p>本组做法：把矿脉场景里的一块**裸露**铁矿用石头**盖上**（它的顶面本来是唯一暴露面）⇒ 再没有任何
     * 现成站位点（LOS 全被挡）——正是"必须挖出来才能挖"的最小复现。然后断言：
     * ① 纯成本场估不出（`∞`，这就是原来的退化）；② 加了精算之后**有了有限成本**（= 规划器的 `score`，
     * 而规划器的路径成本**本来就含破坏 tick 折算**）；③ 精算值与直接跑规划器**逐位相同**（证明确实用了它）。
     * 收尾把盖子去掉（场景复位）。
     *
     * <p>反向对照（改法）：把 `PlanRefinedCostProvider` 的 `topK` 置 0（或直接返回成本场结果）⇒ ②③ 全红。
     */
    /**
     * `D-368` **摊销精算**：每次选择**有界**（≤ {@code REFINE_PER_SELECT} 次规划器）+ 缓存**跨选择覆盖全部候选**。
     *
     * <p>为什么必须摊销（真机证据，`docs/reviews/2026-09-20-mine-round3-root-cause.md` §2bis / §4）：
     * `D-363` 的"每次选择固定精算 top-3"同时造成 ① **102→126 ms/选择（tick 预算 50 ms）** 与
     * ② **`精算 尝试=3 成功=3（候选 91）` ⇒ 97% 候选永远没有真实成本**（脉内其余矿永远排不上 ⇒ 绕远折返）。
     *
     * <p>本夹具用**注入的精算函数 + 注入的时钟**做纯逻辑断言（不依赖世界地形）：
     * ① 每次选择只跑 1 次精算；
     * ② 连续选择后缓存覆盖**全部**候选，且成本表里的最小值 == **真最优**（构造"最优在更远处"：
     *    真成本 `100 - index`，索引越大离 bot 越远 ⇒ 若只精算最近的 3 个，永远选到 100）；
     * ③ TTL 过期后必须**重新精算**（世界会变）；
     * ④ 精算失败（∞）也记账，**不许挡住轮转**，且**不许据此拒绝候选**。
     */
    private void runRefineAmortizationChecks() {
        // 本组不读世界地形（base 是 scripted ⇒ 忽略 spec），但接口需要一份 GoalSpec
        final var amortSpec = com.dddgn.alice.job.GoalSpec.mineBlocks(
                new net.minecraft.core.BlockPos(0, 62, 100),
                com.dddgn.alice.job.mine.MineCandidateSource.SCAN_RADIUS, 1, 600);
        final var anchors = new java.util.ArrayList<com.dddgn.alice.job.Candidate>();
        for (int index = 0; index < 10; index++) {
            anchors.add(new com.dddgn.alice.job.Candidate(new net.minecraft.core.BlockPos(0, 62, 100 + index),
                    "block", java.util.Map.of()));
        }
        // 真成本：索引越大越远，但成本越低（构造"真最优在更远处"）
        final java.util.Map<Long, Double> trueCost = new java.util.HashMap<>();
        for (int index = 0; index < anchors.size(); index++) {
            trueCost.put(anchors.get(index).anchor().asLong(), 100.0D - index);
        }
        final java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        final long[] clock = {0L};
        // 成本场全 ∞（真实地形里就是这样）⇒ 排序退化成"欧氏最近"
        var allUnknown = com.dddgn.alice.job.mine.CandidateCostProvider.scripted(java.util.Map.of());
        var amortized = new com.dddgn.alice.job.mine.PlanRefinedCostProvider(allUnknown,
                com.dddgn.alice.job.mine.PlanRefinedCostProvider.REFINE_PER_SELECT,
                com.dddgn.alice.job.mine.PlanRefinedCostProvider.CACHE_TTL_TICKS,
                (who, candidate) -> {
                    calls.incrementAndGet();
                    return trueCost.get(candidate.anchor().asLong());
                },
                () -> clock[0]);

        var first = amortized.estimate(bot, amortSpec, anchors);
        check("摊销精算①：一次选择只精算 " + com.dddgn.alice.job.mine.PlanRefinedCostProvider.REFINE_PER_SELECT
                        + " 个（实测 calls=" + calls.get() + "）",
                calls.get() == com.dddgn.alice.job.mine.PlanRefinedCostProvider.REFINE_PER_SELECT);
        long finiteAfterFirst = anchors.stream()
                .filter(candidate -> Double.isFinite(first.travel(candidate))).count();
        check("摊销精算①：本 tick 只有 1 个候选拿到真实成本、其余保持「估不出」（实测 finite="
                        + finiteAfterFirst + "，不许因此被拒绝）", finiteAfterFirst == 1L);

        // 连续选择（每次推进游戏时间）⇒ 覆盖增长到全部
        int selects = 0;
        while (calls.get() < anchors.size() + 1 && selects < 40) {
            clock[0] += 5;
            selects++;
            var step = amortized.estimate(bot, amortSpec, anchors);
            if (selects == 1) {
                check("摊销精算②：缓存覆盖随选择增长（第 2 次选择后 calls=" + calls.get() + "）",
                        calls.get() == com.dddgn.alice.job.mine.PlanRefinedCostProvider.REFINE_PER_SELECT + 1);
                long finiteAfterSecond = anchors.stream()
                        .filter(candidate -> Double.isFinite(step.travel(candidate))).count();
                check("摊销精算②：已被覆盖的候选成本进入成本表（实测 finite=" + finiteAfterSecond + "）",
                        finiteAfterSecond == 2L);
            }
        }
        check("摊销精算②：缓存最终覆盖**全部**候选（精算次数=" + calls.get() + " ≥ " + anchors.size()
                        + "，不再锁死最近 3 个）", calls.get() >= anchors.size());
        var warmed = amortized.estimate(bot, amortSpec, anchors);
        double best = anchors.stream().mapToDouble(candidate -> warmed.travel(candidate)).min().orElse(Double.NaN);
        check("摊销精算②：成本表里的最优 == 真最优（实测 " + best + "，真最优 91.0；"
                        + "「只精算最近 3 个」会永远停在 100.0）", Math.abs(best - 91.0D) < 1.0E-6D);
        check("摊销精算②：一次选择之后不应该再有新的精算（TTL 内复用缓存，calls=" + calls.get() + "）",
                calls.get() == anchors.size());

        // ③ TTL：过期后重新精算
        int beforeTtl = calls.get();
        clock[0] += com.dddgn.alice.job.mine.PlanRefinedCostProvider.CACHE_TTL_TICKS + 1L;
        amortized.estimate(bot, amortSpec, anchors);
        check("摊销精算③：超过 TTL 后必须重新精算（calls " + beforeTtl + " → " + calls.get() + "）",
                calls.get() > beforeTtl);

        // ④ 失败（∞）也要记账：否则同一个失败候选每次挡住轮转 ⇒ 覆盖涨不上去
        final java.util.concurrent.atomic.AtomicInteger failCalls = new java.util.concurrent.atomic.AtomicInteger();
        final long[] clock2 = {0L};
        // ⚠️ 失败靶子必须是**排序第一个**（否则它不一定会挡住轮转 ⇒ 判据没有判别力，反向对照实测漏过一次）
        var rankedFirst = com.dddgn.alice.job.mine.PlanRefinedCostProvider
                .rankForRefine(bot, anchors, allUnknown.estimate(bot, amortSpec, anchors)).get(0);
        var failures = new com.dddgn.alice.job.mine.PlanRefinedCostProvider(allUnknown,
                com.dddgn.alice.job.mine.PlanRefinedCostProvider.REFINE_PER_SELECT,
                com.dddgn.alice.job.mine.PlanRefinedCostProvider.CACHE_TTL_TICKS,
                (who, candidate) -> {
                    failCalls.incrementAndGet();
                    return rankedFirst.anchor().equals(candidate.anchor())
                            ? Double.POSITIVE_INFINITY : 5.0D;
                },
                () -> clock2[0]);
        for (int index = 0; index < 4; index++) {
            clock2[0] += 5;
            failures.estimate(bot, amortSpec, anchors);
        }
        check("摊销精算④：精算失败也记账 ⇒ 它不会每次挡住轮转（失败靶子="
                        + rankedFirst.anchor().toShortString() + " · 精算次数=" + failCalls.get()
                        + " ≤ 4 · 覆盖=" + failures.coveredCount(anchors) + "/" + anchors.size() + "）",
                failCalls.get() <= 4 && failures.coveredCount(anchors) == 4);
        check("摊销精算④：精算失败的候选保持「估不出」（没有被当成「不能挖」）",
                !Double.isFinite(failures.estimate(bot, amortSpec, anchors).travel(anchors.get(0))));
    }

    private void runBreakCostChecks() {
        final net.minecraft.server.level.ServerLevel level = bot.serverLevel();
        final var server = level.getServer();
        // 场景：矿石场景里**现搭**一个"被石头包住的矿"，且包层与 bot 脚位**同一层**。
        // ⚠️ 为什么当时必须同层：`miningApproach` 当时禁用 `DOWNWARD`（**D-366b 已放开**）⇒ 矿的暴露面若只在**脚下一层**，
        // 规划器会**如实**报 `tunnel=no_reachable_tunnel_standing_point`（实测过）——那是能力边界，不是 bug；
        // 同层的石头面可以被 `BREAK_AND_ENTER` 挖开 ⇒ 才是"必须挖出来才能挖"的最小复现。
        final BlockPos ore = new BlockPos(52, 63, 128);
        final var shell = List.of(ore.offset(1, 0, 0), ore.offset(-1, 0, 0), ore.offset(0, 0, 1),
                ore.offset(0, 0, -1), ore.above(), ore.below());
        final var spec = com.dddgn.alice.job.GoalSpec.mineBlocks(ore, 8, 1, 600);
        final var candidate = new com.dddgn.alice.job.Candidate(ore, "block",
                java.util.Map.of("block", "minecraft:iron_ore", "d", "0",
                        "y", String.valueOf(ore.getY())));
        try {
            for (BlockPos pos : shell) {
                level.setBlockAndUpdate(pos,
                        net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
            }
            level.setBlockAndUpdate(ore,
                    net.minecraft.world.level.block.Blocks.IRON_ORE.defaultBlockState());
            final var only = List.of(candidate);

            var fieldOnly = new com.dddgn.alice.job.mine.StandingCostField().estimate(bot, spec, only);
            check("break 分量：纯成本场对「被石头包住的矿」估不出（travel="
                            + fieldOnly.travel(candidate) + " · " + fieldOnly.note() + "）",
                    Double.isInfinite(fieldOnly.travel(candidate)));

            var refined = com.dddgn.alice.job.mine.PlanRefinedCostProvider.production()
                    .estimate(bot, spec, only);
            double cost = refined.travel(candidate);
            var planner = new com.dddgn.alice.task.mining.MiningPlanner().plan(bot, ore);
            double plannerCost = planner.success() ? planner.score().getScore()
                    : Double.POSITIVE_INFINITY;
            check("break 分量：精算后有有限成本（travel=" + cost + " · " + refined.note() + "）",
                    Double.isFinite(cost));
            check("break 分量：精算值 == 规划器 score（" + cost + " vs " + plannerCost
                            + "；规划器路径成本含破坏 tick 折算）",
                    planner.success() && Math.abs(cost - plannerCost) < 1.0E-6D);

            // 纯排序：有限的排前面、同为 ∞ 时近的先（确定性）
            var far = new com.dddgn.alice.job.Candidate(ore.offset(20, 0, 0), "block",
                    java.util.Map.of());
            var near = new com.dddgn.alice.job.Candidate(ore.offset(2, 0, 0), "block",
                    java.util.Map.of());
            var scripted = new com.dddgn.alice.job.mine.CandidateCostProvider.Result(
                    java.util.Map.of(near.anchor().asLong(), 9.0D), 0, "scripted");
            var ranked = com.dddgn.alice.job.mine.PlanRefinedCostProvider
                    .rankForRefine(bot, List.of(far, near), scripted);
            check("break 分量：精算候选排序 = 有限成本优先、其余按距离（" + ranked.stream()
                            .map(c -> c.anchor().toShortString()).toList() + "）",
                    ranked.get(0).anchor().equals(near.anchor()));
        } finally {
            // 场景复位（矿石场景自带 terrain 函数）
            server.getCommands().performPrefixedCommand(
                    server.createCommandSourceStack().withSuppressedOutput(),
                    "function alice_test:ore_course_terrain");
        }
    }

    /**
     * ⭐ `D-364` **垫方块的触发条件**（用户 2026-09-20 点名要修的那条）。
     *
     * <p>真机实测（第一轮）：先挖 y=72、再挖 y=73 时，下方正是**自己刚挖空的空气** ⇒ 旧判据
     * `!hasSupportBelow` 判它「悬空」⇒ 要垫方块 ⇒ 垫不上就把目标判死（9 次 `SUPPORT_PLACE_FAILED`），
     * 垫上了又**挡住相邻矿石的视线**（`LINE_OF_SIGHT_BLOCKED`）。而代码注释写的原意只是
     * 「防止掉落物掉进**虚空/岩浆/深坑**」—— 实现比意图宽得多。
     *
     * <p>本组在矿石场景现搭一个**同层**的临时矿（`(54,63,132)`）并改它下面的几何，断言三条：
     * ① 浅坑（掉落物落坑底、捡得回来）⇒ **不垫**（= 真机那一格）· ② 4 格内无可落面（深坑/虚空）⇒ **要垫**
     * · ③ 坑底是岩浆 ⇒ **要垫**。收尾跑场景函数复位。
     */
    private void runSupportTriggerChecks() {
        final net.minecraft.server.level.ServerLevel level = bot.serverLevel();
        final var server = level.getServer();
        final BlockPos ore = new BlockPos(54, 63, 132);
        try {
            // 夹具职责：垫方块需要手上一块可放置方块（`findPlaceableSlot`）
            com.dddgn.alice.item.FixtureToolKit.ensureHotbarTool(bot,
                    () -> new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.COBBLESTONE),
                    stack -> stack.is(net.minecraft.world.item.Items.COBBLESTONE), "cobblestone");
            bot.teleportTo(level, OreCourseAnchor.START_FOOT.getX() + 0.5D,
                    OreCourseAnchor.START_FOOT.getY(), OreCourseAnchor.START_FOOT.getZ() + 0.5D,
                    java.util.Set.of(), bot.getYRot(), bot.getXRot());
            level.setBlockAndUpdate(ore,
                    net.minecraft.world.level.block.Blocks.IRON_ORE.defaultBlockState());

            // ① 浅坑：下方 1 格空气、再下就是实心岩体 ⇒ 掉落物落坑底 ⇒ 不垫
            level.setBlockAndUpdate(ore.below(),
                    net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
            var shallow = new com.dddgn.alice.task.mining.MiningPlanner().plan(bot, ore,
                    com.dddgn.alice.task.mining.MiningBudget.forTarget(bot, level, ore, true));
            check("垫方块：浅坑（掉落物落坑底、捡得回来）⇒ **不垫**（support=" + supportPos(shallow) + "）",
                    shallow.success() && supportPos(shallow) == null);

            // ② 深坑：窗口内（4 格）都没有可落面 ⇒ 会丢 ⇒ 要垫
            for (int depth = 2; depth <= 4; depth++) {
                level.setBlockAndUpdate(ore.below(depth),
                        net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
            }
            var deep = new com.dddgn.alice.task.mining.MiningPlanner().plan(bot, ore,
                    com.dddgn.alice.task.mining.MiningBudget.forTarget(bot, level, ore, true));
            check("垫方块：4 格内无可落面（深坑/虚空）⇒ **要垫**（support=" + supportPos(deep)
                            + "，期望 " + ore.below().toShortString() + "）",
                    deep.success() && ore.below().equals(supportPos(deep)));

            // ③ 坑底岩浆 ⇒ 掉落物被销毁 ⇒ 要垫
            level.setBlockAndUpdate(ore.below(2),
                    net.minecraft.world.level.block.Blocks.LAVA.defaultBlockState());
            var lava = new com.dddgn.alice.task.mining.MiningPlanner().plan(bot, ore,
                    com.dddgn.alice.task.mining.MiningBudget.forTarget(bot, level, ore, true));
            check("垫方块：坑底是岩浆 ⇒ **要垫**（support=" + supportPos(lava) + "）",
                    lava.success() && ore.below().equals(supportPos(lava)));

            // **判别性事实**：三条读数都落日志 —— 判据绿了也要能看出"当时几何是什么、算出什么"
            BotLog.info("[MineMenu] D-364 判别性事实：临时矿={} · 浅坑 plan={} mode={} support={} · "
                            + "深坑 plan={} support={} · 岩浆 plan={} support={}（期望 {}）",
                    ore.toShortString(),
                    shallow.success(), shallow.success() ? shallow.plan().mode() : "-",
                    supportPos(shallow),
                    deep.success(), supportPos(deep), lava.success(), supportPos(lava),
                    ore.below().toShortString());
        } finally {
            // 结束复位（失败路径同样走）：矿石场景自带 terrain 函数
            server.getCommands().performPrefixedCommand(
                    server.createCommandSourceStack().withSuppressedOutput(),
                    "function alice_test:ore_course_terrain");
        }
    }

    /**
     * ⭐ `D-365` **下一个目标在视线范围内就地挖**（用户 2026-09-20 要求）。
     *
     * <p>真机实测的靶子：目标 `367,77,430`(铜矿) 离 bot 站位 `369,78,430` **只有 2 格**，
     * 规划器给了 `mode=TUNNEL pathSize=11`；这一轮 **64 次破坏里 56 次是挖路**（只有 8 次挖到目标矿）
     * ⇒ 写预算 64/64 打满、`mined 8/64`、`partial_quota` 收场。
     *
     * <p>判据用**执行期同一套**「可见 + 触及」（`tickBreak` 的前置），只是**提前问一次**来决定要不要走路；
     * 与 `isValidStandingPoint` 的区别是**不要求"这格适合站位"**（bot 已经在上面了，"适不适合站位"是寻路问题）。
     *
     * <p>本组只断言**决策**（`mineInPlace()`）：正例 = 挪到能看见+够得着的一格 ⇒ 必须就地挖；
     * 负例 = 同一计划但视线被临时方块挡住 ⇒ 必须**不**就地挖（继续走路）。
     */
    private void runMineInPlaceChecks() {
        final net.minecraft.server.level.ServerLevel level = bot.serverLevel();
        final var server = level.getServer();
        final BlockPos ore = new BlockPos(52, 62, 136);      // 场景里的裸露铁矿
        final BlockPos farFoot = new BlockPos(60, 63, 132);   // 计划起点（够不着）
        final BlockPos nearFoot = new BlockPos(51, 63, 136);  // 能看见 + 够得着，且**不是**计划的站位点
        final BlockPos screen = new BlockPos(51, 64, 136);    // 负例：挡住眼位到目标
        try {
            com.dddgn.alice.item.FixtureToolKit.ensurePickaxe(bot);
            teleport(farFoot);
            var planned = new com.dddgn.alice.task.mining.MiningPlanner().plan(bot, ore,
                    com.dddgn.alice.task.mining.MiningBudget.forTarget(bot, level, ore, true));
            boolean planOk = planned.success() && !planned.plan().standingFoot().equals(nearFoot);
            check("就地挖：远处先得到真计划（mode=" + (planned.success() ? planned.plan().mode() : "-")
                            + " stand=" + (planned.success()
                            ? planned.plan().standingFoot().toShortString() : "-")
                            + "，且不等于我们将要站的那格）", planOk);
            if (!planOk) {
                return;
            }

            // 正例：挪到"看得见也够得着"的另一格 ⇒ 必须就地挖
            teleport(nearFoot);
            var inPlace = new com.dddgn.alice.action.MineBlockRunner(bot, planned.plan(),
                    com.dddgn.alice.action.WriteGrant.of(taskName(), com.dddgn.alice.action.WriteReason.EXPECTED_TARGET));
            inPlace.tick();
            boolean positive = inPlace.mineInPlace();
            inPlace.cancel();
            check("就地挖：在视线+触及内 ⇒ **不走去站位点**（mineInPlace=" + positive + "）", positive);

            // 负例：同一计划，但视线被临时方块挡住 ⇒ 必须不就地挖
            level.setBlockAndUpdate(screen,
                    net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
            teleport(nearFoot);
            var blocked = new com.dddgn.alice.action.MineBlockRunner(bot, planned.plan(),
                    com.dddgn.alice.action.WriteGrant.of(taskName(), com.dddgn.alice.action.WriteReason.EXPECTED_TARGET));
            blocked.tick();
            boolean negative = !blocked.mineInPlace();
            blocked.cancel();
            check("就地挖：视线被挡住 ⇒ **不**就地挖（继续走计划路线）", negative);
            BotLog.info("[MineMenu] D-365 判别性事实：计划 stand={} · 就地格={} · 正例 mineInPlace={} · "
                            + "负例（挡视线）mineInPlace={}",
                    planned.plan().standingFoot().toShortString(), nearFoot.toShortString(),
                    positive, !negative);
        } finally {
            server.getCommands().performPrefixedCommand(
                    server.createCommandSourceStack().withSuppressedOutput(),
                    "function alice_test:ore_course_terrain");
        }
    }

    private void teleport(BlockPos foot) {
        bot.teleportTo(bot.serverLevel(), foot.getX() + 0.5D, foot.getY(), foot.getZ() + 0.5D,
                java.util.Set.of(), bot.getYRot(), bot.getXRot());
        bot.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        bot.controller().stopMovement();
    }

    private static net.minecraft.core.BlockPos supportPos(
            com.dddgn.alice.task.mining.MiningPlanner.Result result) {
        return result == null || result.plan() == null ? null : result.plan().supportPlacementPos();
    }

    private void check(String what, boolean ok) {
        checksRun++;
        if (!ok) {
            failures.add(what);
        }
    }

    /**
     * ⭐ `D-329` §2 **S4（分片扫描）+ S3（搜索预算受限）** 的判据组。
     *
     * <p>为什么这些判据必须存在（而不是"实现完看一眼"）：分片把"一次全量"拆成"多次部分"，
     * **最容易犯的错是"少扫了却没人发现"**（部分结果看起来跟"没有矿"一模一样）。
     * 所以这里断言三件事：① 单次调用有上限；② **拼起来仍然逐字等于一次性全量**（幂等合并 + 去重）；
     * ③ 被总预算截断时**不许**产出 `not_found`（那是把"没扫完"说成"没有"）。
     *
     * <p>反向对照（改法）：把游标改成"每次 advance 从头开始" ⇒ `visited > 体积`、单次上限与
     * "合并==全量"两条同时红；把总预算截断分支去掉 ⇒ `truncated/done` 那条红。
     */
    private void runScanContractChecks() {
        final BlockPos center = bot.blockPosition();
        final int smallBudget = 64;
        final int r = 4;
        final long volume = (long) (2 * r + 1) * (2 * r + 1) * (2 * r + 1);
        final var stone = com.dddgn.alice.job.mine.MineCandidateSource.Target
                .ofBlock(net.minecraft.world.level.block.Blocks.STONE);
        final var spec = com.dddgn.alice.job.GoalSpec.mineBlocks(center, r, 1, 600);
        final var source = new com.dddgn.alice.job.mine.MineCandidateSource(stone, r);
        final var memory = com.dddgn.alice.job.mine.MineScanMemoryData.get(bot.getServer());

        // ---- S4：分片推进 + 幂等合并 + 去重 ----
        var session = source.newSession(spec, smallBudget, Integer.MAX_VALUE);
        int calls = 0;
        while (!session.done() && calls < 100_000) {
            session.advance(bot);
            calls++;
        }
        check("S4 单次考察格数 ≤ 上限（上限=" + smallBudget + " 实测单次最大="
                        + session.maxCallVisited() + "）",
                session.maxCallVisited() <= smallBudget);
        check("S4 扫完时 visited == 体积（visited=" + session.visited() + " 体积=" + volume + "）",
                session.visited() == volume);
        check("S4 不变式 visited == reads + unscanned（" + session.visited() + " == "
                        + session.reads() + " + " + session.unscanned() + "）",
                session.visited() == session.reads() + session.unscanned());
        check("S4 确实分了多次调用（calls=" + calls + "；一次全量的话是 1）", calls > 1);

        var oneShot = com.dddgn.alice.job.mine.MineCandidateSource
                .candidatesForTargets(bot, spec, List.of(stone), r).sets().get(0);
        List<Long> oneShotIds = oneShot.viable().stream().map(c -> c.anchor().asLong()).sorted().toList();
        List<Long> chunkedIds = session.sets().get(0).viable().stream()
                .map(c -> c.anchor().asLong()).sorted().toList();
        check("S4 跨 tick 合并结果 == 一次性全量（分片=" + chunkedIds.size()
                        + " 全量=" + oneShotIds.size() + "；少扫/漏扫在这里现形）",
                oneShotIds.equals(chunkedIds));
        check("S4 去重：候选无重复（" + chunkedIds.size() + " 条 / 去重后 "
                        + chunkedIds.stream().distinct().count() + "）",
                chunkedIds.stream().distinct().count() == chunkedIds.size());

        // ---- S3：总预算截断 ⇒ "搜索受限"，**不是**"没矿" ----
        var truncated = source.newSession(spec, smallBudget, smallBudget);
        // ⭐ 写入门槛的基线**必须在这一行之前取**：判据要包住这次 `advance`，否则
        // "截断也写记忆"的注入会被读成"本来就有的条目"⇒ 假绿（第一版就是这么错的，反向对照抓出来的）。
        int entriesBeforeTruncated = memory.totalEntries();
        truncated.advance(bot);
        check("S5 被预算截断的扫描**不写记忆**（advance 前=" + entriesBeforeTruncated + " 后="
                        + memory.totalEntries() + "）",
                memory.totalEntries() == entriesBeforeTruncated);
        check("S3 总预算用尽 ⇒ truncated=true 且 done=false（truncated=" + truncated.truncated()
                        + " done=" + truncated.done() + " visited=" + truncated.visited()
                        + " 预算=" + smallBudget + "）",
                truncated.truncated() && !truncated.done() && truncated.visited() == smallBudget);
        check("S3 截断**不许**产出 not_found（出现它 = 把『没扫完』说成『这里没有』）",
                truncated.sets().get(0).rejected().stream().noneMatch(x -> x.contains("not_found")));

        // ---- S3：顶层码三分法（纯函数；截断**不许**退化成"没有可达候选"）----
        // 反向对照：把 `shortfallReason` 的第一条改回"无论如何都按挖到数算" ⇒ 前两条必红。
        String limitedNone = com.dddgn.alice.job.mine.MineJob.shortfallReason(true, 0);
        String limitedSome = com.dddgn.alice.job.mine.MineJob.shortfallReason(true, 3);
        String scannedNone = com.dddgn.alice.job.mine.MineJob.shortfallReason(false, 0);
        String scannedSome = com.dddgn.alice.job.mine.MineJob.shortfallReason(false, 3);
        check("S3 搜索受限 ⇒ search_incomplete（挖到 0 个或 3 个都一样；实测 "
                        + limitedNone + " / " + limitedSome + "）",
                "search_incomplete".equals(limitedNone) && "search_incomplete".equals(limitedSome));
        check("S3 扫完且零产出 ⇒ no_reachable_candidate（与『搜索受限』**必须区分**；实测 "
                        + scannedNone + "）",
                "no_reachable_candidate".equals(scannedNone));
        check("S3 扫完且有产出 ⇒ partial_quota（实测 " + scannedSome + "）",
                "partial_quota".equals(scannedSome));

        // ---- S5：扫描记忆（有界 + 可持久化 + **只有计数没有位置**）----
        // （`memory` 在方法开头就取，因为"截断不写记忆"这条判据要包住前面的 `advance`）

        // ② 落盘往返：写 3 条 → save/load → 逐条相等
        int savedCap = memory.cap();
        memory.setCapForTesting(64);
        long tick = bot.serverLevel().getGameTime();
        memory.noteScanned(bot.serverLevel(), "#test:memory", 11, 22, tick, 3, 256);
        memory.noteScanned(bot.serverLevel(), "#test:memory", 12, 22, tick + 1, 0, 256);
        memory.noteScanned(bot.serverLevel(), "#test:memory", 13, 22, tick + 2, 7, 128);
        com.dddgn.alice.job.mine.MineScanMemoryData reloaded =
                com.dddgn.alice.job.mine.MineScanMemoryData.load(memory.save(new net.minecraft.nbt.CompoundTag()));
        boolean roundTrip = reloaded.totalEntries() == memory.totalEntries();
        for (int cx = 11; cx <= 13 && roundTrip; cx++) {
            var a = memory.at(bot.serverLevel(), "#test:memory", cx, 22);
            var b = reloaded.at(bot.serverLevel(), "#test:memory", cx, 22);
            roundTrip = a != null && b != null && a.lastTick() == b.lastTick()
                    && a.hits() == b.hits() && a.cellsVisited() == b.cellsVisited();
        }
        check("S5 落盘往返后记忆逐条不变（条目=" + memory.totalEntries() + " / 往返后="
                        + reloaded.totalEntries() + "）", roundTrip);
        check("S5 落盘往返保留上限与淘汰计数（cap=" + reloaded.cap() + " evicted=" + reloaded.evictedTotal() + "）",
                reloaded.cap() == memory.cap() && reloaded.evictedTotal() == memory.evictedTotal());

        check("S5 记忆条目读取口径：写进去的那条能原样读回（hits=3 cells=256）",
                memory.at(bot.serverLevel(), "#test:memory", 11, 22) != null
                        && memory.at(bot.serverLevel(), "#test:memory", 11, 22).hits() == 3
                        && memory.at(bot.serverLevel(), "#test:memory", 11, 22).cellsVisited() == 256);

        // ③ 有界 + **确定性淘汰**：上限压到 4，写 6 条（tick 递增）⇒ 只剩 4 条，淘汰的**恰好是最旧 2 条**；
        //    两个**独立实例**做同样的写入 ⇒ 留下来的必须是同一批（"不许随机"是靠这条判据咬住的）。
        var evictA = new com.dddgn.alice.job.mine.MineScanMemoryData();
        var evictB = new com.dddgn.alice.job.mine.MineScanMemoryData();
        for (var instance : List.of(evictA, evictB)) {
            instance.setCapForTesting(4);
            for (int i = 0; i < 6; i++) {
                instance.noteScanned(bot.serverLevel(), "#test:evict", 100 + i, 200, 1000L + i, i, 256);
            }
        }
        boolean boundedOk = evictA.totalEntries() == 4 && evictA.evictedTotal() == 2
                && evictA.at(bot.serverLevel(), "#test:evict", 100, 200) == null
                && evictA.at(bot.serverLevel(), "#test:evict", 101, 200) == null
                && evictA.at(bot.serverLevel(), "#test:evict", 105, 200) != null;
        boolean deterministic = true;
        for (int i = 100; i <= 105; i++) {
            deterministic &= (evictA.at(bot.serverLevel(), "#test:evict", i, 200) != null)
                    == (evictB.at(bot.serverLevel(), "#test:evict", i, 200) != null);
        }
        check("S5 有界（上限 4 ⇒ 留 4 条 / 淘汰 2 条 / 最旧两条消失=" + boundedOk + "）+ 确定性淘汰"
                        + "（两个实例留下同一批=" + deterministic + "；evicted=" + evictA.evictedTotal() + "）",
                boundedOk && deterministic);

        // ④ 记忆**不参与选点**：把记忆灌满后再扫一次，候选集必须与空记忆时逐字相同
        var memoryScan = source.newSession(spec, smallBudget, Integer.MAX_VALUE);
        int guard = 0;
        while (!memoryScan.done() && guard++ < 100_000) {
            memoryScan.advance(bot);
        }
        List<Long> withMemory = memoryScan.sets().get(0).viable().stream()
                .map(c -> c.anchor().asLong()).sorted().toList();
        check("S5 记忆**不参与选点**（灌记忆后再扫，候选集与先前逐字相同："
                        + withMemory.size() + " vs " + chunkedIds.size() + "）",
                withMemory.equals(chunkedIds));
        memory.setCapForTesting(savedCap);
        memory.clear();   // 收尾复位（记忆是全局单例 SavedData）

        // ---- ⭐ 1.5 作业区 / 意图（`D-329` §3）：意图只回答"在不在计划里"，**不替"能不能挖"下结论** ----
        // 判据要咬住用户点出的陷阱：**区内但实际不可挖**的候选必须保住**它自己的**理由码，
        // 不许被 `outside_work_area` 顶替（否则"挖不动"会被伪装成"不在计划里"）。
        // ⚠️ 层位取 `center.getY() - 1`：bot 自己那一层是空气，**石块在脚下那一层**
        // （首版取 `center.getY()` ⇒ 区内可行=0，判据当场红 —— 这就是"意图只是一层过滤"的直接证据）
        final var intent = com.dddgn.alice.job.mine.MineIntent
                .area(center, 2, center.getY() - 1, center.getY() - 1);
        final var intentSpec = com.dddgn.alice.job.GoalSpec.mineBlocks(center, r, 1, 600, intent);
        var intentSession = source.newSession(intentSpec, Integer.MAX_VALUE, Integer.MAX_VALUE);
        intentSession.advance(bot);
        var intentSet = intentSession.sets().get(0);
        long outsideRejected = intentSet.rejected().stream()
                .filter(x -> x.endsWith(com.dddgn.alice.job.mine.MineIntent.OUTSIDE)).count();
        long insideViable = intentSet.viable().stream()
                .filter(c -> intent.refusalFor(c.anchor()) == null).count();
        check("1.5 作业区生效：区外候选带 `outside_work_area`（区外被拒=" + outsideRejected
                        + "）· 区内可行=" + insideViable + "（>0）· 且**所有可行候选都在区内**",
                outsideRejected > 0 && insideViable > 0
                        && intentSet.viable().stream().allMatch(c -> intent.refusalFor(c.anchor()) == null));

        // ⭐⭐ 陷阱判据（用户 2026-09-20 点出）：**区内**也可能有"实际不可挖"的目标 ⇒
        // 它必须报**它自己的**理由（这里用**保护区**这条真实授权路径造），而区外那些报的是
        // **计划层**理由 `outside_work_area` ⇒ 两类理由同时可见、**互不顶替**。
        // ⚠️ 判据要**强**才可红：把**整卷扫描**都罩进保护区 ⇒ 一旦检查顺序反了（先算能不能挖），
        //    区外候选就会全被 `protected_area` 顶替 ⇒ `outsideByPlan` 归零 ⇒ 红。
        var zones = com.dddgn.alice.protection.SafeZoneData.get(bot.getServer());
        var dimension = bot.serverLevel().dimension().location();
        var claimsBefore = new java.util.HashSet<>(zones.claims(dimension));
        zones.claimCircle(bot.serverLevel(), center, 5);
        try {
            var protectedSession = source.newSession(intentSpec, Integer.MAX_VALUE, Integer.MAX_VALUE);
            protectedSession.advance(bot);
            var protectedSet = protectedSession.sets().get(0);
            long insideByAuthority = protectedSet.rejected().stream()
                    .filter(x -> x.endsWith(":protected_area")).count();
            long outsideByPlan = protectedSet.rejected().stream()
                    .filter(x -> x.endsWith(com.dddgn.alice.job.mine.MineIntent.OUTSIDE)).count();
            check("1.5 陷阱：区内**不可挖**的候选保住自己的理由码（区内 `protected_area`=" + insideByAuthority
                            + " >0 · 区外 `outside_work_area`=" + outsideByPlan
                            + " >0 且**一个都没被顶替**；整卷都在保护区内 ⇒ 顺序反了这条必红）",
                    insideByAuthority > 0 && outsideByPlan > 0);
        } finally {
            // 收尾必须还原：只放掉**本判据新认领**的区块（判据自己不留副作用）
            // ⚠️ 必须先**拷贝**：`claims(...)` 返回的是活集合的视图，边遍历边 unclaim 会 CME
            for (long key : new java.util.ArrayList<>(zones.claims(dimension))) {
                if (!claimsBefore.contains(key)) {
                    zones.unclaim(bot.serverLevel(),
                            net.minecraft.world.level.ChunkPos.getX(key),
                            net.minecraft.world.level.ChunkPos.getZ(key));
                }
            }
        }

        // 反向对照（行为）：意图指到**没有目标的远处** ⇒ 一个可行候选都不许有（证明意图真被消费）
        final var farIntent = com.dddgn.alice.job.mine.MineIntent.area(center.offset(1000, 0, 0), 2,
                Integer.MIN_VALUE, Integer.MAX_VALUE);
        var farSession = source.newSession(
                com.dddgn.alice.job.GoalSpec.mineBlocks(center, r, 1, 600, farIntent),
                Integer.MAX_VALUE, Integer.MAX_VALUE);
        farSession.advance(bot);
        check("1.5 反向对照：意图指到远处 ⇒ 可行候选=0（意图**真的**参与取舍，不是装饰）",
                farSession.sets().get(0).viable().isEmpty());
        // 正向对照：没有意图 ⇒ 与上面"无意图"那次逐字相同（意图不是隐形默认打开）
        var noneSession = source.newSession(
                com.dddgn.alice.job.GoalSpec.mineBlocks(center, r, 1, 600), Integer.MAX_VALUE, Integer.MAX_VALUE);
        noneSession.advance(bot);
        check("1.5 对照：`MineIntent.none()` ⇒ 候选集与基线逐字相同（意图默认关闭）",
                noneSession.sets().get(0).viable().stream().map(c -> c.anchor().asLong()).sorted().toList()
                        .equals(chunkedIds));

        // ---- ⭐ 目标簇（`D-329` §3 邻居；用户 2026-09-20：几何相连就是一簇，区块分割最多 +3 次搜索）----
        // 判据必须咬住两件事：① 簇判定**纯粹是几何**（不掺授权/可挖性/记忆）；② 超预算要**如实拆簇**且成员守恒。
        var chain = new java.util.ArrayList<net.minecraft.core.BlockPos>();
        for (int i = 0; i < 4; i++) {
            chain.add(new net.minecraft.core.BlockPos(100 + i, 62, 200));
        }
        var diagonal = java.util.List.of(new net.minecraft.core.BlockPos(0, 62, 0),
                new net.minecraft.core.BlockPos(1, 62, 1));
        var apart = java.util.List.of(new net.minecraft.core.BlockPos(0, 62, 0),
                new net.minecraft.core.BlockPos(9, 62, 9));
        var faceOnly = com.dddgn.alice.job.mine.TargetClusters.partition(diagonal,
                com.dddgn.alice.job.mine.TargetClusters.Connectivity.FACE, 3);
        var diagonalAll = com.dddgn.alice.job.mine.TargetClusters.partition(diagonal,
                com.dddgn.alice.job.mine.TargetClusters.Connectivity.DIAGONAL_26, 3);
        check("簇：面相连成一条 ⇒ 恰好 1 簇（实测 " + com.dddgn.alice.job.mine.TargetClusters.partition(chain).size() + "）"
                        + " · 隔开的 ⇒ 2 簇（实测 " + com.dddgn.alice.job.mine.TargetClusters.partition(apart).size() + "）"
                        + " · 对角相连：26 邻接=1 簇 / 面邻接=2 簇（实测 "
                        + diagonalAll.size() + " / " + faceOnly.size() + "）",
                com.dddgn.alice.job.mine.TargetClusters.partition(chain).size() == 1
                        && com.dddgn.alice.job.mine.TargetClusters.partition(apart).size() == 2
                        && diagonalAll.size() == 1 && faceOnly.size() == 2);

        // 跨区块边界 ⇒ **仍是一簇**，但如实记账"要多扫几个区块"
        var straddling = java.util.List.of(new net.minecraft.core.BlockPos(15, 62, 8),
                new net.minecraft.core.BlockPos(16, 62, 8));
        var straddleClusters = com.dddgn.alice.job.mine.TargetClusters.partition(straddling);
        check("簇：跨区块边界**不许**被切开（用户口径：区块分割只是搜索成本）；"
                        + straddleClusters.size() + " 簇 / chunks="
                        + straddleClusters.get(0).chunkCount() + " / extraSearches="
                        + straddleClusters.get(0).extraSearches() + " ≤ 3",
                straddleClusters.size() == 1 && straddleClusters.get(0).crossesChunkBoundary()
                        && straddleClusters.get(0).extraSearches() <= 3);

        // 超预算（一条横跨 6 个区块的相连链）⇒ **如实拆簇**，且**成员守恒**（一个都不许丢）
        var longChain = new java.util.ArrayList<net.minecraft.core.BlockPos>();
        for (int i = 0; i < 6 * 16; i++) {
            longChain.add(new net.minecraft.core.BlockPos(i, 62, 300));
        }
        var split = com.dddgn.alice.job.mine.TargetClusters.partition(longChain);
        int membersAfter = split.stream().mapToInt(com.dddgn.alice.job.mine.TargetClusters.Cluster::size).sum();
        boolean budgetOk = split.stream().allMatch(c -> c.extraSearches() <= 3);
        check("簇：跨 6 个区块的相连链 ⇒ 拆成 " + split.size() + " 簇，每簇 extraSearches ≤ 3（" + budgetOk
                        + "）且**成员守恒**（拆前 " + longChain.size() + " = 拆后 " + membersAfter + "）",
                split.size() > 1 && budgetOk && membersAfter == longChain.size());

        // 确定性：同一输入两次 ⇒ 逐字同结果（不许靠 HashSet 迭代序）
        var again = com.dddgn.alice.job.mine.TargetClusters.partition(longChain);
        check("簇：同一输入两次跑 ⇒ 簇数与每簇成员逐字相同（确定性）",
                again.size() == split.size() && java.util.stream.IntStream.range(0, split.size())
                        .allMatch(i -> again.get(i).members().equals(split.get(i).members())));

        // 真实场景：矿石场景扫出来的候选也要能被切成簇（不是只有合成坐标能跑）
        var oreScan = new com.dddgn.alice.job.mine.MineCandidateSource(
                com.dddgn.alice.job.mine.MineCandidateSource.Target.ofBlock(
                        net.minecraft.world.level.block.Blocks.IRON_ORE),
                com.dddgn.alice.job.mine.MineCandidateSource.SCAN_RADIUS)
                .newSession(com.dddgn.alice.job.GoalSpec.mineBlocks(center,
                        com.dddgn.alice.job.mine.MineCandidateSource.SCAN_RADIUS, 1, 600),
                        Integer.MAX_VALUE, Integer.MAX_VALUE);
        oreScan.advance(bot);
        var oreAnchors = oreScan.sets().get(0).viable().stream()
                .map(com.dddgn.alice.job.Candidate::anchor).toList();
        var oreClusters = com.dddgn.alice.job.mine.TargetClusters.partition(oreAnchors);
        int oreMembers = oreClusters.stream()
                .mapToInt(com.dddgn.alice.job.mine.TargetClusters.Cluster::size).sum();
        check("簇：真实矿石场景的候选也能切簇（候选=" + oreAnchors.size() + " ⇒ " + oreClusters.size()
                        + " 簇 / 成员=" + oreMembers + "，守恒=" + (oreMembers == oreAnchors.size()) + "）",
                !oreAnchors.isEmpty() && oreMembers == oreAnchors.size());

        // ---- ⑨ ⭐ 掉刻归因（`D-367`）：**量化**"一次成本选择"与"一次扫描分片"的耗时 ----
        // 背景（`docs/reviews/2026-09-20-mine-round3-root-cause.md` §2）：真机三次
        // `Can't keep up! … Running 2035/2632/2232ms or 40/52/44 ticks behind`（2026-09-20）。
        // 候选之一是 `D-363`：每次选择最多跑 K 次**完整规划器**（`nodes=2348 ms=69` 量级）。
        // 这里把数字量出来（离线可测，不需要客户端）；判据只做**数量级护栏**（>200ms = 单次选择
        // 就能吃掉整个 tick 预算），避免 flaky。
        var costSource = new com.dddgn.alice.job.mine.MineCandidateSource(
                com.dddgn.alice.job.mine.MineCandidateSource.Target.ofBlock(
                        net.minecraft.world.level.block.Blocks.IRON_ORE),
                com.dddgn.alice.job.mine.MineCandidateSource.SCAN_RADIUS);
        var costSpec = com.dddgn.alice.job.GoalSpec.mineBlocks(center,
                com.dddgn.alice.job.mine.MineCandidateSource.SCAN_RADIUS, 1, 600);
        long tShard = System.nanoTime();
        var costSession = costSource.newSession(costSpec, Integer.MAX_VALUE, Integer.MAX_VALUE);
        costSession.advance(bot);
        long shardMs = (System.nanoTime() - tShard) / 1_000_000L;
        var costCandidates = costSession.sets().get(0).viable();
        long tRefined = System.nanoTime();
        com.dddgn.alice.job.mine.PlanRefinedCostProvider.production()
                .estimate(bot, costSpec, costCandidates);
        long refinedMs = (System.nanoTime() - tRefined) / 1_000_000L;
        long tField = System.nanoTime();
        new com.dddgn.alice.job.mine.StandingCostField(4, 64).estimate(bot, costSpec, costCandidates);
        long fieldMs = (System.nanoTime() - tField) / 1_000_000L;
        BotLog.info("[MineMenu] D-367 tick耗时：候选={} · 选择(冷启动=摊销第1次)={}ms · 成本场only={}ms · "
                        + "扫描分片={}ms（tick 预算 50ms；真机掉刻 2035/2632/2232ms）",
                costCandidates.size(), refinedMs, fieldMs, shardMs);
        long tMenu = System.nanoTime();
        com.dddgn.alice.decision.CandidateMenu.build(bot);
        long menuMs = (System.nanoTime() - tMenu) / 1_000_000L;
        BotLog.info("[MineMenu] D-367 tick耗时②：候选菜单构建={}ms（真机每个 PROGRESS 事件都会重建快照，"
                        + "而快照含菜单 ⇒ 这是掉刻的主要嫌疑）", menuMs);
        // ⭐ `D-368` 摊销后：**同一提供者**的第二次选择走缓存 ⇒ 稳态耗时（① 的收益在这里）
        var amortizedProvider = com.dddgn.alice.job.mine.PlanRefinedCostProvider.production();
        amortizedProvider.estimate(bot, costSpec, costCandidates);
        long tWarm = System.nanoTime();
        amortizedProvider.estimate(bot, costSpec, costCandidates);
        long warmMs = (System.nanoTime() - tWarm) / 1_000_000L;
        BotLog.info("[MineMenu] D-368 tick耗时③：摊销后（热缓存）一次选择={}ms vs 冷启动={}ms "
                        + "（tick 预算 50ms）", warmMs, refinedMs);
        check("掉刻归因：一次选择(冷启动，即摊销精算第 1 次) 的耗时必须有界（实测 " + refinedMs + "ms ≤ 200ms）",
                refinedMs <= 200L);
        check("掉刻归因：摊销后（热缓存）一次选择的耗时必须回到 tick 预算附近（实测 " + warmMs + "ms ≤ 60ms）",
                warmMs <= 60L);
        check("掉刻归因：候选菜单构建耗时必须有界（实测 " + menuMs + "ms ≤ 200ms）", menuMs <= 200L);

        // ---- ⭐ 成本模型（`D-329` §2.2；用户 2026-09-20 三条裁定）----
        // 判据用**脚本化成本**（确定性，不依赖世界）：把"规则"与"事实"分开测（本项目一贯口径）。
        var lowOre = new com.dddgn.alice.job.Candidate(new net.minecraft.core.BlockPos(0, 62, 1), "block",
                java.util.Map.of("block", "minecraft:coal_ore", "d", "1.0"));
        var richOre = new com.dddgn.alice.job.Candidate(new net.minecraft.core.BlockPos(20, 62, 1), "block",
                java.util.Map.of("block", "minecraft:diamond_ore", "d", "20.0"));
        var twoKinds = new com.dddgn.alice.job.CandidateSet(java.util.List.of(lowOre, richOre),
                java.util.List.of());
        var scripted = com.dddgn.alice.job.mine.CandidateCostProvider.scripted(java.util.Map.of(
                lowOre.anchor().asLong(), 1.0D, richOre.anchor().asLong(), 11.0D));

        // ① 权重 0 ⇒ 价值项**完全不参与**（用户裁定其二：默认关闭）：近的低级矿胜出
        var w0 = new com.dddgn.alice.job.policy.CostOptimalPolicy(scripted,
                com.dddgn.alice.job.mine.MineCostConfig.of(0.0D));
        var pickW0 = w0.select(bot, spec, twoKinds);
        check("成本模型：权重 0 ⇒ 选**成本低**的（低价值近矿；实测 "
                        + pickW0.picked().feature("block") + "）· 价值项关闭=" + !w0.lastValueEnabled(),
                "minecraft:coal_ore".equals(pickW0.picked().feature("block")) && !w0.lastValueEnabled());

        // ② 权重小 ⇒ **仍然选近的**（用户裁定其三：不许无条件挖最高级矿）
        var small = new com.dddgn.alice.job.policy.CostOptimalPolicy(scripted,
                com.dddgn.alice.job.mine.MineCostConfig.of(3.0D));
        var pickSmall = small.select(bot, spec, twoKinds);
        check("成本模型：价值权重大于路程差之前**必须仍选近的**（w=3，钻石价值优势=3×(1−1/3)=2 < 10 ⇒ 选 "
                        + pickSmall.picked().feature("block") + "）",
                "minecraft:coal_ore".equals(pickSmall.picked().feature("block"))
                        && small.lastValueEnabled());

        // ③ 权重压过路程差 ⇒ 才愿意绕路（同一候选集，只有权重变）
        var big = new com.dddgn.alice.job.policy.CostOptimalPolicy(scripted,
                com.dddgn.alice.job.mine.MineCostConfig.of(20.0D));
        var pickBig = big.select(bot, spec, twoKinds);
        check("成本模型：权重压过路程差 ⇒ 才选贵的（w=20，优势=20×(1−1/3)=13.3 > 10 ⇒ 选 "
                        + pickBig.picked().feature("block") + "；同一候选集只有权重变）",
                "minecraft:diamond_ore".equals(pickBig.picked().feature("block")));

        // ④ 单种类任务 ⇒ 价值项**完全惰性**（用户裁定其二）
        var oneKind = new com.dddgn.alice.job.CandidateSet(java.util.List.of(lowOre), java.util.List.of());
        var single = new com.dddgn.alice.job.policy.CostOptimalPolicy(scripted,
                com.dddgn.alice.job.mine.MineCostConfig.of(20.0D));
        single.select(bot, spec, oneKind);
        check("成本模型：**单种类**任务里价值项必须惰性（kinds=" + single.lastKindCount()
                        + " valueOn=" + single.lastValueEnabled() + "；极端权重也不生效）",
                single.lastKindCount() == 1 && !single.lastValueEnabled());

        // ⑤ 价值表：**未登记 = 0**（不猜），已登记按档位归一化；真实方块走标签解析
        double diamond = com.dddgn.alice.job.mine.MineValueTable.normalized(
                net.minecraft.world.level.block.Blocks.DIAMOND_ORE.defaultBlockState());
        double coal = com.dddgn.alice.job.mine.MineValueTable.normalized(
                net.minecraft.world.level.block.Blocks.COAL_ORE.defaultBlockState());
        double plainStone = com.dddgn.alice.job.mine.MineValueTable.normalized(
                net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
        check("价值表：标签解析生效（钻石=" + diamond + " > 煤=" + coal + "），未登记方块 = 0（石头="
                        + plainStone + "，**不猜**为低级）",
                diamond > coal && coal > 0.0D && plainStone == 0.0D);

        // ⑥ 成本场**一次性**（用户裁定其三）：同一策略连选两次 ⇒ 两次都重新估算（计数不得为 0 且递增）
        var counting = new java.util.concurrent.atomic.AtomicInteger();
        com.dddgn.alice.job.mine.CandidateCostProvider countingProvider = (serverPlayer, goalSpec, list) -> {
            counting.incrementAndGet();
            return new com.dddgn.alice.job.mine.CandidateCostProvider.Result(java.util.Map.of(
                    lowOre.anchor().asLong(), 1.0D, richOre.anchor().asLong(), 11.0D), list.size(), "counting");
        };
        var countingPolicy = new com.dddgn.alice.job.policy.CostOptimalPolicy(countingProvider,
                com.dddgn.alice.job.mine.MineCostConfig.of(0.0D));
        countingPolicy.select(bot, spec, twoKinds);
        countingPolicy.select(bot, spec, twoKinds);
        check("成本模型：成本读数**每次选择重算**（不跨选择缓存；实测估算次数=" + counting.get() + " 应为 2）"
                        + "，且 `estimatedCells` 有值（有界性可测="
                        + countingPolicy.lastResult().estimatedCells() + "）",
                counting.get() == 2 && countingPolicy.lastResult().estimatedCells() > 0);

        // ⑦ 真成本场（生产路径）能跑通且有界：真实世界 + 真站位点枚举
        var realPolicy = new com.dddgn.alice.job.policy.CostOptimalPolicy(
                new com.dddgn.alice.job.mine.StandingCostField(4, 64),
                com.dddgn.alice.job.mine.MineCostConfig.of(0.0D));
        var oreCandidates = new com.dddgn.alice.job.CandidateSet(
                oreScan.sets().get(0).viable(), java.util.List.of());
        var realPick = realPolicy.select(bot, spec, oreCandidates);
        check("成本模型：真成本场在生产路径上跑得通（候选=" + oreCandidates.viable().size()
                        + " 选中=" + (realPick.picked() == null ? "none" : realPick.picked().id())
                        + " cells=" + realPolicy.lastResult().estimatedCells() + " ≤ 64）",
                realPick.picked() != null && realPolicy.lastResult().estimatedCells() <= 64
                        && realPolicy.lastResult().estimatedCells() > 0);

        // ⑧ 簇队列（纯函数）：同簇成员连续、选中的排第一、单格簇不改变行为
        var clusterAnchors = java.util.List.of(new net.minecraft.core.BlockPos(0, 62, 0),
                new net.minecraft.core.BlockPos(1, 62, 0), new net.minecraft.core.BlockPos(2, 62, 0),
                new net.minecraft.core.BlockPos(30, 62, 0));
        var queue = com.dddgn.alice.job.mine.TargetClusters.queueFor(clusterAnchors,
                new net.minecraft.core.BlockPos(1, 62, 0));
        check("簇消费：队列 = 同簇成员且**选中的排第一**（实测 " + queue.size() + " 个："
                        + queue.stream().map(p -> p.getX() + "").toList() + "；远端不同簇的**不在**队列里）",
                queue.size() == 3 && queue.get(0).getX() == 1 && queue.stream().noneMatch(p -> p.getX() == 30));

        // ⑧b `D-364`：**簇内顺序按图距** —— 真机实测「挖一半突然跑出几格又跑回来」。
        // ⚠️ 判据必须有判别力：`Cluster.members` 本身就是 (y,x,z) 排序 ⇒ 旧顺序 = **层优先坐标序**
        // （先挖完整层、含同层 3 格外的，再进下一层）。所以这里用一个"T 形"簇：
        //   A(0,0) 有两条路 —— 沿 z 的链 (0,1)(0,2)(0,3)(0,4)，以及**紧邻**的 N(1,0)。
        //   层优先坐标序会把 x=0 的都排前面 ⇒ 先走到 (0,4) 才回头到 N（图距 4 → 1，**来回横跳**）；
        //   图距序（BFS，同距按 y→x→z）⇒ N 紧跟 (0,1) 之后。断言**精确序列**。
        var zigzag = java.util.List.of(new net.minecraft.core.BlockPos(0, 62, 0),
                new net.minecraft.core.BlockPos(0, 62, 1), new net.minecraft.core.BlockPos(0, 62, 2),
                new net.minecraft.core.BlockPos(0, 62, 3), new net.minecraft.core.BlockPos(0, 62, 4),
                new net.minecraft.core.BlockPos(1, 62, 0));
        var expectedOrder = java.util.List.of(new net.minecraft.core.BlockPos(0, 62, 0),
                new net.minecraft.core.BlockPos(0, 62, 1), new net.minecraft.core.BlockPos(1, 62, 0),
                new net.minecraft.core.BlockPos(0, 62, 2), new net.minecraft.core.BlockPos(0, 62, 3),
                new net.minecraft.core.BlockPos(0, 62, 4));
        var ordered = com.dddgn.alice.job.mine.TargetClusters.queueFor(zigzag,
                new net.minecraft.core.BlockPos(0, 62, 0));
        check("簇消费：**按图距排序**（T 形簇：层优先坐标序 = 0,0,1→0,0,4→1,0（图距 0,1,2,3,4,**1** 来回横跳）；"
                        + "期望图距序 0,0,0 → 0,0,1 → 1,0,0 → 0,0,2 …；实测 "
                        + ordered.stream().map(p -> p.getX() + "," + p.getZ()).toList() + "）",
                ordered.equals(expectedOrder));
        BotLog.info("[MineMenu] D-364 簇内顺序：T 形簇（A 沿 z 的链 + 紧邻 N）⇒ 层优先坐标序会先走到"
                        + " z=4 再回头到 N；图距序实测={}（同距按 y→x→z）",
                ordered.stream().map(p -> p.getX() + "," + p.getZ()).toList());

        // **判别性事实**（判据绿了也要能复核数字；红了更要能看出差在哪）
        BotLog.info("[MineMenu] S3/S4 判别性事实：分片 calls={} 单次最大={}（上限={}）visited={}/{} "
                        + "读={} 未扫={} · 合并==全量: {}（分片 {} 条 / 全量 {} 条）· "
                        + "截断 visited={} truncated={} done={} not_found={} · 真实矿石簇={}（成员={}）",
                calls, session.maxCallVisited(), smallBudget, session.visited(), volume,
                session.reads(), session.unscanned(), oneShotIds.equals(chunkedIds),
                chunkedIds.size(), oneShotIds.size(), truncated.visited(), truncated.truncated(),
                truncated.done(),
                truncated.sets().get(0).rejected().stream().filter(x -> x.contains("not_found")).count(),
                oreClusters.size(), oreMembers);
    }

    /** 用**同一份**菜单断言拒绝（菜单构建含 11 个矿石目标的全扫，重复构建会在一个 tick 里白烧掉百万次读）。 */
    private void checkRefused(CandidateMenu menu, String what, String json) {
        GoalAction action = GoalAction.parse(json, bot, menu);
        if (!(action instanceof GoalAction.Refused)) {
            failures.add(what + " 应 Refused，实际 " + action);
        }
    }
}
