package com.dddgn.alice.task;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.log.BotLog;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * **V-4 对照计时器的判据（2026-09-17）**：停表必须**排除"手动输入 `#goto` 的打字延迟"**。
 *
 * <p>实测问题（用户原话："我没办法启动场景后瞬间输入 goto"）：原 `sw_start` 在 **function 执行那一刻**
 * 就开始计时 ⇒ Baritone 侧的 tick 里混进了**打字时间**（几十 tick 量级）⇒ 与 Alice 侧的纯移动 tick **不可比**
 * （实测：Alice FALL `exec_ticks=17`，Baritone 最小 43 —— 差值主要来自打字，不是移动速度）。
 *
 * <p>修法：`sw_start` 只**待发**（清分 + `alice_sw_pending` + 在玩家脚下放 `sw_origin` marker），
 * 玩家**离开起点**后 `sw_tick` 才调 `sw_begin` 正式计时；停表只认**打了 `sw_goal` 标签**的目标 marker。
 *
 * <p>**两条判别性断言**（各自的"旧行为"都会让它红）：
 * <ol>
 *   <li>**待发期不计时**：`sw_start` 之后静置 40 tick（模拟打字）⇒ 分数必须**仍是 0**、且**没有** running 标签
 *       （旧的 `sw_start` 会直接 running ⇒ 40 tick 后分数≈40 ⇒ 红）；</li>
 *   <li>**只有目标 marker 停表**：在 bot 脚下放一个**不带标签**的 marker ⇒ 计时**必须继续**
 *       （旧的 `sw_tick` 认"任意 0.8 格内的 marker" ⇒ 会立刻停表 ⇒ 红）。</li>
 * </ol>
 */
public class ContrastTimerCheckTask implements Task {

    /** 模拟"打字延迟"的 tick 数（真实手打通常 20~60 tick）。 */
    private static final int TYPING_DELAY_TICKS = 40;

    private final BotPlayer bot;
    private final ServerPlayer observer;
    private final List<String> failures = new ArrayList<>();
    private int checksRun;
    private int ticks;
    private int phase;
    private int phaseStartTick;
    private int armedScore;
    private Vec3 armedPos = Vec3.ZERO;
    private boolean done;

    public ContrastTimerCheckTask(BotPlayer bot, ServerPlayer observer) {
        this.bot = bot;
        this.observer = observer;
    }

    @Override
    public String taskName() {
        return "ContrastTimerCheck";
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
        ticks++;
        var server = bot.serverLevel().getServer();
        var scoreboard = server.getScoreboard();
        var objective = scoreboard.getObjective("alice_sw");
        if (objective == null) {
            check("前提：计分板目标 alice_sw 存在（数据包 load 标签跑过）", false);
            return finish();
        }
        int score = scoreboard.getOrCreatePlayerScore(bot.getScoreboardName(), objective).getScore();

        switch (phase) {
            case 0 -> {
                // 复位：清标签/清分/清 marker，**并把 bot 定住**（否则它自己飘一下就会被判成"开始移动"，
                // 打字窗口就不成立了 —— 2026-09-17 实测：第一版正是因此假红）。
                bot.removeTag("alice_sw_running");
                bot.removeTag("alice_sw_pending");
                scoreboard.getOrCreatePlayerScore(bot.getScoreboardName(), objective).setScore(0);
                dispatch("kill @e[type=minecraft:marker]");
                bot.setNoGravity(true);
                bot.setDeltaMovement(Vec3.ZERO);
                armedPos = bot.position();
                dispatch("function alice_test:sw_start");
                check("待发：sw_start 后带上 alice_sw_pending", bot.getTags().contains("alice_sw_pending"));
                check("待发：sw_start 后**不该**直接 running（旧行为会）", !bot.getTags().contains("alice_sw_running"));
                phase = 1;
                phaseStartTick = ticks;
            }
            case 1 -> {
                // ① 静置 40 tick（模拟打字）⇒ 分数必须仍是 0
                if (ticks - phaseStartTick < TYPING_DELAY_TICKS) {
                    return Status.RUNNING;
                }
                armedScore = score;
                double drift = bot.position().distanceTo(armedPos);
                check("前提：打字窗口内 bot **没动**（漂移 " + String.format(java.util.Locale.ROOT, "%.3f", drift)
                        + " 格；动了这个用例就不成立）", drift < 0.05D);
                check("① 打字延迟 " + TYPING_DELAY_TICKS + " tick 内**不计时**（实测分数=" + score + "，期望 0）",
                        score == 0);
                check("① 期间**没有** running 标签", !bot.getTags().contains("alice_sw_running"));
                // 模拟"开始移动"：离开起点 marker 2 格
                bot.teleportTo(bot.serverLevel(), bot.getX() + 2.0D, bot.getY(), bot.getZ(),
                        java.util.Set.of(), bot.getYRot(), bot.getXRot());
                bot.setDeltaMovement(Vec3.ZERO);
                phase = 2;
                phaseStartTick = ticks;
            }
            case 2 -> {
                if (ticks - phaseStartTick < 3) {
                    return Status.RUNNING;
                }
                check("② 离开起点后正式计时（running 标签在）", bot.getTags().contains("alice_sw_running"));
                check("② 离开起点后分数开始累加（实测 " + score + "，期望 1~3）", score >= 1 && score <= 3);
                // ③ 在脚下放一个**不带标签**的 marker ⇒ 不许停表
                dispatch("summon minecraft:marker ~ ~ ~");
                phase = 3;
                phaseStartTick = ticks;
            }
            case 3 -> {
                if (ticks - phaseStartTick < 2) {
                    return Status.RUNNING;
                }
                check("③ 非目标 marker 不停表（running 仍在）", bot.getTags().contains("alice_sw_running"));
                // ④ 换成一个带 sw_goal 的目标 marker ⇒ 必须停表
                dispatch("kill @e[type=minecraft:marker]");
                dispatch("summon minecraft:marker ~ ~ ~ {Tags:[\"sw_goal\"]}");
                phase = 4;
                phaseStartTick = ticks;
            }
            case 4 -> {
                if (ticks - phaseStartTick < 2) {
                    return Status.RUNNING;
                }
                check("④ 目标 marker（sw_goal）会停表（running 已清）", !bot.getTags().contains("alice_sw_running"));
                check("④ 停表分数**很小**（实测 " + score + "，≤ 8 ⇒ 说明只算了移动的几 tick）", score <= 8);
                BotLog.info("[ContrastTimer] 观测：打字期分数={} 停表分数={}（间隔 {} tick）",
                        armedScore, score, ticks);
                // 收尾
                dispatch("kill @e[type=minecraft:marker]");
                bot.removeTag("alice_sw_running");
                bot.removeTag("alice_sw_pending");
                scoreboard.getOrCreatePlayerScore(bot.getScoreboardName(), objective).setScore(0);
                bot.setNoGravity(false);   // 收尾：恢复正常物理
                check("收尾：标签与分数已复位",
                        !bot.getTags().contains("alice_sw_running") && !bot.getTags().contains("alice_sw_pending"));
                phase = 5;
            }
            default -> {
                return finish();
            }
        }
        return Status.RUNNING;
    }

    /** 以 bot 为执行者跑一条数据包命令（`@s` = bot）。 */
    private void dispatch(String command) {
        var server = bot.serverLevel().getServer();
        var source = server.createCommandSourceStack()
                .withEntity(bot)
                .withPosition(bot.position())
                .withSuppressedOutput();
        server.getCommands().performPrefixedCommand(source, command);
    }

    private Status finish() {
        done = true;
        boolean pass = failures.isEmpty();
        BotLog.info("[ContrastTimer] SUMMARY checks={} failures={} {} → {}",
                checksRun, failures.size(), failures, pass ? "PASS" : "FAIL");
        if (observer != null && !observer.hasDisconnected() && !observer.isRemoved()) {
            observer.sendSystemMessage(Component.literal("[alice] 对照停表自检 "
                    + (pass ? "PASS（打字延迟不计时；只有目标 marker 停表）" : "FAIL " + failures)));
        }
        return Status.RUNNING;
    }

    private void check(String what, boolean ok) {
        checksRun++;
        if (!ok) {
            failures.add(what);
        }
    }
}
