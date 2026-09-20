package com.dddgn.alice.action;

import com.dddgn.alice.log.BotLog;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;

/**
 * **清障不得吃掉任务目标**（`D-362`，用户 2026-09-20 修正口径）。
 *
 * <p>用户原话：「先不管不是不修复，**对清障目标和任务目标的区分还是要修复**，即使是绕过去；
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

    private record Scope(String owner, Predicate<BlockPos> isTaskTarget) {
    }

    private static final Map<String, Scope> SCOPES = new HashMap<>();

    private TaskTargetProtection() {
    }

    /** 任务开始：登记"本任务的目标方块"谓词（`owner` 只用于归因日志）。 */
    public static void begin(ServerPlayer bot, String owner, Predicate<BlockPos> isTaskTarget) {
        if (bot == null || isTaskTarget == null) {
            return;
        }
        SCOPES.put(bot.getUUID().toString(), new Scope(owner == null ? "unknown" : owner, isTaskTarget));
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
        if (bot == null || pos == null) {
            return null;
        }
        Scope scope = SCOPES.get(bot.getUUID().toString());
        if (scope == null) {
            return null;
        }
        try {
            return scope.isTaskTarget().test(pos) ? CODE : null;
        } catch (RuntimeException exception) {
            BotLog.warn("[TaskTargetProtection] 目标谓词抛异常 ⇒ 按**受保护**处理（owner={}）：{}",
                    scope.owner(), exception.toString());
            return CODE;
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
