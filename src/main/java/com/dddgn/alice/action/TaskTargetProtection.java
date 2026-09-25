package com.dddgn.alice.action;

import com.dddgn.alice.log.BotLog;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;

/**
 * **任务作用域内的写入保护**（同一个 bot 在同一任务里不许自己毁掉自己的路）。两台面：
 *
 * <ol>
 *   <li><b>破坏面</b>（`D-362`，2026-09-20）：**清障不得吃掉任务目标** —— 见下方 §问题；</li>
 *   <li><b>放置面</b>（`I5`，2026-09-25 用户裁定「先治根因，让他不会在作业区放方块」）：
 *       **不许把方块放进本作业自己的通道层格** —— 见 {@link #CHANNEL_CODE} 的真机证据。</li>
 * </ol>
 *
 * <p>两面刻意共用一台机制（同一作用域生命周期、同一 fail-closed 纪律、同一消费层
 * {@code BlockInteraction}），因为它们是同一条不变式的两侧：**"这条通道在施工期间必须保持可通行"**。
 *
 * <p>用户原话（破坏面）：「先不管不是不修复，**对清障目标和任务目标的区分还是要修复**，即使是绕过去；
 * 不管的是**成本模型隐含的不准确问题**」。
 *
 * <h3>问题（真机实测，第一轮 A 路线）</h3>
 * 第 8 个目标是煤 `479,68,103`，规划器给它选的站位点是 **`479,68,104`——那一格本身也是煤**
 * （同簇的活候选）。于是它被当"清障方块"挖掉：日志 `[WRITE] break 479,68,104 minecraft:coal_ore
 * by=mine-runner:attempt0:PATH_ACCESS` ⇒ **产物进了包，但既不进 `success` 也不进 `failure`**
 * （同时解释了当轮 `candidates=55` 与 `inventoryDelta=9` 两个对不上的数）。
 *
 * <h3>对照 Baritone（本 skill 要求给 `文件:行`）</h3>
 * <ul>
 *   <li>{@code baritone/pathing/movement/MovementHelper.java:68} {@code avoidBreaking(bsi,x,y,z,state)}
 *       ——"这一格不许挖"的谓词（名单 = {@code blocksToDisallowBreaking} + 冰 + 虫蚀方块 + 相邻危险）；</li>
 *   <li>同文件 {@code :590}：命中它 ⇒ **该步代价 {@code COST_INF}** ⇒ **规划器自然绕行**；</li>
 *   <li>{@code baritone/process/BuilderProcess.java:1166}：{@code isPossiblyProtected(x,y,z)} ⇒ {@code COST_INF}
 *       ——"被保护的格子不许挖"同款语义。</li>
 * </ul>
 * Alice 这里做的是**同一件事**，只是闸门放在**授权侧**（`BlockInteraction.breakRefusal`）而不是只放在搜索的成本里：
 * 搜索与执行**共用同一个 `breakable`** ⇒ 不会出现"计划说能过、执行到一半才被拒"。
 *
 * <h3>口径（可判据的三条）</h3>
 * <ol>
 *   <li><b>只管清障</b>：仅当 `WriteReason == PATH_ACCESS` 时生效 —— `EXPECTED_TARGET`（真的去挖那一格）
 *       逐字不受影响；</li>
 *   <li><b>作用域属于任务</b>：按 `botId` 记；任务开始时由 `BotManager` 清（与 `WriteEnvelopes` 同一处），
 *       任务结束时由 Job 自己 `end` ⇒ **不会跨任务泄漏**（泄漏了就等于"这个 bot 以后都不能清障开路"）；</li>
 *   <li><b>豁免"当前目标"由安装方给</b>：本类只回答"这一格是不是任务目标"，至于"这一格正是我这次要挖的"
 *       由 Job 在谓词里排除（`MineJob`：`!pos.equals(current)`）—— 否则 `ENTER_TARGET`（破坏进入自己那格）
 *       会被自己拦死。</li>
 * </ol>
 *
 * <p>⚠️ 谓词**自己抛异常时按"受保护"处理（fail-closed）+ 响亮告警**：保护类判据的保守方向是"宁可绕远"，
 * 而且"守卫自己坏了"必须看得见（静默 fail-open 会把本类要修的 bug 原样放回来）。
 */
public final class TaskTargetProtection {

    /** 拒绝码（进 `rejected` / `[WRITE-REFUSED]` 的**稳定**字符串，别改字面量）。 */
    public static final String CODE = "task_target_not_clearance";

    /**
     * **通道层格拒绝码**（`I5` 的**放置面**，2026-09-25 用户裁定「先治根因，让他不会在作业区放方块」）。
     *
     * <h3>为什么它与上面那条是同一台机制的两面，而不是新机制</h3>
     * 上面那条（`D-362`）拦的是"**清障不得吃掉任务目标**"（破坏侧）；这一条拦的是"**不许把方块放进
     * 我自己的通道层**"（放置侧）。两者共用：① 同一生命周期（按 `botId`、任务开始立、任务结束撤）；
     * ② 同一 fail-closed 纪律（谓词抛异常 ⇒ 按受保护处理 + 响亮告警）；③ 同一消费层
     * （{@code BlockInteraction}，搜索与执行共用 ⇒ 不会"计划说能过、执行到一半才被拒"）。
     *
     * <h3>真机证据（第四轮，2026-09-25，逐字见 `docs/reviews/2026-09-25-真机第四轮-自己搭的方块拦住回程.md`）</h3>
     * <pre>
     * 23:17:20.605 [WRITE] break -65,54,181 stone   ← 鱼骨挖穿通道末列的脚位格
     * 23:17:21.704 [WRITE] break -65,55,181 stone   ← 头位格 ⇒ 该列成为合格的 2 格高通道
     * 23:17:22.653 CHASE_APPROACH 走进刚挖完的那一格（bot 站进去）
     * 23:17:31.257 [WRITE] place -65,54,181 cobblestone by=collect-drops:attempt0:STEP_PLACEMENT
     *              ↑ **把自己 2 秒前刚走过的通道脚位格填回实心**（PILLAR 垫脚上矿簇）
     * 23:17:36.155 SPUR_RETURN feet=-66,56,181 → junction=-65,54,172
     * 23:17:36.157 [PathingStats] descend_precondition=11 status=UNREACHABLE（nodes=3）
     * 23:17:36.204 return_failed ⇒ 整个作业 FAILED
     * </pre>
     * 因果链（**与"填了一格"严格对应，不是相关**）：那一格原本是空气 ⇒ `appendFall` 的 drop=2 落点
     * `canStandCentered` 成立（落点空 + 底下石头 + `fallRecoverable` 的净空/放置面/一次性方块都够）
     * ⇒ **一条零破坏的 `FALL` 边**；被填成圆石后落点不可站 ⇒ 该边消失，而 DESCEND 又已因
     * "2 格高通道 + 中继格头位是天花板石头"被拒 ⇒ 回程路被**自己的一格**切断。
     */
    public static final String CHANNEL_CODE = "channel_layer_reserved";

    /**
     * 一个 bot 同时只跑一个任务 ⇒ 每个 bot 一份作用域。两个谓词里只会有一个被装上，
     * 且装新的时**整份覆盖**（不做"只改一半"）⇒ 不会留下上一任务的一半作用域。
     */
    private record Scope(String owner, Predicate<BlockPos> isTaskTarget, Predicate<BlockPos> isChannelCell) {
    }

    private static final Map<String, Scope> SCOPES = new HashMap<>();

    private TaskTargetProtection() {
    }

    /** 任务开始：登记"本任务的目标方块"谓词（`owner` 只用于归因日志）。 */
    public static void begin(ServerPlayer bot, String owner, Predicate<BlockPos> isTaskTarget) {
        if (bot == null || isTaskTarget == null) {
            return;
        }
        SCOPES.put(bot.getUUID().toString(),
                new Scope(owner == null ? "unknown" : owner, isTaskTarget, null));
    }

    /**
     * 任务开始：登记"本任务的**通道层格**"谓词（`I5` 的放置面）——这些格在施工期间**不许被放方块**。
     *
     * <p>⚠️ **刻意不是"不许破坏"**：鱼骨挖穿通道本身就是破坏脚位/头位那两格，破坏侧属于另一件事
     * （草案 `S2` 的 `I5` 破坏面）。本条只回答"这一格能不能**放**"。
     *
     * @param isChannelCell 该格是不是本作业自己的通道层格（脚位格 + 头位格）
     */
    public static void beginChannel(ServerPlayer bot, String owner, Predicate<BlockPos> isChannelCell) {
        if (bot == null || isChannelCell == null) {
            return;
        }
        SCOPES.put(bot.getUUID().toString(),
                new Scope(owner == null ? "unknown" : owner, null, isChannelCell));
    }

    /** 任务结束：撤销（**必须**走，否则这个 bot 之后的开路清障都会被拦）。 */
    public static void end(ServerPlayer bot) {
        if (bot != null) {
            end(bot.getUUID().toString());
        }
    }

    public static void end(String botId) {
        if (botId != null) {
            SCOPES.remove(botId);
        }
    }

    /** 这一格是不是**受保护的任务目标**；返回 `null` = 不受保护（可以当清障方块）。 */
    public static String refusalFor(ServerPlayer bot, BlockPos pos) {
        return query(bot, pos, true);
    }

    /**
     * 这一格能不能**放方块**（`I5` 放置面）；返回 `null` = 不受保护（可以放）。
     *
     * <p>与 {@link #refusalFor} 共用查询体 ⇒ 失败方向（fail-closed）与告警只有一处实现。
     */
    public static String placementRefusalFor(ServerPlayer bot, BlockPos pos) {
        return query(bot, pos, false);
    }

    private static String query(ServerPlayer bot, BlockPos pos, boolean targetSide) {
        if (bot == null || pos == null) {
            return null;
        }
        Scope scope = SCOPES.get(bot.getUUID().toString());
        if (scope == null) {
            return null;
        }
        Predicate<BlockPos> predicate = targetSide ? scope.isTaskTarget() : scope.isChannelCell();
        if (predicate == null) {
            return null;   // 本任务只装了另一面 ⇒ 这一面不受保护
        }
        String code = targetSide ? CODE : CHANNEL_CODE;
        try {
            return predicate.test(pos) ? code : null;
        } catch (RuntimeException exception) {
            BotLog.warn("[TaskTargetProtection] {} 谓词抛异常 ⇒ 按**受保护**处理（owner={}）：{}",
                    targetSide ? "目标" : "通道层", scope.owner(), exception.toString());
            return code;
        }
    }

    /** 是否有任务在保护（判据/日志用）。 */
    public static boolean active(ServerPlayer bot) {
        return bot != null && SCOPES.containsKey(bot.getUUID().toString());
    }

    public static String describe(ServerPlayer bot) {
        if (bot == null) {
            return "无";
        }
        Scope scope = SCOPES.get(bot.getUUID().toString());
        return scope == null ? "无" : scope.owner();
    }

    /** 夹具收尾用（避免测试之间互相污染）。 */
    public static void reset() {
        SCOPES.clear();
    }
}
