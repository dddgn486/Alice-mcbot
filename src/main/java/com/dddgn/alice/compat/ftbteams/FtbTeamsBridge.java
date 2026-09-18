package com.dddgn.alice.compat.ftbteams;

import com.dddgn.alice.log.BotLog;
import net.minecraft.server.level.ServerPlayer;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * FTB Teams 的**只读**桥（D-321，2026-09-18）—— 只看、不写；写一律走 FTB 自己的命令（见 {@link FtbCommandRunner}）。
 *
 * <p><b>为什么是反射</b>：FTB 不是 Alice 的编译期依赖（也不该是 —— 玩家可以不带它玩）。
 * 每个成员都在**装好的 jar** 上逐条核过签名（`ftb-teams-forge-2001.3.2.jar`）：
 * <ul>
 *   <li>`dev.ftb.mods.ftbteams.api.FTBTeamsAPI`：`static API api()`；</li>
 *   <li>`FTBTeamsAPI$API`：`boolean isManagerLoaded()` / `TeamManager getManager()`；</li>
 *   <li>`TeamManager`：`Optional&lt;Team&gt; getTeamForPlayer(ServerPlayer)`；</li>
 *   <li>`Team`：`UUID getId()` / `String getShortName()` / `boolean isPartyTeam()` /
 *       `TeamRank getRankForPlayer(UUID)` / `UUID getOwner()`。</li>
 * </ul>
 *
 * <p><b>D-318 的教训（这条类是它的产物）</b>：上一版 FTB 兼容层靠"猜"一个方法名
 * （`FTBChunksAPI.isManagerLoaded()` 其实不存在）⇒ 整套兼容**从未生效**、也从未报错。
 * 所以这里：① 缺类 ⇒ 明确回"未安装"；② 类在但方法签名对不上 ⇒ 回 `signature_mismatch:<成员>`
 * 并**打一条 warn**（把"猜错了"变成看得见的事实，而不是静默的空结果）。
 */
public final class FtbTeamsBridge {

    /** 只读取出来的队伍快照（不含任何可变引用）。 */
    public record TeamView(UUID id, String shortName, boolean party, String rank, UUID owner) {

        /** 人类可读的一行（聊天的 log 都用它，避免两处措辞漂移）。 */
        public String describe() {
            return (party ? "party「" : "自己「") + shortName + "」· 我在此队的身份=" + rank;
        }
    }

    private static final AtomicBoolean PROBED = new AtomicBoolean();
    private static volatile boolean available;
    private static volatile String unavailableReason = "not_probed";
    private static volatile boolean managerLoaded;
    private static volatile String lastError = "";

    private static Method apiMethod;
    private static Method isManagerLoadedMethod;
    private static Method getManagerMethod;
    private static Method getTeamForPlayerMethod;
    private static Method getTeamsMethod;
    private static Method teamGetIdMethod;
    private static Method teamGetShortNameMethod;
    private static Method teamIsPartyTeamMethod;
    private static Method teamGetRankForPlayerMethod;
    private static Method teamGetOwnerMethod;

    private FtbTeamsBridge() {
    }

    /** FTB Teams 是否在场且签名对得上（不含"管理器已加载"这个运行期条件）。 */
    public static boolean available() {
        probe();
        return available;
    }

    /** 不在场/对不上时的**具体原因**（聊天与日志都打这个，不写"不支持"这种空话）。 */
    public static String unavailableReason() {
        probe();
        return available ? "" : unavailableReason;
    }

    /** 服务端的队伍管理器是否已就绪（客户端侧/主菜单里会是 false）。 */
    public static boolean managerLoaded() {
        probe();
        return available && managerLoaded;
    }

    /** 最近一次反射调用失败的原因（空串 = 没失败过）；只用于诊断输出。 */
    public static String lastError() {
        return lastError;
    }

    /** 读一只玩家（真人或我们的假人）当前的 FTB 队伍；取不到 ⇒ empty（**绝不猜一个假身份**）。 */
    public static Optional<TeamView> teamOf(ServerPlayer player) {
        Optional<Object> team = teamObjectOf(player);
        if (team.isEmpty()) {
            return Optional.empty();
        }
        TeamView view = viewOf(team.get(), player.getUUID());
        return view == null ? Optional.empty() : Optional.of(view);
    }

    /**
     * `who` 在 `member` 所在队伍里的身份（读的是**队伍对象自己的** `getRankForPlayer`）。
     * 入队判定要用它：FTB 的 `party join` 要求 `isAtLeast(TeamRank.INVITED)`。
     */
    public static Optional<String> rankInTeamOf(ServerPlayer member, ServerPlayer who) {
        Optional<Object> team = teamObjectOf(member);
        if (team.isEmpty() || who == null) {
            return Optional.empty();
        }
        try {
            Object rank = teamGetRankForPlayerMethod.invoke(team.get(), who.getUUID());
            return Optional.of(rank == null ? "NONE" : rank.toString());
        } catch (Throwable t) {
            recordError("rankInTeamOf", t);
            return Optional.empty();
        }
    }

    /**
     * `who` 是否**已被邀请**进 `teamOwner` 的队（含更高身份）。
     *
     * <p>判据用的是 `TeamRank` 的常量名，逐条对着装好的 jar（`TeamRank.values()` =
     * `ENEMY, NONE, ALLY, INVITED, MEMBER, OFFICER, OWNER`）—— 不依赖它内部的 power 数值。
     */
    public static boolean invited(ServerPlayer teamOwner, ServerPlayer who) {
        Optional<String> rank = rankInTeamOf(teamOwner, who);
        if (rank.isEmpty()) {
            return false;
        }
        return switch (rank.get()) {
            case "INVITED", "MEMBER", "OFFICER", "OWNER" -> true;
            default -> false;
        };
    }

    // ==================== 内部 ====================

    private static Optional<Object> teamObjectOf(ServerPlayer player) {
        probe();
        if (!available || player == null || player.getServer() == null) {
            return Optional.empty();
        }
        try {
            Object api = apiMethod.invoke(null);
            if (api == null) {
                return Optional.empty();
            }
            boolean loaded = Boolean.TRUE.equals(isManagerLoadedMethod.invoke(api));
            managerLoaded = loaded;
            if (!loaded) {
                return Optional.empty();
            }
            Object manager = getManagerMethod.invoke(api);
            if (manager == null) {
                return Optional.empty();
            }
            Object optional = getTeamForPlayerMethod.invoke(manager, player);
            if (!(optional instanceof Optional<?> opt) || opt.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(opt.get());
        } catch (Throwable t) {
            recordError("teamOf", t);
            return Optional.empty();
        }
    }

    private static TeamView viewOf(Object team, UUID viewer) {
        try {
            UUID id = (UUID) teamGetIdMethod.invoke(team);
            Object shortName = teamGetShortNameMethod.invoke(team);
            boolean party = Boolean.TRUE.equals(teamIsPartyTeamMethod.invoke(team));
            Object rank = teamGetRankForPlayerMethod.invoke(team, viewer);
            UUID owner = (UUID) teamGetOwnerMethod.invoke(team);
            return new TeamView(id, shortName == null ? "?" : shortName.toString(),
                    party, rank == null ? "NONE" : rank.toString(), owner);
        } catch (Throwable t) {
            recordError("viewOf", t);
            return null;
        }
    }

    /** 两只玩家是否**同一个 FTB 队伍**（FTB Chunks 的 `isMemberOrBetter()` 就是按这个 id 比的）。 */
    public static boolean sameTeam(ServerPlayer a, ServerPlayer b) {
        Optional<TeamView> ta = teamOf(a);
        Optional<TeamView> tb = teamOf(b);
        return ta.isPresent() && tb.isPresent() && ta.get().id().equals(tb.get().id());
    }

    /** 服务器上 party 的数量（用于"幂等/清理真的生效"这类计数判据）；读不到 ⇒ -1。 */
    public static int partyCount() {
        probe();
        if (!available) {
            return -1;
        }
        try {
            Object api = apiMethod.invoke(null);
            if (api == null || !Boolean.TRUE.equals(isManagerLoadedMethod.invoke(api))) {
                return -1;
            }
            Object manager = getManagerMethod.invoke(api);
            if (manager == null) {
                return -1;
            }
            Object teams = getTeamsMethod.invoke(manager);
            if (!(teams instanceof java.util.Collection<?> collection)) {
                return -1;
            }
            int parties = 0;
            for (Object team : collection) {
                if (Boolean.TRUE.equals(teamIsPartyTeamMethod.invoke(team))) {
                    parties++;
                }
            }
            return parties;
        } catch (Throwable t) {
            recordError("partyCount", t);
            return -1;
        }
    }

    /** 人类可读：`party「x」· 我在此队的身份=MEMBER`；取不到时写清楚为什么。 */
    public static String describeTeamOf(ServerPlayer player) {
        Optional<TeamView> team = teamOf(player);
        if (team.isPresent()) {
            return team.get().describe();
        }
        if (!available) {
            return "取不到（" + unavailableReason() + "）";
        }
        return "取不到（" + (lastError.isEmpty() ? "FTB 管理器未就绪或该玩家没有队伍" : lastError) + "）";
    }

    // ==================== 反射落地 ====================

    private static void probe() {
        if (!PROBED.compareAndSet(false, true)) {
            return;
        }
        try {
            Class<?> apiClass = Class.forName("dev.ftb.mods.ftbteams.api.FTBTeamsAPI");
            Class<?> apiIface = Class.forName("dev.ftb.mods.ftbteams.api.FTBTeamsAPI$API");
            Class<?> managerIface = Class.forName("dev.ftb.mods.ftbteams.api.TeamManager");
            Class<?> teamIface = Class.forName("dev.ftb.mods.ftbteams.api.Team");

            apiMethod = apiClass.getMethod("api");
            isManagerLoadedMethod = apiIface.getMethod("isManagerLoaded");
            getManagerMethod = apiIface.getMethod("getManager");
            getTeamForPlayerMethod = managerIface.getMethod("getTeamForPlayer", ServerPlayer.class);
            getTeamsMethod = managerIface.getMethod("getTeams");
            teamGetIdMethod = teamIface.getMethod("getId");
            teamGetShortNameMethod = teamIface.getMethod("getShortName");
            teamIsPlayerTeamMethodCheck(teamIface);
            teamIsPartyTeamMethod = teamIface.getMethod("isPartyTeam");
            teamGetRankForPlayerMethod = teamIface.getMethod("getRankForPlayer", UUID.class);
            teamGetOwnerMethod = teamIface.getMethod("getOwner");

            available = true;
            unavailableReason = "";
            BotLog.info("[FTB] FTB Teams 只读桥已就绪（反射签名逐条核对通过；写一律走它自己的命令）");
        } catch (ClassNotFoundException e) {
            available = false;
            unavailableReason = "ftb_teams_absent";
            BotLog.info("[FTB] 未检测到 FTB Teams（{}）⇒ 队伍相关功能整体跳过", e.getMessage());
        } catch (NoSuchMethodException e) {
            available = false;
            unavailableReason = "signature_mismatch:" + e.getMessage();
            BotLog.warn("[FTB] ⛔ 反射签名核对失败：{} ⇒ 不猜、不降级，直接当作不可用（D-318 教训）",
                    e.getMessage());
        }
    }

    /** `isPlayerTeam()` 只用于自检（默认方法）：核不到不影响只读能力，但要把事实记下来。 */
    private static void teamIsPlayerTeamMethodCheck(Class<?> teamIface) {
        try {
            teamIface.getMethod("isPlayerTeam");
        } catch (NoSuchMethodException e) {
            BotLog.warn("[FTB] 提示：`Team.isPlayerTeam()` 不存在（本版本可能只有 isPartyTeam）");
        }
    }

    private static void recordError(String where, Throwable t) {
        Throwable cause = (t instanceof java.lang.reflect.InvocationTargetException ite && ite.getCause() != null)
                ? ite.getCause() : t;
        String message = where + ": " + cause;
        if (!message.equals(lastError)) {
            lastError = message;
            BotLog.warn("[FTB] 只读调用失败（后续同类失败不再重复打印）：{}", message);
        }
    }
}
