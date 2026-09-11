package com.dddgn.alice.task.mining;

import com.dddgn.alice.action.WriteGrant;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.pathing.MovementHelper;
import com.dddgn.alice.pathing.core.MovementType;
import com.dddgn.alice.pathing.core.search.CorePathPlanner;
import com.dddgn.alice.pathing.core.search.PathPlan;
import com.dddgn.alice.pathing.core.search.PathRequest;
import com.dddgn.alice.task.PathRetryRunner;
import net.minecraft.core.BlockPos;

/**
 * **原地加高 1 格**的共享执行器（D-116）：规划"脚位 → 脚位上方一格"（恰好 1 次 `PILLAR`）并逐步执行。
 *
 * <p>为什么必须共享：这个动作现在有**两个**使用方——`MineTask`（够不到目标时抬高，D-111）与
 * `CollectDropsTask`（掉落物在头顶够不到时抬高，D-116）。写两份就是"同一件事两次实现"，
 * 正是用户反复指出的病灶；抽到这里，能力只有一处定义、预算统一由 {@link MiningProfile} 声明。
 *
 * <p>前置：脚下/身边要有放置面（伐木时树干天然满足 ✓）、快捷栏要有一次性方块（`PILLAR` 的规划前提）。
 * 不满足时如实返回 {@code FAILED}，由调用方决定回落（加高失败 ⇒ 该目标/该掉落物如实失败）。
 */
public final class GainStepRunner {

    public enum State { RUNNING, DONE, FAILED }

    private final BotPlayer bot;
    private final MiningProfile profile;
    private final WriteGrant grant;
    private PathRetryRunner runner;
    private String failure = "";
    private boolean planned;

    public GainStepRunner(BotPlayer bot, MiningProfile profile, WriteGrant grant) {
        this.bot = bot;
        this.profile = profile;
        this.grant = grant;
    }

    public State tick() {
        if (!planned) {
            planned = true;
            BlockPos foot = MovementHelper.footCell(bot.serverLevel(), bot);
            BlockPos goal = foot.above();
            PathRequest request = PathRequest.climbApproach(bot.getUUID().toString(), foot, goal,
                    grant.requester() + ":gain");
            PathPlan plan = new CorePathPlanner().plan(bot, bot.serverLevel(), request);
            int pillars = (int) plan.movements().stream()
                    .filter(m -> m.movementType() == MovementType.PILLAR).count();
            if (!plan.reached() || pillars != 1 || pillars > profile.gainBlockBudget()) {
                failure = "gain_unavailable:" + plan.status() + ":pillar=" + pillars;
                BotLog.info("[GainStep] unavailable from={} status={} pillar={}/{} profile={}",
                        foot.toShortString(), plan.status(), pillars, profile.gainBlockBudget(),
                        profile.describe());
                return State.FAILED;
            }
            runner = new PathRetryRunner(bot, request, PathRetryRunner.DEFAULT_MAX_REPLANS,
                    grant.requester() + "-gain");
        }
        PathRetryRunner.State state = runner.tick();
        if (state == PathRetryRunner.State.RUNNING) {
            return State.RUNNING;
        }
        if (state == PathRetryRunner.State.DONE) {
            BotLog.info("[GainStep] done foot={} profile={}",
                    MovementHelper.footCell(bot.serverLevel(), bot).toShortString(),
                    profile.describe());
            return State.DONE;
        }
        failure = "gain_failed:" + state;
        BotLog.warn("[GainStep] failed state={} profile={}", state, profile.describe());
        return State.FAILED;
    }

    public String failureReason() {
        return failure;
    }
}
