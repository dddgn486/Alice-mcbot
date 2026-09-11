package com.dddgn.alice.action;

import com.dddgn.alice.log.BotLog;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;

/**
 * 执行期写入预算（D-106）：**一个任务作用域（scope）内允许修改世界的次数上限**。
 *
 * <p>与既有概念的分工（D-082 的"预算不合并"在此被有意推进一格）：
 * <ul>
 *   <li>{@link WriteGrant} 回答"**谁**、**为什么**"（授权，指向这一格/这一次）；</li>
 *   <li>`MiningBudget.maxExtraBreakTicks` 回答"这次挖掘**值得**拆多少"（规划期选站位）；</li>
 *   <li>{@link SearchBudget} 回答"搜索能烧多少"；</li>
 *   <li>本类回答"这个任务**已经改了世界多少**，还允许改多少"——执行期硬闸门。</li>
 * </ul>
 *
 * <p>为什么必须有：授权只约束"这一格能不能拆"，不约束"拆了多少格"。内核清障
 * （{@code PATH_ACCESS} / {@code SUPPORT_PLACEMENT}）是"有多少拆多少"：目标不可达时它会一路
 * 试下去，失败语义只体现在段超时/重规划上，**没有任何"到此为止"的世界侧上限**。
 * 对 Baritone 而言这不是缺口（写入由权限 + 成本 + `maxCostIncrease` 取消天然约束，
 * `CalculationContext.java:104/156/175`、`MovementHelper.java:73`，**没有计数上限**）；
 * 对 Alice 则是目标层差异——服务端权威 + 可审计 + 可归因，需要一条明确的"不许再改"。
 *
 * <p>计数与归因口径：
 * <ul>
 *   <li>键 = {@code WorldModLedger} 的当前 scopeId（一次任务一个作用域）；</li>
 *   <li>破坏与放置**分开计**（成本与后果不同）；</li>
 *   <li>回收我方临时放置（{@link WriteReason#SCAFFOLD_RESTORE}）**豁免上限但仍计数**
 *       ——否则"谁建谁拆"会被自己的预算卡死（J6 教训）；</li>
 *   <li>没有打开作用域时**不设上限**，但记一次可见日志（不静默丢弃归因）。</li>
 * </ul>
 *
 * <p>超限语义（用户 2026-09-11 裁定）：任务级超限 = **硬停 + 如实报告**，
 * 不自动补恢复（恢复仍按 D-103 的"会话内、人还在脚手架上时做"）。
 */
public final class WriteBudget {

    /** 任务级破坏上限（用户 2026-09-11 裁定接受 64 起点；按观测再收紧）。 */
    public static final int DEFAULT_MAX_BREAKS = 64;
    /** 任务级放置上限（同上，32 起点）。 */
    public static final int DEFAULT_MAX_PLACES = 32;

    /** 判定结果。 */
    public enum Verdict {
        /** 计数已累加，允许写入。 */
        ALLOW,
        /** 豁免上限（回收我方临时放置），已单独计数，允许写入。 */
        ALLOW_EXEMPT,
        /** 超限：**必须拒绝写入**。 */
        REFUSED
    }

    /** 上限（可按作用域覆写：夹具用它构造"预算不足"场景，不暴露给玩家命令）。 */
    public record Caps(int maxBreaks, int maxPlaces) {
        public static final Caps DEFAULT = new Caps(DEFAULT_MAX_BREAKS, DEFAULT_MAX_PLACES);

        public Caps {
            if (maxBreaks < 0 || maxPlaces < 0) {
                throw new IllegalArgumentException("caps must be non-negative");
            }
        }
    }

    private static final class Counters {
        int breaks;
        int places;
        int exemptBreaks;
        int exemptPlaces;
        int refusedBreaks;
        int refusedPlaces;
        boolean breakExhausted;
        boolean placeExhausted;
        boolean loggedNoScope;
    }

    private static final Map<String, Counters> SCOPES = new HashMap<>();
    private static final Map<String, Caps> CAPS = new HashMap<>();

    private WriteBudget() {
    }

    // ==================== 作用域 ====================

    /** 当前作用域 id；没有打开的作用域时返回 {@code null}（不设上限，但记日志）。 */
    public static String scopeOf(ServerPlayer bot) {
        if (bot == null) {
            return null;
        }
        return com.dddgn.alice.ledger.WorldModLedger.currentScope(bot.getServer(), bot.getUUID());
    }

    /** 覆写某个作用域的上限（**夹具专用**：构造"预算不足"自检，不接玩家命令入口）。 */
    public static void setCaps(String scopeId, Caps caps) {
        if (scopeId == null) {
            return;
        }
        CAPS.put(scopeId, caps);
    }

    /** 作用域收尾：输出一行可观测 SUMMARY 并清账。 */
    public static void closeScope(String scopeId) {
        if (scopeId == null) {
            return;
        }
        Counters counters = SCOPES.remove(scopeId);
        Caps caps = CAPS.remove(scopeId);
        if (counters == null) {
            return;
        }
        Caps effective = caps == null ? Caps.DEFAULT : caps;
        BotLog.info("[WriteBudget] SUMMARY scope={} breaks={}/{} places={}/{} exemptBreaks={}"
                        + " exemptPlaces={} refusedBreaks={} refusedPlaces={} exhausted={}",
                scopeId, counters.breaks, effective.maxBreaks(), counters.places, effective.maxPlaces(),
                counters.exemptBreaks, counters.exemptPlaces, counters.refusedBreaks, counters.refusedPlaces,
                counters.breakExhausted || counters.placeExhausted);
    }

    // ==================== 判定 ====================

    /** 破坏预算判定（**累加计数**；被调用即视为一次真实破坏即将发生）。 */
    public static Verdict consumeBreak(ServerPlayer bot, ServerLevel level, BlockPos pos, WriteGrant grant) {
        String scope = scopeOf(bot);
        if (scope == null) {
            logNoScope(bot, "break", pos);
            return Verdict.ALLOW;
        }
        Counters counters = SCOPES.computeIfAbsent(scope, key -> new Counters());
        Caps caps = CAPS.getOrDefault(scope, Caps.DEFAULT);
        if (grant != null && grant.reason() == WriteReason.SCAFFOLD_RESTORE) {
            // 建拆同权：回收我方临时放置不受破坏上限约束（否则恢复会被自己的预算卡死）
            counters.exemptBreaks++;
            return Verdict.ALLOW_EXEMPT;
        }
        if (counters.breaks >= caps.maxBreaks()) {
            counters.refusedBreaks++;
            if (!counters.breakExhausted) {
                counters.breakExhausted = true;
                BotLog.warn("[WriteBudget] exhausted scope={} action=break pos={} by={} breaks={}/{}"
                                + " → 本任务停止继续破坏",
                        scope, pos.toShortString(), grant == null ? "-" : grant.describe(),
                        counters.breaks, caps.maxBreaks());
            }
            return Verdict.REFUSED;
        }
        counters.breaks++;
        return Verdict.ALLOW;
    }

    /** 放置预算判定（**累加计数**）。 */
    public static Verdict consumePlace(ServerPlayer bot, ServerLevel level, BlockPos pos, WriteGrant grant) {
        String scope = scopeOf(bot);
        if (scope == null) {
            logNoScope(bot, "place", pos);
            return Verdict.ALLOW;
        }
        Counters counters = SCOPES.computeIfAbsent(scope, key -> new Counters());
        Caps caps = CAPS.getOrDefault(scope, Caps.DEFAULT);
        if (counters.places >= caps.maxPlaces()) {
            counters.refusedPlaces++;
            if (!counters.placeExhausted) {
                counters.placeExhausted = true;
                BotLog.warn("[WriteBudget] exhausted scope={} action=place pos={} by={} places={}/{}"
                                + " → 本任务停止继续放置",
                        scope, pos.toShortString(), grant == null ? "-" : grant.describe(),
                        counters.places, caps.maxPlaces());
            }
            return Verdict.REFUSED;
        }
        counters.places++;
        return Verdict.ALLOW;
    }

    /**
     * 记录一次"**提前判定**被拒的放置"（`placeAt`/`placeBulkEdit` 在尝试写入前就拒绝，
     * 不经过 {@link #consumePlace}，因此拒绝计数要在这里补上，否则 SUMMARY 会漏报）。
     *
     * <p>与破坏侧的差别：破坏的执行期拒绝走 `beginBreak → consumeBreak`，已经计数正确；
     * 而 `breakable(...)` 是**搜索谓词**（一次规划会被调用成千上万次），**不在这里计数**——
     * 拒绝次数只统计"真的打算动手"的那一次。
     */
    public static void notePlaceRefusal(ServerPlayer bot, BlockPos pos, WriteGrant grant) {
        String scope = scopeOf(bot);
        if (scope == null) {
            return;
        }
        Counters counters = SCOPES.computeIfAbsent(scope, key -> new Counters());
        Caps caps = CAPS.getOrDefault(scope, Caps.DEFAULT);
        counters.refusedPlaces++;
        if (!counters.placeExhausted) {
            counters.placeExhausted = true;
            BotLog.warn("[WriteBudget] exhausted scope={} action=place pos={} by={} places={}/{}"
                            + " → 本任务停止继续放置",
                    scope, pos.toShortString(), grant == null ? "-" : grant.describe(),
                    counters.places, caps.maxPlaces());
        }
    }

    // ==================== 只读查询（内核谓词/计划期用） ====================

    /**
     * **计划期**剪枝（D-106 Slice B）：这条边**至少**要 b 次破坏 / p 次放置时，是否还在预算内。
     *
     * <p>语义是"下界"检查（一条写边至少写 1 次），因此**不会过度剪枝**合法路径；
     * 真正的"绝不超过上限"仍由执行期闸门（{@link #consumeBreak}/{@link #consumePlace}）保证。
     * 任一桶**已经**耗尽时，对任何写入边一律返回 {@code false} —— 这正是"耗尽后本次尝试
     * 降级为纯通行"的实现（否则 bot 会换成另一种写入方式继续试：夹具日志实测过这个病）。
     */
    public static boolean plannedWritesAllowed(ServerPlayer bot, int minBreaks, int minPlaces) {
        String scope = scopeOf(bot);
        if (scope == null) {
            return true;
        }
        Counters counters = SCOPES.get(scope);
        if (counters == null) {
            return true;
        }
        if (counters.breakExhausted || counters.placeExhausted) {
            return false;
        }
        Caps caps = CAPS.getOrDefault(scope, Caps.DEFAULT);
        if (minBreaks > 0 && counters.breaks + minBreaks > caps.maxBreaks()) {
            return false;
        }
        return minPlaces <= 0 || counters.places + minPlaces <= caps.maxPlaces();
    }

    /** 剩余破坏额度（计划级检查用；无作用域 = 无限）。 */
    public static int remainingBreaks(ServerPlayer bot) {
        String scope = scopeOf(bot);
        if (scope == null) {
            return Integer.MAX_VALUE;
        }
        Counters counters = SCOPES.get(scope);
        return counters == null ? CAPS.getOrDefault(scope, Caps.DEFAULT).maxBreaks()
                : Math.max(0, CAPS.getOrDefault(scope, Caps.DEFAULT).maxBreaks() - counters.breaks);
    }

    /** 剩余放置额度（计划级检查用；无作用域 = 无限）。 */
    public static int remainingPlaces(ServerPlayer bot) {
        String scope = scopeOf(bot);
        if (scope == null) {
            return Integer.MAX_VALUE;
        }
        Counters counters = SCOPES.get(scope);
        return counters == null ? CAPS.getOrDefault(scope, Caps.DEFAULT).maxPlaces()
                : Math.max(0, CAPS.getOrDefault(scope, Caps.DEFAULT).maxPlaces() - counters.places);
    }

    /** 已拒绝的破坏次数（诊断/夹具断言）。 */
    public static int refusedBreaks(ServerPlayer bot) {
        String scope = scopeOf(bot);
        Counters counters = scope == null ? null : SCOPES.get(scope);
        return counters == null ? 0 : counters.refusedBreaks;
    }

    /** 已拒绝的放置次数（诊断/夹具断言）。 */
    public static int refusedPlaces(ServerPlayer bot) {
        String scope = scopeOf(bot);
        Counters counters = scope == null ? null : SCOPES.get(scope);
        return counters == null ? 0 : counters.refusedPlaces;
    }

    /**
     * 现在还允许破坏吗（**不计数**，供内核谓词/搜索剪枝用）。
     *
     * <p>与 {@link #consumeBreak} 用同一判据，因此"搜索认为可以破坏"与"执行时允许破坏"
     * 始终一致。回收我方临时放置（{@code SCAFFOLD_RESTORE}）不受上限约束。
     */
    public static boolean breakAllowed(ServerPlayer bot, WriteGrant grant) {
        if (grant != null && grant.reason() == WriteReason.SCAFFOLD_RESTORE) {
            return true;
        }
        String scope = scopeOf(bot);
        if (scope == null) {
            return true;
        }
        Counters counters = SCOPES.get(scope);
        Caps caps = CAPS.getOrDefault(scope, Caps.DEFAULT);
        return counters == null || counters.breaks < caps.maxBreaks();
    }

    /** 现在还允许放置吗（**不计数**）。 */
    public static boolean placeAllowed(ServerPlayer bot) {
        String scope = scopeOf(bot);
        if (scope == null) {
            return true;
        }
        Counters counters = SCOPES.get(scope);
        Caps caps = CAPS.getOrDefault(scope, Caps.DEFAULT);
        return counters == null || counters.places < caps.maxPlaces();
    }

    /** 是否已经发生过"预算耗尽拒绝"（诊断/摘要用）。 */
    public static boolean breakExhausted(ServerPlayer bot) {
        String scope = scopeOf(bot);
        Counters counters = scope == null ? null : SCOPES.get(scope);
        return counters != null && counters.breakExhausted;
    }

    /** 是否已经发生过"预算耗尽拒绝"（诊断/摘要用）。 */
    public static boolean placeExhausted(ServerPlayer bot) {
        String scope = scopeOf(bot);
        Counters counters = scope == null ? null : SCOPES.get(scope);
        return counters != null && counters.placeExhausted;
    }

    /** 只读快照（诊断/夹具断言用）：`breaks=n/max places=n/max exhausted=?`。 */
    public static String describe(ServerPlayer bot) {
        String scope = scopeOf(bot);
        if (scope == null) {
            return "scope=<none>";
        }
        Counters counters = SCOPES.get(scope);
        Caps caps = CAPS.getOrDefault(scope, Caps.DEFAULT);
        if (counters == null) {
            return "scope=" + scope + " breaks=0/" + caps.maxBreaks() + " places=0/" + caps.maxPlaces()
                    + " exhausted=false";
        }
        return "scope=" + scope + " breaks=" + counters.breaks + "/" + caps.maxBreaks()
                + " places=" + counters.places + "/" + caps.maxPlaces()
                + " exemptBreaks=" + counters.exemptBreaks + " exemptPlaces=" + counters.exemptPlaces
                + " exhausted=" + (counters.breakExhausted || counters.placeExhausted);
    }

    /** 只读计数（夹具断言用）。 */
    public static int breaks(ServerPlayer bot) {
        String scope = scopeOf(bot);
        Counters counters = scope == null ? null : SCOPES.get(scope);
        return counters == null ? 0 : counters.breaks;
    }

    /** 只读计数（夹具断言用）。 */
    public static int places(ServerPlayer bot) {
        String scope = scopeOf(bot);
        Counters counters = scope == null ? null : SCOPES.get(scope);
        return counters == null ? 0 : counters.places;
    }

    private static void logNoScope(ServerPlayer bot, String action, BlockPos pos) {
        // 没有任务作用域时不做上限（作用域是记账单位），但要在日志里留痕——"缺口不静默"
        BotLog.info("[WriteBudget] no_scope action={} pos={} bot={} （未计入任务预算）",
                action, pos.toShortString(), bot == null ? "-" : bot.getName().getString());
    }
}
