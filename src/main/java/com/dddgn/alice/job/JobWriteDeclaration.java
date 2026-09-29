package com.dddgn.alice.job;

import java.util.Objects;

/**
 * ⚠️ **（临时）作业级写入声明** —— 改革 ① **自带的最小载体**（`D-511`，用户 2026-09-28「我选甲」）。
 *
 * <h2>⛔ 这是一个「（临时）」载体，不是一个新架构</h2>
 *
 * <p>它**只装 `K2` 今天需要的那两栏**（`§28.2` 逐字）：
 * <b>① 授权来源</b>（`D-500` §IV：`D-076` 过期的是载体「逐请求白名单」，留的是"授权来源 = 上游作业级声明"）
 * ＋ <b>② 预算闸门所需量</b>（`MiningBudget.maxExtraBreakTicks` 的分档值）。
 *
 * <p><b>为什么必须有它</b>：`K2` 要把 `B` 分支（站位枚举 → `MiningPlan.Mode.TUNNEL`）交给内核，
 * 而 `D-076` 的授权**必须自重挂**（`DS-9`）—— 内核对"能不能写世界"只认**来源 + 预算闸门**。
 * 而"作业级声明"的**正式**载体（`P4/A` 能力信封 + `P5.1/A` 落点 + `JobDeclaration`）属于
 * **"改革 ① 做完之后才补全"**那一批 ⇒ 改革 ① 只能**自带一个只够用的**。
 *
 * <h2>⚠️ 已知风险（设计文档自己点名的，登记不删）</h2>
 *
 * <p>`§28` 逐字：甲 的代价 = 「⚠️ 可能造出"**第二个载体**"」——
 * 即 `D-419`（`B3` 能力清单单一真相源）所防的"**第二真相源**"病。
 * ⇒ <b>本类必须<u>不是</u>授权真相源</b>：今天的活真相源仍是
 * `pathing.core.WriteEnvelopes`（从 `allowedMovementTypes` **推导**出来的信封，
 * `PathRequest` 唯一构造点自动 `note`）与 `MiningBudget` / `WriteBudget`。
 * ⛔ **本类不得被接成"第二份授权名单"**；它的**唯一消费者今天 = 夹具**
 * （`task/K2AdjacentGoalCheckTask`，顺带跑 `1a`=甲 的"换脚格"回路）。
 * ⚠️ 真接线（把 `B` 分支换成它）是**下一刀**，且那一刀必须**同时**让
 * `WriteEnvelopes`/逐请求白名单**退出**——否则就真的变成两个真相源。
 *
 * <h2>⛔ 它**不**含什么（让步范围，`§28.2` 逐字）</h2>
 *
 * <ul>
 *   <li>⛔ `P5/A` 的**资源清单**（工具族 / 一次性方块 / 空位 / 产物归属）；</li>
 *   <li>⛔ `P5/A` 的**归因码表**；</li>
 *   <li>⛔ `P13/A` 的**预期产物**；</li>
 *   <li>⛔ `P1/A′` 的 **`unit`**。</li>
 * </ul>
 * ⇒ 上面任何一项**想加进来之前**，先看 `docs/plans/2026-09-28-站位选优形态-设计讨论.md` 的 `§28`：
 * 它们**按裁定属于 `JobDeclaration`**，不属于本临时载体（否则就是把框架补全的内容提前造一遍）。
 *
 * <h2>⭐ 回收条件（到点必须删，判据要能机械核）</h2>
 *
 * <p><b>本类必须在「`JobDeclaration` 拆两层」那一刀里<u>并进 `JobDeclaration`</u> 并<u>同刀删除</u>。</b>
 * <ul>
 *   <li>判据 = ⭐ **旧类型零残留**（`grep -rn 'JobWriteDeclaration' src/` ⇒ 0）＋ ⭐ **同一字段只有一个载体**；</li>
 *   <li>⚠️ ⚠️ **勘误（2026-09-29，`D-517`）**：`D-511` 原文把回收条件写成
 *       「**`P12/A`/`P4/A` 落地时**并进 `JobDeclaration` 并同刀删除」。而 `D-478` `P12/A` 的**甲 =
 *       只做纯改名**，已于 2026-09-29 **先落地**（`job.GoalSpec` → `JobDeclaration`）——
 *       **它并没有做"拆两层"那件事**。⇒ 若照字面读，本类的回收条件**在它出生之前就已经"到期"**了。
 *       ⇒ **正确的触发条件 = 「`P12/A` 的<u>拆两层</u>部分 ＋ `P4/A`」落地时**（= 瘦身 / 字段搬家 /
 *       `P5.1/A` / `P13/A` / `P10` 那一刀），**不是**已经落地的纯改名。见 `D-517` 的勘误节。</li>
 *   <li>⚠️ **诚实边界**（`§28.2` 逐字）：「同一字段只有一个载体」这条判据**今天没有门禁**
 *       ⇒ 到点只能**人工核对**，或届时补一道门禁（本轮**不立**，只登记）。</li>
 * </ul>
 *
 * <h2>⚠️ 命名注意</h2>
 *
 * <p>名字里**没有** `GoalSpec`/`Goal`：它与内核那个同名两物**无关**（`§12.1.2`）。
 * ⚠️ 也**没有**登记进 `tools/check-duplicate-class-names.py` 的豁免表 —— 因为本名字
 * **今天不重名**（该门禁的口径：豁免条目只在**真重复**时存在，且"豁免得手也要判红"）。
 *
 * @param source             **授权来源**：谁声明的（作业/调用方的稳定标识，进日志与归因）
 * @param mayBreak           本作业声明**允许破坏**地形（对应 `MovementType.changesWorld()` 的破坏族）
 * @param mayPlace           本作业声明**允许放置**方块（搭桥/垫脚族）
 * @param mayCollectDrops    掉落物收集**是否可改世界**（`D-372` / `DS-15`，2026-09-21 用户裁定）
 * @param maxExtraBreakTicks **预算闸门**：额外破坏可烧的 tick 上界（`MiningBudget` 的分档量，非时长承诺）
 * @param maxFootRetries     ⭐ `1b`=丙 的**上界默认值**（见 {@link #DEFAULT_MAX_FOOT_RETRIES}）——
 *                           它是 `DS-19`（统一的时间限制 + 重试限制）那条统一账里的**一条默认值**，
 *                           ⛔ **刻意不独立立常量**、⛔ 不对外当旋钮。
 */
public record JobWriteDeclaration(
        String source,
        boolean mayBreak,
        boolean mayPlace,
        boolean mayCollectDrops,
        int maxExtraBreakTicks,
        int maxFootRetries
) {

    /**
     * ⭐ **`1b`=丙 的上界默认值**（`D-502` / `§49.1`）：**换脚格**重试的上界。
     *
     * <p>裁定逐字：「**上界不独立立常量**。它作为 `DS-19`（「统一的时间限制 + 重试限制」）
     * 那条统一账里的一条默认值；本轮按 `#16` 的最小载体落 ⇒ 带「（临时）」标记 ＋
     * 回收条件 = **并进 `DS-19` 统一账**。」
     *
     * <p>⛔ **刻意 `private`**（不是 `public static final` 的旋钮）：
     * 一旦它变成可被别处点名引用的常量，就又成了"第二真相源"（`D-419` 要防的病）。
     * 唯一出口是记录组件 {@link #maxFootRetries()}，消费者是夹具。
     *
     * <p>⛔ **⛔ 被否掉的两个替代**（`D-502` 逐字，别再提）：
     * 「甲（复用 `MAX_APPROACH_PLANS`）被否：**语义不同**（它数全预算 `A*`）＋ **随 `B` 消失**」·
     * 「乙（新常量）被否：与已裁的 `C-5`「重入队机制**需重构**」相抵」。
     *
     * <p>⚠️ **`P4/A` 铁律照旧**：**夹具断"次数"，时间只进日志** —— 所以这个量的断言面是
     * "试了几格"，⛔ 不是"花了多少毫秒"。
     */
    private static final int DEFAULT_MAX_FOOT_RETRIES = 4;

    public JobWriteDeclaration {
        source = Objects.requireNonNull(source, "source");
        if (source.isBlank()) {
            throw new IllegalArgumentException("source 不能是空白 —— 授权来源必须可归因（D-341 口径）");
        }
        if (maxExtraBreakTicks < 0) {
            throw new IllegalArgumentException("maxExtraBreakTicks 必须 ≥ 0");
        }
        if (maxFootRetries <= 0) {
            throw new IllegalArgumentException("maxFootRetries 必须 > 0（0 次重试 = 第一次不通就放弃，"
                    + "那不是「上界」，是「不重试」；要表达后者请显式传 1 并在归因里说清）");
        }
    }

    /** 挖掘类作业的最小声明（`K2` 今天要的那两栏）：允许破坏 + 允许放置，用 {@link #DEFAULT_MAX_FOOT_RETRIES}。 */
    public static JobWriteDeclaration mining(String source, int maxExtraBreakTicks) {
        return new JobWriteDeclaration(source, true, true, false, maxExtraBreakTicks,
                DEFAULT_MAX_FOOT_RETRIES);
    }

    /** 人类可读一行 —— 进日志用。⛔ 不是给 LLM 的自述（那是 `P13/A`，不属本临时载体）。 */
    public String describe() {
        return "jobWrite(source=" + source
                + " break=" + mayBreak + " place=" + mayPlace + " collectDrops=" + mayCollectDrops
                + " maxExtraBreakTicks=" + maxExtraBreakTicks
                + " maxFootRetries=" + maxFootRetries + ")";
    }
}
