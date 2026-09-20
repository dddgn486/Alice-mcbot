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
