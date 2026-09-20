package com.dddgn.alice.task;

import com.dddgn.alice.action.BlockBreakSession;
import com.dddgn.alice.action.WriteGrant;
import com.dddgn.alice.action.WriteReason;
import com.dddgn.alice.bot.BotManager;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.compat.ftbteams.FtbCommandRunner;
import com.dddgn.alice.compat.ftbteams.FtbTeamsBridge;
import com.dddgn.alice.log.BotLog;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * **被拒绝的破坏不许被记成成功**自检（`D-323`）—— 电池步 `break_refused`。
 *
 * <p><b>为什么有这一步（真机发现，2026-09-18）</b>：用户第一次用 FTB 队伍联动实测时看到
 * "在队里能挖领地内的方块、退队就不行"——**观感是对的，而我们的日志是错的**：退队后 4 次破坏
 * （`31,63,217` / `30,63,218` / `30,63,219` / `28,63,220`，全在玩家的认领区块内）都打了
 * `block_break_done` + `MineTask terminal=COMPLETED` + `WriteBudget breaks=1/64`，
 * 而**存档里那 4 格仍是 `minecraft:dirt`**。根因 = `BlockBreakSession` 在 `gameMode.destroyBlock(...)`
 * 之后**不验证世界事实**就宣布 DONE（返回值也丢了）⇒ 任何"取消破坏"的来源（FTB 认领、别的保护模组、
 * 事件层取消）都会被记成成功，决策层连一个失败码都拿不到。
 *
 * <p><b>这一步钉住的四条事实</b>（世界是唯一真相，全部读方块而不是读日志）：
 * <ol>
 *   <li><b>对照（防"永远红"）</b>：普通生存模式挖一块自己放的泥土 ⇒ 必须 `DONE` 且方块**真的变空气**；</li>
 *   <li><b>确定性负例（不依赖任何模组）</b>：切到**冒险模式**（空手 ⇒ 原版 `blockActionRestricted` 拦下）
 *       ⇒ 必须 `FAILED` + 失败码 `REFUSED` + 方块**原地不动**；</li>
 *   <li><b>真机同因（FTB 在場时）</b>：让一只**别的队伍**的假人认领这一区块（`/ftbchunks claim`），
 *       会话假人再去挖 ⇒ 同样必须 `FAILED` + `REFUSED` + 方块不动。⚠️ 这条**自带前提校验**：
 *       若认领没生效，方块就会被挖掉 ⇒ 判据自己变红（不会假绿）；FTB 不在场 ⇒ 整组 `ftb=skip`；</li>
 *   <li><b>自清理</b>：目标格还原、游戏模式还原成生存、认领撤销、探针拆掉。</li>
 * </ol>
 *
 * <p>目标格是**现场选的**（同区块的邻格 + 空气 + 可触及），进入前记下原状、结束时精确还原
 * ——夹具自己摆起点与收尾，不依赖前序步骤把哪块地留成什么样。
 */
public final class BreakRefusedCheckTask implements Task {

    /** 三个用例各约 50 tick（手挖泥土）+ 余量。 */
    private static final int BUDGET_TICKS = 600;

    /** 单个用例的破坏上限：手挖泥土实测 ≈50 tick，给到 120 足够（超时也能区分"挖不动"与"被拒绝"）。 */
    private static final int CASE_BREAK_TICKS = 120;

    private static final String PROBE_NAME = "ClaimProbe";

    /** 目标方块类型（对照判据用对象比较，不用 `isDirt()` 的间接语义）。 */
    private static final Block DIRT_BLOCK = Blocks.DIRT;

    /** 固定 UUID ⇒ 每轮复用同一个 FTB 身份（不堆个人队文件）。 */
    private static final UUID PROBE_UUID =
            UUID.nameUUIDFromBytes("alice-claim-probe".getBytes(StandardCharsets.UTF_8));

    private enum Phase { GUARD, CONTROL, ADVENTURE, FTB_CLAIM, BULK_CONTROL, CLEANUP, DONE }

    private final BotPlayer bot;
    private final ServerPlayer observer;
    private final List<String> failures = new ArrayList<>();

    private Phase phase = Phase.GUARD;
    private int ticks;
    private int checks;
    private boolean done;

    /** 现场选定的目标格 + 它进入前的样子（收尾精确还原）。 */
    private BlockPos target;
    private BlockState targetBefore;

    private BlockBreakSession session;
    private int caseTicks;
    private String caseLabel = "";

    private BotPlayer probe;
    private boolean probeClaimed;
    private String ftbSkipReason = "";
    private String controlCode = "";
    private String adventureCode = "";
    private String ftbCode = "";

    public BreakRefusedCheckTask(BotPlayer bot, ServerPlayer observer) {
        this.bot = bot;
        this.observer = observer;
    }

    @Override
    public String taskName() {
        return "BreakRefusedCheck";
    }

    @Override
    public TaskTarget target() {
        return bot == null ? TaskTarget.block(BlockPos.ZERO) : TaskTarget.block(bot.blockPosition());
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
        if (++ticks > BUDGET_TICKS) {
            check("自检必须在预算内跑完（" + BUDGET_TICKS + " tick）", false);
            return finish();
        }
        switch (phase) {
            case GUARD -> guardPhase();
            case CONTROL -> controlPhase();
            case ADVENTURE -> adventurePhase();
            case FTB_CLAIM -> ftbClaimPhase();
            case BULK_CONTROL -> bulkControlPhase();
            case CLEANUP -> cleanupPhase();
            case DONE -> {
                return finish();
            }
            default -> {
            }
        }
        return Task.Status.RUNNING;
    }

    // ==================== 阶段 ====================

    /** 前提 + 现场选目标格（同区块、空气、可触及），并记下原状用于还原。 */
    private void guardPhase() {
        check("前提：本步需要一只会话假人", bot != null);
        check("前提：本步需要观察者玩家（报告要发给一个人看）", observer != null);
        if (bot == null || observer == null) {
            advance(Phase.DONE);
            return;
        }
        ServerLevel level = bot.serverLevel();
        bot.controller().stopMovement();
        bot.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);

        target = pickTarget();
        check("前提：找到一格可用的目标（同区块 / 当前是空气 / 够得着）（实际 "
                        + (target == null ? "无" : target.toShortString()) + "）",
                target != null);
        if (target == null) {
            advance(Phase.CLEANUP);
            return;
        }
        targetBefore = level.getBlockState(target);
        check("前提：目标格起点是空气（下面每例都自己放方块，不影响现场）（实际 "
                + targetBefore.getBlock().getName().getString() + "）", targetBefore.isAir());
        // ⭐ D-323 附注一：被拒绝**不该白花重试预算**（用户实测：原来同一目标要试 3 次才失败）。
        // 判据直接钉"硬拒绝名单"的形状：命中 ⇒ 任务层立刻升级失败（不重规划）；可重试码**不许**混进去。
        check("⑤ 策略：`BREAK_REFUSED` 必须算「硬拒绝」（重试这个目标没有意义 ⇒ 不再白花 "
                        + "MAX_RECOVERY_ATTEMPTS 次重规划）（实际 hardRefusal="
                        + MineTask.isHardTargetRefusal("BREAK_REFUSED") + "）",
                MineTask.isHardTargetRefusal("BREAK_REFUSED"));
        check("⑤ 反向对照：可重试的失败码（`BREAK_PROGRESS_TIMEOUT` / `OUT_OF_REACH`）**不许**被算成硬拒绝"
                        + "（否则这条谓词退化成「永远 true」）",
                !MineTask.isHardTargetRefusal("BREAK_PROGRESS_TIMEOUT")
                        && !MineTask.isHardTargetRefusal("OUT_OF_REACH"));

        // ⭐ `D-359`：**被拒绝**这件事必须一路走到**顶层码**，不许在 Job 层退化成"没矿"
        // （`D-323` 附注一的第二层：第一层是"没发生的破坏被记成成功"，第二层是"被拒绝的破坏被记成没矿"）。
        java.util.List<String> allRefused = java.util.List.of("BREAK_REFUSED", "BREAK_REFUSED");
        String refusedTop = com.dddgn.alice.job.mine.MineJob.attributeFailure("no_reachable_candidate", allRefused);
        check("⑥ 归因：**每一次**破坏都被世界侧拒绝 ⇒ 顶层码必须是 `world_refused`（实测 " + refusedTop
                        + "）；不许退化成 `no_reachable_candidate`（那会让真机里读成『这里没矿』）",
                "world_refused".equals(refusedTop));
        String mixedTop = com.dddgn.alice.job.mine.MineJob.attributeFailure("no_reachable_candidate",
                java.util.List.of("BREAK_REFUSED", "no_suitable_tool"));
        check("⑥ 反向对照：混合原因（被拒 + 缺工具）⇒ **保持**总括码（不许挑一个家庭硬说成单一原因；实测 "
                        + mixedTop + "）", "no_reachable_candidate".equals(mixedTop));
        String budgetTop = com.dddgn.alice.job.mine.MineJob.attributeFailure("no_reachable_candidate",
                java.util.List.of("WRITE_BUDGET_EXHAUSTED"));
        check("⑥ 既有家庭没被新家庭抢走：全预算耗尽 ⇒ 仍是 `write_budget_exhausted`（实测 " + budgetTop + "）",
                "write_budget_exhausted".equals(budgetTop));
        String otherBase = com.dddgn.alice.job.mine.MineJob.attributeFailure("search_incomplete", allRefused);
        check("⑥ 非总括基础码**逐字返回**（`search_incomplete` + 全被拒 ⇒ 仍是 " + otherBase
                        + "；S3 的『搜索受限』不许被拒绝归因盖掉）", "search_incomplete".equals(otherBase));        advance(Phase.CONTROL);
    }

    /** ① 对照：普通生存模式挖掉自己放的泥土 ⇒ 必须真成功（否则后面的"被拒绝"判据没有意义）。 */
    private void controlPhase() {
        if (caseTicks == 0) {
            caseLabel = "对照（生存模式）";
            placeTarget();
            session = BlockBreakSession.begin(bot, bot.serverLevel(), target);
            caseTicks = 1;
            return;
        }
        if (!tickCase()) {
            return;
        }
        BlockState now = bot.serverLevel().getBlockState(target);
        controlCode = codeOf(session);
        check("① 对照：生存模式必须**真挖掉**（status=" + statusOf(session) + " 失败码=" + controlCode
                        + " 方块现在=" + now.getBlock().getName().getString() + "）",
                session.status() == BlockBreakSession.Status.DONE && now.isAir());
        advance(Phase.ADVENTURE);
    }

    /** ② 确定性负例：冒险模式（空手 ⇒ 原版限制）⇒ 必须明确失败且方块原地不动。 */
    private void adventurePhase() {
        if (caseTicks == 0) {
            caseLabel = "负例（冒险模式）";
            clearTarget();
            placeTarget();
            bot.gameMode.changeGameModeForPlayer(GameType.ADVENTURE);
            session = BlockBreakSession.begin(bot, bot.serverLevel(), target);
            caseTicks = 1;
            return;
        }
        if (!tickCase()) {
            return;
        }
        BlockState now = bot.serverLevel().getBlockState(target);
        adventureCode = codeOf(session);
        check("② ⭐ 冒险模式必须**被拒绝**：status=" + statusOf(session) + " 失败码=" + adventureCode
                        + "（期望 FAILED/REFUSED）",
                session.status() == BlockBreakSession.Status.FAILED && "REFUSED".equals(adventureCode));
        check("② ⭐ 被拒绝时**方块必须原地不动**（这就是「不许把没发生的事记成成功」的世界事实）"
                        + "（实际 " + now.getBlock().getName().getString() + "）", !now.isAir());
        bot.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
        advance(Phase.FTB_CLAIM);
    }

    /** ③ 真机同因：别人认领的区块（FTB）——同一个判据，但由模组来拦。 */
    private void ftbClaimPhase() {
        if (caseTicks == 0) {
            caseLabel = "负例（FTB 认领）";
            if (!FtbTeamsBridge.available() || !FtbCommandRunner.hasCommandRoot(bot, "ftbchunks")) {
                ftbSkipReason = FtbTeamsBridge.available() ? "ftbchunks_absent" : FtbTeamsBridge.unavailableReason();
                BotLog.warn("[BreakRefused] FTB 认领用例跳过：{}（本组判据不成立，不假绿也不假红）", ftbSkipReason);
                advance(Phase.CLEANUP);
                return;
            }
            probe = findOrSpawnProbe();
            check("③ 前提：另一只假人已就位（它来当「认领者」）（实际 "
                    + (probe == null ? "null" : probe.getName().getString()) + "）", probe != null);
            if (probe == null) {
                ftbSkipReason = "probe_spawn_failed";
                advance(Phase.CLEANUP);
                return;
            }
            FtbCommandRunner.Outcome claim = FtbCommandRunner.runAs(probe, "ftbchunks claim");
            probeClaimed = claim.ok();
            BotLog.info("[BreakRefused] 探针 {} 认领所在区块：ok={} {}", probe.getName().getString(),
                    probeClaimed, claim.text());
            check("③ 前提：探针所在区块认领成功（这格正是我们的目标格所在区块）", probeClaimed);
            if (!probeClaimed) {
                advance(Phase.CLEANUP);
                return;
            }
            clearTarget();
            placeTarget();
            session = BlockBreakSession.begin(bot, bot.serverLevel(), target);
            caseTicks = 1;
            return;
        }
        if (!tickCase()) {
            return;
        }
        BlockState now = bot.serverLevel().getBlockState(target);
        ftbCode = codeOf(session);
        boolean sameTeam = probe != null && FtbTeamsBridge.sameTeam(bot, probe);
        check("③ 前提：会话假人与认领者**不同队**（否则这条判据是假绿）（实际 sameTeam=" + sameTeam + "）",
                !sameTeam);
        check("③ ⭐ FTB 拦下的破坏必须**被拒绝**：status=" + statusOf(session) + " 失败码=" + ftbCode
                        + "（期望 FAILED/REFUSED）",
                session.status() == BlockBreakSession.Status.FAILED && "REFUSED".equals(ftbCode));
        check("③ ⭐ 被 FTB 拒绝时方块必须原地不动（真机实测：退队后 4 格全是 dirt 而我们记了 done）"
                        + "（实际 " + now.getBlock().getName().getString() + "）", !now.isAir());
        // ④（D-326）**批量写路径**：道路施工/清障走 `placeBulkEdit` / `breakForBulkEdit`，
        // 而它们**不触发 Forge 的放置/破坏事件**（`Level.destroyBlock` 已反汇编核实无 BreakEvent 引用）
        // ⇒ FTB 的认领对它们本来完全不可见（自方闸门仍在）。收口后必须**如实拒绝**。
        ServerLevel lvl = bot.serverLevel();
        // ⚠️ 判据写成「**与尝试前一致**」而不是「仍是空气」：③ 那一例被拒后目标格本来就可能留有方块
        //    （第一版就是在这里假红：断言 isAir 而实际是上一例留下的 Dirt）。
        BlockState beforeBulk = lvl.getBlockState(target);
        boolean bulkPlaced = com.dddgn.alice.action.BlockInteraction.placeBulkEdit(bot, lvl, target,
                Blocks.DIRT.defaultBlockState(), WriteGrant.of(taskName(), WriteReason.BULK_EDIT));
        check("④ ⭐ FTB 认领内**批量放置**必须被拒（道路施工走的正是这条路，它对 Forge 事件不可见）"
                        + "（返回=" + bulkPlaced + " 尝试前=" + beforeBulk.getBlock().getName().getString()
                        + " 尝试后=" + lvl.getBlockState(target).getBlock().getName().getString() + "）",
                !bulkPlaced && lvl.getBlockState(target).equals(beforeBulk));
        lvl.setBlock(target, Blocks.DIRT.defaultBlockState(), 3);   // 夹具直接摆一块，用来试批量破坏
        boolean bulkBroke = com.dddgn.alice.action.BlockInteraction.breakForBulkEdit(bot, lvl, target, false,
                WriteGrant.of(taskName(), WriteReason.BULK_EDIT));
        check("④ ⭐ FTB 认领内**批量破坏**必须被拒（走 `Level.destroyBlock` ⇒ 不触发 Forge 破坏事件）"
                        + "（返回=" + bulkBroke + " 方块现在=" + lvl.getBlockState(target).getBlock().getName().getString()
                        + "）",
                !bulkBroke && !lvl.getBlockState(target).isAir());
        advance(Phase.BULK_CONTROL);
    }

    /** ⑤ 对照（防"永远拒"）：**撤销认领之后**同一组批量路径必须真的能写、且世界真的变了。 */
    private void bulkControlPhase() {
        if (caseTicks == 0) {
            caseLabel = "对照（撤销认领后批量写）";
            caseTicks = 1;
            if (probe != null && probeClaimed) {
                FtbCommandRunner.Outcome unclaim = FtbCommandRunner.runAs(probe, "ftbchunks unclaim");
                probeClaimed = !unclaim.ok();
                check("⑤ 前提：探针撤销认领成功（否则下面的「能写」可能只是没认领过）", !probeClaimed);
                BotLog.info("[BreakRefused] 探针撤销认领：ok={} {}", unclaim.ok(), unclaim.text());
            }
            ServerLevel lvl = bot.serverLevel();
            clearTarget();
            boolean bulkPlaced = com.dddgn.alice.action.BlockInteraction.placeBulkEdit(bot, lvl, target,
                    Blocks.DIRT.defaultBlockState(), WriteGrant.of(taskName(), WriteReason.BULK_EDIT));
            check("⑤ 对照：**无认领**时批量放置必须真的落地（返回=" + bulkPlaced + " 方块现在="
                            + lvl.getBlockState(target).getBlock().getName().getString() + "）",
                    bulkPlaced && lvl.getBlockState(target).is(DIRT_BLOCK));
            boolean bulkBroke = com.dddgn.alice.action.BlockInteraction.breakForBulkEdit(bot, lvl, target, false,
                    WriteGrant.of(taskName(), WriteReason.BULK_EDIT));
            check("⑤ 对照：**无认领**时批量破坏必须真的生效（返回=" + bulkBroke + " 方块现在="
                            + lvl.getBlockState(target).getBlock().getName().getString() + "）",
                    bulkBroke && lvl.getBlockState(target).isAir());
            advance(Phase.CLEANUP);
            return;
        }
        advance(Phase.CLEANUP);
    }

    /** 自清理：目标格还原、模式还原、认领撤销、探针拆掉。 */
    private void cleanupPhase() {
        if (bot != null) {
            ServerLevel level = bot.serverLevel();
            if (target != null && targetBefore != null) {
                level.setBlock(target, targetBefore, 3);
                check("自清理：目标格还原成进入前的样子（" + targetBefore.getBlock().getName().getString() + "）",
                        level.getBlockState(target).equals(targetBefore));
            }
            bot.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
            bot.controller().stopMovement();
        }
        if (probe != null) {
            if (probeClaimed) {
                FtbCommandRunner.Outcome unclaim = FtbCommandRunner.runAs(probe, "ftbchunks unclaim");
                BotLog.info("[BreakRefused] 探针撤销认领：ok={} {}", unclaim.ok(), unclaim.text());
            }
            BotManager.remove(probe);
            check("自清理：认领探针已从玩家列表移除",
                    bot == null || bot.getServer().getPlayerList().getPlayer(probe.getUUID()) == null);
            // `remove` 会清掉世界存档里的 botTag ⇒ 把**会话 bot** 的记录写回去（否则会污染后续步/重启恢复）
            BotManager.saveToWorld(bot);
        }
        advance(Phase.DONE);
        finish();
    }

    // ==================== 工具 ====================

    /** 推进当前用例的破坏会话；true = 该用例已结束。 */
    private boolean tickCase() {
        caseTicks++;
        if (session == null) {
            return true;
        }
        BlockBreakSession.Status status = session.tick();
        if (status != BlockBreakSession.Status.IN_PROGRESS) {
            return true;
        }
        if (caseTicks > CASE_BREAK_TICKS) {
            BotLog.warn("[BreakRefused] 用例「{}」在 {} tick 内没有终态 ⇒ 计为失败", caseLabel, CASE_BREAK_TICKS);
            return true;
        }
        return false;
    }

    /** 现场选目标：同区块的四个邻格里第一个"空气 + 够得着"的（保证 FTB 认领区块覆盖它）。 */
    private BlockPos pickTarget() {
        BlockPos foot = bot.blockPosition();
        int chunkX = foot.getX() >> 4;
        int chunkZ = foot.getZ() >> 4;
        List<BlockPos> candidates = List.of(
                foot.offset(1, 0, 0), foot.offset(-1, 0, 0), foot.offset(0, 0, 1), foot.offset(0, 0, -1));
        for (BlockPos candidate : candidates) {
            if ((candidate.getX() >> 4) != chunkX || (candidate.getZ() >> 4) != chunkZ) {
                continue;   // 跨区块 ⇒ FTB 认领用例会失去前提
            }
            if (bot.serverLevel().getBlockState(candidate).isAir()
                    && com.dddgn.alice.action.BlockInteraction.reachable(bot, candidate)) {
                return candidate.immutable();
            }
        }
        return null;
    }

    private void placeTarget() {
        bot.serverLevel().setBlock(target, Blocks.DIRT.defaultBlockState(), 3);
    }

    private void clearTarget() {
        bot.serverLevel().setBlock(target, Blocks.AIR.defaultBlockState(), 3);
    }

    /** 复用同名残留探针，否则新建（固定 UUID ⇒ 每轮同一个 FTB 身份）。 */
    private BotPlayer findOrSpawnProbe() {
        for (BotPlayer candidate : BotManager.getAllBots()) {
            if (PROBE_NAME.equals(candidate.getName().getString())) {
                return candidate;
            }
        }
        return BotManager.spawn(bot.serverLevel(), bot.blockPosition(), PROBE_NAME, PROBE_UUID, null);
    }

    private static String statusOf(BlockBreakSession session) {
        return session == null ? "无会话" : session.status().toString();
    }

    private static String codeOf(BlockBreakSession session) {
        return session == null ? "" : session.failureCode();
    }

    private Task.Status finish() {
        if (done) {
            return failures.isEmpty() ? Task.Status.DONE : Task.Status.FAILED;
        }
        done = true;
        boolean pass = failures.isEmpty();
        BotLog.info("[BreakRefused] SUMMARY checks={} failures={} {} → {}（对照={} 冒险={} FTB={} ftb={}）",
                checks, failures.size(), failures, pass ? "PASS" : "FAIL",
                controlCode.isEmpty() ? "-" : controlCode,
                adventureCode.isEmpty() ? "-" : adventureCode,
                ftbCode.isEmpty() ? "-" : ftbCode,
                ftbSkipReason.isEmpty() ? "ran" : "skip(" + ftbSkipReason + ")");
        if (observer != null && !observer.hasDisconnected() && !observer.isRemoved()) {
            observer.sendSystemMessage(Component.literal(
                    "[alice] 破坏被拒自检 " + (pass ? "PASS" : "FAIL " + failures)));
        }
        return pass ? Task.Status.DONE : Task.Status.FAILED;
    }

    /** 进任何阶段都把用例计时清零（`caseTicks == 0` 是"本用例第一 tick"的信号）。 */
    private void advance(Phase next) {
        phase = next;
        caseTicks = 0;
        session = null;
    }

    private void check(String what, boolean ok) {
        checks++;
        if (!ok) {
            failures.add(what);
        }
    }
}
