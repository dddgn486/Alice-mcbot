package com.dddgn.alice.task;

import com.dddgn.alice.bot.BotManager;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.bot.FakeConnection;
import com.dddgn.alice.log.BotLog;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * **两只假人挨着 ⇒ 不许互相转发到爆栈**（D-320）—— 电池步 {@code bot_pair_no_recurse}。
 *
 * <p>它钉的是一次**真实客户端崩溃**：`FakeConnection.send()` 会把服务端发给这只假人的**旋转包**
 * 再广播给"追踪它的玩家"，而**别的假人也在追踪者名单里** ⇒ 两只假人挨着时
 * `A.send → broadcast → B.send → broadcast → A.send …` 无限递归 ⇒
 * `java.lang.StackOverflowError: Sending packet`（2026-09-18 客户端实测：`demo` 与 `tango` 相隔 **1 格**，
 * 生成 2 秒后开爆，日志里 84,892 帧）。
 *
 * <p>判据（三条互补，任何一条单独都抓不全）：
 * <ol>
 *   <li><b>确实转发过</b>（`relay ≥ 1`）—— 否则本步是**假绿**（比如旋转包根本没发出去）；</li>
 *   <li><b>转发有界</b>（`relay ≤ 旋转 tick 数 + 余量`）—— 递归会让它爆掉（或先把服务端弄死）；</li>
 *   <li>⭐ <b>闸真的生效</b>（`suppressed ≥ 1`）—— "转发过程中收到的旋转包"必须被**丢弃**，
 *       光靠"有界"抓到的是症状，这条抓的是机制。</li>
 * </ol>
 *
 * <p>⚠️ **会写世界存档**（`spawn` 生成即写；`remove` 会清 `botTag`）⇒ 与 `death_kill_bot` 同类，
 * 放 **EXTRA** 且**只适合 `single:bot_pair_no_recurse` 单独跑**；收尾会把会话 bot 的记录写回去。
 */
public final class BotPairNoRecurseCheckTask implements Task {

    private static final int BUDGET_TICKS = 200;
    /** 转多少 tick（每 tick 注入一个旋转包）。 */
    private static final int ROTATE_TICKS = 40;
    private static final String PROBE_NAME = "PairProbe";

    private enum Phase { SPAWN, ROTATE, JUDGE, CLEANUP, DONE }

    private final BotPlayer bot;
    private final ServerPlayer observer;
    private final List<String> failures = new ArrayList<>();

    private Phase phase = Phase.SPAWN;
    private int ticks;
    private int checks;
    private int rotateTicks;
    private boolean done;

    private BotPlayer probe;
    private long relayBefore;
    private long suppressedBefore;

    public BotPairNoRecurseCheckTask(BotPlayer bot, ServerPlayer observer) {
        this.bot = bot;
        this.observer = observer;
    }

    @Override
    public String taskName() {
        return "BotPairNoRecurseCheck";
    }

    @Override
    public TaskTarget target() {
        return bot == null ? TaskTarget.block(net.minecraft.core.BlockPos.ZERO) : TaskTarget.block(bot.blockPosition());
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
            case SPAWN -> spawnPhase();
            case ROTATE -> rotatePhase();
            case JUDGE -> judgePhase();
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

    private void spawnPhase() {
        check("前提：本步需要一只会话假人", bot != null);
        if (bot == null) {
            advance(Phase.DONE);
            return;
        }
        relayBefore = FakeConnection.relayCount();
        suppressedBefore = FakeConnection.suppressedCount();

        // 放在**同一格**：两只假人必然互相在追踪范围里（这就是线上崩溃的现场）
        probe = BotManager.spawn(bot.serverLevel(), bot.blockPosition(), PROBE_NAME);
        check("第二只假人已生成在**同一格**（必然互相在追踪范围内）（实际 "
                        + (probe == null ? "null" : probe.blockPosition().toShortString()) + "）",
                probe != null && !probe.isRemoved());
        advance(Phase.ROTATE);
    }

    /** 每 tick 让会话假人转一下，并**显式**给它发一个旋转包（= 生产里服务端发给假人的那一类包）。 */
    private void rotatePhase() {
        bot.setYRot(bot.getYRot() + 37.0F);
        bot.setYHeadRot(bot.getYRot());
        byte headYaw = (byte) ((int) (bot.getYRot() * 256.0F / 360.0F));
        bot.connection.send(new ClientboundRotateHeadPacket(bot, headYaw));
        if (++rotateTicks >= ROTATE_TICKS) {
            advance(Phase.JUDGE);
        }
    }

    private void judgePhase() {
        long relays = FakeConnection.relayCount() - relayBefore;
        long suppressed = FakeConnection.suppressedCount() - suppressedBefore;

        // ⚠️ 上界口径（实测标定，不是拍的）：每 tick 进入这个假人的旋转包**不止我一个注入** ——
        // 两只假人各自的 ServerEntity 每 tick 也会推旋转包（实测 ≈6.3 次 tick）。递归的签名完全不同：
        // 它会在几十 tick 内到上万次，而且通常先把服务端弄死（旧码实测 = 服务端无判决退出）。
        // ⇒ 给"每 tick 16 次 + 32"的宽松上界：既不误红正常环境，又能抓住任何链式放大。
        long bound = (long) rotateTicks * 16 + 32;
        check("① 确实发生过转发（否则本判据是假绿）：relay=" + relays, relays >= 1);
        check("② ⭐ 转发**有界**（链式放大/互相喂会让它按指数涨）：relay=" + relays + " ≤ " + bound,
                relays <= bound);
        check("③ ⭐ 闸真的生效（转发过程中收到的旋转包必须被丢弃）：suppressed=" + suppressed + " ≥ 1",
                suppressed >= 1);
        check("④ ⭐ 不放大：下游副本不超过「每份转发一份」（suppressed=" + suppressed
                        + " ≤ relay=" + relays + " + 8）",
                suppressed <= relays + 8);
        check("⑤ 两只假人都没被发送路径弄掉线（服务端还活着）",
                !bot.hasDisconnected() && (probe == null || !probe.hasDisconnected()));
        check("⑥ 探针仍在玩家列表里（没被异常踢出）",
                probe != null && bot.getServer().getPlayerList().getPlayer(probe.getUUID()) != null);
        advance(Phase.CLEANUP);
    }

    private void cleanupPhase() {
        if (probe != null) {
            BotManager.remove(probe);   // 拆实体 + 清 botTag
        }
        check("自清理：探针已从玩家列表移除",
                probe == null || bot.getServer().getPlayerList().getPlayer(probe.getUUID()) == null);
        // `remove` 会清掉世界存档里的 botTag ⇒ 把**会话 bot**的记录写回去（否则会污染后续步/重启恢复）
        BotManager.saveToWorld(bot);
        bot.controller().stopMovement();
        advance(Phase.DONE);
        finish();
    }

    // ==================== 收尾 / 工具 ====================

    private Task.Status finish() {
        if (done) {
            return failures.isEmpty() ? Task.Status.DONE : Task.Status.FAILED;
        }
        done = true;
        boolean pass = failures.isEmpty();
        long relays = (bot == null) ? 0L : FakeConnection.relayCount() - relayBefore;
        long suppressed = (bot == null) ? 0L : FakeConnection.suppressedCount() - suppressedBefore;
        BotLog.info("[PairSync] SUMMARY checks={} failures={} {} → {}（relay={} suppressed={} rotateTicks={}）",
                checks, failures.size(), failures, pass ? "PASS" : "FAIL", relays, suppressed, rotateTicks);
        if (observer != null && !observer.hasDisconnected() && !observer.isRemoved()) {
            observer.sendSystemMessage(Component.literal(
                    "[alice] 双假人转发自检 " + (pass ? "PASS" : "FAIL " + failures)));
        }
        return pass ? Task.Status.DONE : Task.Status.FAILED;
    }

    private void advance(Phase next) {
        phase = next;
    }

    private void check(String what, boolean ok) {
        checks++;
        if (!ok) {
            failures.add(what);
        }
    }
}
