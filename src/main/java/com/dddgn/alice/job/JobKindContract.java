package com.dddgn.alice.job;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * **每个 kind 的"成功必须能用世界事实对账"声明表**（`D-349` / 勘测侧 Pit 1）。
 *
 * <h2>它防的是什么（为什么不是散文）</h2>
 * 提案 `FINAL_FORM §11` 的验收判据只查"**有没有** `successCriterion`"（存在性），
 * **不查"它在世界里判不定得下来"**（可判定性）。这个缺口在本周被咬了三次：
 * <ul>
 *   <li>`D-345/346`：追取上限（`SEARCH_LIMIT`）小于自己的作用域直径 ⇒ 判据永远不可能成立；</li>
 *   <li>⭐ `D-348`：判据拿"tick 末在不在实体查找表里"当**代理**，而真事实是"**登记被推迟** 1~19 tick"
 *       ⇒ 差一点把"真的会进世界的掉落物"当成"没生成"丢掉。</li>
 * </ul>
 * ⇒ 规则：**每个 kind 必须声明三件事**，且其中"读世界事实的那个方法"由门禁
 * （`tools/check-job-kind-contracts.sh`，挂在 `check-all` 上）**核对真的存在** —— 这样"声明"就钉在
 * 真代码上，而不是一句人话。
 *
 * <h2>三件事（缺一 ⇒ 门禁红）</h2>
 * <ol>
 *   <li>{@link Contract#successCriterion()}：**成功判据**（与谁对账、怎么看得出成了）；</li>
 *   <li>{@link Contract#queryRef()}：**读世界事实**的方法（格式 `类名#方法名`，必须真实存在）；</li>
 *   <li>{@link Contract#onMismatch()}：**对不上时怎么办**（如实归因，不许静默成功）。</li>
 * </ol>
 *
 * <h2>怎么用（将来接队列时必须照办）</h2>
 * 新增 kind ⇒ ① 在本表加一行；② `tools/check-job-kind-contracts.sh` 会核对"`JobRequest.Kind` 的每个值
 * 都在表里、三个字段都非空、`queryRef` 指向的方法真的在源码里" ⇒ 缺一项**构建红**。
 * 队列的入队拒绝（`§11` 判据 1）应当直接复用 {@link #isComplete(JobRequest.Kind)}，不要再写第二份判据。
 */
public final class JobKindContract {

    /**
     * 一个 kind 的契约。
     *
     * @param kind             与之绑定的任务种类
     * @param successCriterion 成功判据（**必须能在世界里判**：写清与什么事实对账）
     * @param queryRef         **读该世界事实**的方法，格式 `类名#方法名`（门禁核对存在性）
     * @param onMismatch       对账不一致时的**如实**处理（禁止静默成功）
     */
    public record Contract(JobRequest.Kind kind, String successCriterion, String queryRef, String onMismatch) {
        public boolean complete() {
            return successCriterion != null && !successCriterion.isBlank()
                    && queryRef != null && queryRef.contains("#")
                    && onMismatch != null && !onMismatch.isBlank();
        }

        public String describe() {
            return kind + " ⇒ 判据「" + successCriterion + "」｜对账「" + queryRef + "」｜不一致「"
                    + onMismatch + "」";
        }
    }

    private static final Map<JobRequest.Kind, Contract> TABLE = build();

    private static Map<JobRequest.Kind, Contract> build() {
        Map<JobRequest.Kind, Contract> table = new LinkedHashMap<>();
        for (Contract c : new Contract[] {
            // ---- 一次性伐木：产物是**原木进背包**，不是"看起来砍了"----
            new Contract(JobRequest.Kind.LUMBER,
                    "目标树真的被砍掉，且**原木进背包**的净增量 ≥ 本次配额",
                    "LumberJob#countLogs",
                    "如实失败/未达成（配额未满足或掉落物没到手），不静默成功"),

            // ---- 一次性挖掘：产物是**目标物品进背包**（`D-348` 咬到的正是这条）----
            new Contract(JobRequest.Kind.MINE,
                    "目标方块真的被破坏，且**目标物品进背包**的净增量 ≥ 已破坏数",
                    "MineJob#countTargetItems",
                    "如实 `FAILED product_not_collected`（挖了但没拿到 = 没成功）"),

            // ---- 可持续伐木区（MAINTAIN）：判据是"**还能不能维持**"（`D-349` Pit 2）----
            new Contract(JobRequest.Kind.REGION_LUMBER,
                    "区域内持续可作业（区域不变量）；**不可维持时必须如实登记**（无树无苗无欠）",
                    "RegionLumberJob#maintainUnreachable",
                    "登记 + 上报「可做什么」（不擅自收工：常驻只由玩家/决策层打断）"),

            // ---- 收集：清单内落物**真的进背包**（不是"走到过"）----
            new Contract(JobRequest.Kind.COLLECT,
                    "清单内的落物**进背包**（按 in-flight 账本的净增量对账）",
                    "CollectJob#dropsInRange",
                    "仍有清单落物未到手 ⇒ 如实未完成/失败，不按「去过就算」结账"),

            // ---- 合成/熔炼：产物数量对账产物栏/背包 ----
            new Contract(JobRequest.Kind.CRAFT,
                    "产物数量 ≥ 配额（**从产物栏/背包读出**的实数）",
                    "CraftJob#verify",
                    "如实 `FAILED partial_quota`（少了就是少了）")
        }) {
            table.put(c.kind(), c);
        }
        return Map.copyOf(table);
    }

    private JobKindContract() {
    }

    /** 取某 kind 的契约（**每个 `JobRequest.Kind` 都必须有**，否则门禁红）。 */
    public static Contract of(JobRequest.Kind kind) {
        return TABLE.get(kind);
    }

    /** 入队受理判据（`§11` 判据 1 的运行时形态）：缺契约/字段不全 ⇒ 不许入队。 */
    public static boolean isComplete(JobRequest.Kind kind) {
        Contract c = of(kind);
        return c != null && c.complete();
    }

    /** 全表（诊断/自检用）。 */
    public static Map<JobRequest.Kind, Contract> all() {
        return TABLE;
    }

    /** 一行摘要（`bot_report`/自检用）。 */
    public static String describe() {
        StringBuilder sb = new StringBuilder("kind 契约 ");
        for (Map.Entry<JobRequest.Kind, Contract> e : TABLE.entrySet()) {
            sb.append(e.getKey()).append(e.getValue().complete() ? "✓" : "✗").append(' ');
        }
        return sb.toString().trim();
    }
}
