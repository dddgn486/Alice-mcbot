package com.dddgn.alice.compat.ftbteams;

import com.dddgn.alice.bot.BotOwnership;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.log.BotLog;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * **让假人继承创建者的 FTB 身份**（D-321）—— 全部通过 FTB 自己的命令完成，Alice 只负责"代打 + 核对"。
 *
 * <p><b>为什么必须先有 party（1.20.1 源码核过，不是猜的）</b>：
 * <ul>
 *   <li>`ChunkTeamDataImpl.canPlayerUse:291-305`：`PUBLIC`⇒放行；非假人 + `ALLIES`⇒`isAlly(uuid)`；
 *       否则要 `getRankForPlayer(uuid).isMemberOrBetter()`；而 `FTBChunksWorldConfig.DEF_BLOCK_EDIT`
 *       默认就是 `ALLIES`；</li>
 *   <li>而"加盟友"和"入队"两个命令**都挂在 `/ftbteams party` 下面**（`FTBTeamsCommands.register`：
 *       `party → allies add|remove` / `party → invite` / `party → join`）
 *       ⇒ 创建者**自己没有 party 时，假人没有任何合法途径在他的领地里动手**。</li>
 * </ul>
 *
 * <p><b>入队要邀请（同源码核过）</b>：`party join` 先取 `partyTeam.getRankForPlayer(uuid)` 再
 * `isAtLeast(TeamRank.INVITED)`，否则抛 `TeamArgument.NOT_INVITED`
 * ⇒ 顺序固定为 **create → invite → join**，不能省 invite。
 *
 * <p><b>副作用要说清楚</b>：创建者一旦建队，FTB Chunks 的 `playerJoinedParty`（1.20.1 `FTBChunks.java:450`）
 * 会把创建者**已有的认领区块转移进这个 party**（FTB 自己留了"原始认领"记录，退队时还回），
 * claim 上限默认 `PARTY_LIMIT_MODE=LARGEST` ⇒ 不变。所以 Alice **不在 spawn 时偷偷做这件事**，
 * 只在玩家显式敲 `/alice ftb bind` 时做（用户裁定，D-321）。
 */
public final class FtbPartyBinder {

    /** 一步的现场事实（`what` 是动作，`detail` 是命令回话或状态）。 */
    public record Step(String what, boolean ok, String detail) {

        public String line() {
            return what + (ok ? " ✓" : " ✗") + (detail.isEmpty() ? "" : "（" + detail + "）");
        }
    }

    /** 一次绑定/解绑的全部证据 + 结论。 */
    public record Result(boolean ok, String partyName, List<Step> steps) {

        public String summary() {
            List<String> parts = new ArrayList<>();
            for (Step step : steps) {
                parts.add(step.line());
            }
            return String.join("；", parts);
        }
    }

    private static final int DETAIL_LIMIT = 160;

    private FtbPartyBinder() {
    }

    /** 把 `bots` 拉进 `creator` 的 FTB 队伍（没有就建一个）。**只有显式命令会调它。** */
    public static Result bind(ServerPlayer creator, List<BotPlayer> bots) {
        List<Step> steps = new ArrayList<>();
        if (creator == null) {
            steps.add(new Step("前提：需要一个游戏内玩家当创建者", false, "命令方块/控制台没有 FTB 身份"));
            return new Result(false, "", steps);
        }
        if (!FtbTeamsBridge.available()) {
            steps.add(new Step("前提：FTB Teams 在场", false, FtbTeamsBridge.unavailableReason()));
            return new Result(false, "", steps);
        }
        if (bots.isEmpty()) {
            steps.add(new Step("前提：至少有一只是你创建的假人", false, "先 /alice spawn <名字>"));
            return new Result(false, "", steps);
        }

        String partyName = ensureParty(creator, steps);
        if (partyName == null) {
            return new Result(false, "", steps);
        }

        boolean allOk = true;
        for (BotPlayer bot : bots) {
            allOk &= bindOne(creator, bot, partyName, steps);
        }
        Result result = new Result(allOk, partyName, steps);
        BotLog.info("[FTB] bind 结算 creator={} party=「{}」bots={} ok={} | {}",
                creator.getGameProfile().getName(), partyName, bots.size(), allOk, result.summary());
        return result;
    }

    /**
     * 反向操作（退伙）：先让**成员**（假人）退，再让**队长**退 ——
     * `PartyTeam.leave` 对"还有别人的队长"会抛 `OWNER_CANT_LEAVE`，顺序反了会卡住；
     * 队长作为最后一名成员退队时 FTC 会删掉这个 party（`deleteTeam` + 删队伍文件）。
     */
    public static Result leave(ServerPlayer creator, List<BotPlayer> bots) {
        List<Step> steps = new ArrayList<>();
        if (creator == null || !FtbTeamsBridge.available()) {
            steps.add(new Step("前提：FTB Teams 在场 + 有创建者", false,
                    creator == null ? "无玩家身份" : FtbTeamsBridge.unavailableReason()));
            return new Result(false, "", steps);
        }

        Optional<FtbTeamsBridge.TeamView> creatorTeam = FtbTeamsBridge.teamOf(creator);
        String partyName = creatorTeam.map(FtbTeamsBridge.TeamView::shortName).orElse("");
        boolean allOk = true;

        for (BotPlayer bot : bots) {
            if (!FtbTeamsBridge.sameTeam(creator, bot)) {
                steps.add(new Step("假人 " + name(bot) + " 本来就不在你的队伍里", true, "无需退伙"));
                continue;
            }
            FtbCommandRunner.Outcome outcome = FtbCommandRunner.runAs(bot, "ftbteams party leave");
            boolean nowAway = !FtbTeamsBridge.sameTeam(creator, bot);
            allOk &= outcome.ok() && nowAway;
            steps.add(new Step("假人 " + name(bot) + " 退伙", outcome.ok() && nowAway,
                    outcome.ok() ? (nowAway ? "已回到它自己的队伍" : "命令成功但仍是同队")
                            : detail(outcome)));
        }

        if (creatorTeam.isPresent() && creatorTeam.get().party()) {
            FtbCommandRunner.Outcome outcome = FtbCommandRunner.runAs(creator, "ftbteams party leave");
            Optional<FtbTeamsBridge.TeamView> after = FtbTeamsBridge.teamOf(creator);
            boolean leftParty = after.isPresent() && !after.get().party();
            allOk &= outcome.ok() && leftParty;
            steps.add(new Step("你自己退队（最后一名成员退队 ⇒ FTB 删掉该队伍）",
                    outcome.ok() && leftParty,
                    after.map(FtbTeamsBridge.TeamView::describe).orElse("读不到队伍")));
        }

        Result result = new Result(allOk, partyName, steps);
        BotLog.info("[FTB] leave 结算 creator={} party=「{}」ok={} | {}",
                creator.getGameProfile().getName(), partyName, allOk, result.summary());
        return result;
    }

    // ==================== 内部 ====================

    /** 创建者还没有 party ⇒ 建一个；返回可用来 join 的队伍短名（失败 ⇒ null）。 */
    private static String ensureParty(ServerPlayer creator, List<Step> steps) {
        Optional<FtbTeamsBridge.TeamView> before = FtbTeamsBridge.teamOf(creator);
        if (before.isEmpty()) {
            steps.add(new Step("读取你的 FTB 队伍", false, "取不到（" + FtbTeamsBridge.describeTeamOf(creator) + "）"));
            return null;
        }
        if (before.get().party()) {
            steps.add(new Step("你已经有队伍", true, before.get().describe() + "（不新建）"));
            return before.get().shortName();
        }

        String wanted = name(creator) + "的队伍";
        FtbCommandRunner.Outcome outcome = FtbCommandRunner.runAs(creator, "ftbteams party create " + wanted);
        Optional<FtbTeamsBridge.TeamView> after = FtbTeamsBridge.teamOf(creator);
        if (outcome.ok() && after.isPresent() && after.get().party()) {
            steps.add(new Step("建队（你的个人队 ⇒ party）", true,
                    after.get().describe() + "；⚠️ 你已有的认领区按 FTB 规则转入该队（退队时还回）"));
            return after.get().shortName();
        }
        steps.add(new Step("建队", false, outcome.ok() ? "命令成功但没读到 party" : detail(outcome)));
        return null;
    }

    /** 一只假人：同队 ⇒ 幂等返回；否则 invite（队长身份）+ join（假人身份）+ 复核。 */
    private static boolean bindOne(ServerPlayer creator, BotPlayer bot, String partyName, List<Step> steps) {
        String botName = name(bot);
        String creatorName = name(creator);
        BotOwnership.Creator owner = BotOwnership.creatorOfBot(bot);

        if (FtbTeamsBridge.sameTeam(creator, bot)) {
            steps.add(new Step("假人 " + botName + " 已在同一队伍", true,
                    FtbTeamsBridge.describeTeamOf(bot) + "（重复执行不改任何东西）"));
            return true;
        }
        // 身份闸：只允许"创建者本人"给自己的假人办这件事（未登记 ⇒ 先 /alice adopt，不静默补主）
        if (!owner.registered() || !creator.getUUID().equals(owner.uuid())) {
            steps.add(new Step("假人 " + botName + " 的创建者不是执行者", false,
                    "创建者=" + BotOwnership.describe(owner) + "；只有创建者本人能给自己的假人办这件事"
                            + (owner.registered() ? "" : "（先 /alice adopt " + botName + "）")));
            return false;
        }

        FtbCommandRunner.Outcome invite = FtbCommandRunner.runAs(creator,
                "ftbteams party invite " + botName);
        boolean invited = FtbTeamsBridge.invited(creator, bot);
        steps.add(new Step("邀请假人 " + botName + "（入队前必须有邀请）", invite.ok() && invited,
                invite.ok() ? (invited ? "已获得 INVITED 级" : "命令成功但读不到 INVITED 级")
                        : detail(invite)));
        if (!invite.ok() || !invited) {
            return false;
        }

        // `party join` 的参数是队伍（TeamArgument）⇒ 先给短名，失败再退回"队长名字"这种写法（两种都记进证据）
        FtbCommandRunner.Outcome join = FtbCommandRunner.runAs(bot, "ftbteams party join " + partyName);
        String usedArg = partyName;
        if (!join.ok() && !FtbTeamsBridge.sameTeam(creator, bot)) {
            join = FtbCommandRunner.runAs(bot, "ftbteams party join " + creatorName);
            usedArg = creatorName;
        }
        boolean joined = FtbTeamsBridge.sameTeam(creator, bot);
        steps.add(new Step("假人 " + botName + " 入队（参数=" + usedArg + "）", join.ok() && joined,
                joined ? FtbTeamsBridge.describeTeamOf(bot) : detail(join)));
        return join.ok() && joined;
    }

    private static String detail(FtbCommandRunner.Outcome outcome) {
        String text = outcome.text();
        if (text.isEmpty()) {
            return "命令返回 " + outcome.result() + "（无回话）";
        }
        return text.length() <= DETAIL_LIMIT ? text : text.substring(0, DETAIL_LIMIT) + "…";
    }

    private static String name(ServerPlayer player) {
        return player.getGameProfile().getName();
    }
}
