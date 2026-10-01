/**
 * ⭐ **区域授权（`region/authz/`）** —— 回答「**这一格现在允不允许动**」。
 *
 * <p>⚠️⚠️ **当前状态：只立家，零入住。** 本包今天<b>只有这一个文件</b> ——
 * 结构提案 `§8` 步 2 只要求「**建两包骨架 ＋ 搬 3 个原样类**」，而 3 个原样类
 * （`JobAreaRegistry` / `ClaimService` / `MapGeometry`）都属**区域本体**，落 {@code region/} 顶层
 * ⇒ ⛔ **本包现在是空的**。往里搬东西是**刀 4**（`§8` 步 5）。
 *
 * <h2>一 · 为什么嵌在 `region/` 里（⛔ 不是平级）</h2>
 *
 * <p>用户 2026-10-01 第六轮的论据（逐字）：「**authz 在实际结果上只对 region 有作用，
 * 非 region 没有意义，两个概念是互相绑定的**」。
 * ⭐ **实测支持他**：{@code protection/ZoneAuthority.java} 在
 * {@code AreaData.isClaimed(...) == false} 时**直接返回 `NOT_GATED`** ⇒
 * **`authz` 的作用域确实 = `region`**（未认领的格子根本不进这条判据）。
 * ⇒ 采纳 `region/authz/`（结构提案 `§9.1` 的"改荐 (b)"**已被推翻**，最终 `§9.3` #7 落 (a)）。
 *
 * <h2>二 ⭐⭐ · 嵌套的**前提**（⛔ 不满足就是"用容器名撒谎"）</h2>
 *
 * <p>用户同轮裁定的口径：`authz/` **只剩「谓词 ＋ 初始权限表 ＋ 覆盖检查」**。
 * ⇒ 下面三件**必须先踢出去**，⛔ 否则本包会变成"把三个维度塞进「区域管理」名下"：
 *
 * <table border="1">
 *   <tr><th>件</th><th>它是什么</th><th>去哪</th><th>今天在哪（⛔ 还没踢）</th></tr>
 *   <tr><td>{@code Tenure}</td><td><b>地理归属</b>（Alice 的地 / 别人的地）</td>
 *       <td>留**写入维度的策略矩阵**里</td>
 *       <td>{@code write/WritePolicyMatrix.Tenure}（与 region **无关**的第三个义）</td></tr>
 *   <tr><td>{@code ModifyAudit}</td><td><b>留痕 / 归因账</b></td>
 *       <td>{@code ledger/}</td><td>{@code write/WriteAudit}</td></tr>
 *   <tr><td>{@code Quota}</td><td><b>额度</b>（还能动几次）</td>
 *       <td>`job/` 侧（按 `scopeId` 计，⛔ 不按区域计）</td>
 *       <td>{@code write/WriteBudget}（605 行 / 63 文件引用面）</td></tr>
 * </table>
 *
 * <p>⇒ ⭐ **刀 4 的顺序**：先把这三件外移（`WriteAudit`→`ledger/` · `WriteBudget`→`Quota` ·
 * `Attribution`→`Attribution` 原语）＋ 拆 `ZoneAuthority` 成三个谓词，**然后**才谈本包的入住。
 *
 * <h2>三 · 本包要装的那一个判据（今天的形状，搬进来之前先记住）</h2>
 *
 * <p>今天它叫 {@code protection/ZoneAuthority}（330 行、一个函数装三件事 —— 这正是"授权臃肿"的
 * **物理位置**）。拆后的形状 = 「**一个判据，多处消费**」：候选扫描 ×2 · 破坏闸门 · 放置闸门 ·
 * 能力闸门**都问同一个函数**，⛔ 不许各写一套（重演"六份重复"的旧账）。
 * 拒绝码是**稳定词表**（`protected_area` / `zone_read_only` / `zone_break_not_allowed` /
 * `zone_place_not_scaffold` / `zone_place_quota` / `zone_reason_required` /
 * `protected_block_entity`）—— 调用方、日志、夹具都按它归因。
 *
 * <h2>四 ⚠️ 未裁：本包**今天没有门禁盯着**</h2>
 *
 * <p>结构提案 `§7` #6 提的方向断言（`region/authz/` 不许 import `{task, action, job}` ·
 * `region/` 不许 import `{task, job}`）**尚未裁定** ⇒ 如实登记在台账 `O130`，
 * ⛔ 本节不假装它已生效。
 */
package com.dddgn.alice.region.authz;
