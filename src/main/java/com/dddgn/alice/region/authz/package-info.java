/**
 * ⭐ **区域授权（`region/authz/`）** —— 回答「**这一格现在允不允许动**」。
 *
 * <p>⚠️ **当前状态：裁决面已入住（2026-10-01 刀 4 第 4 件，用户裁定「拆三个谓词后定名」）。**
 * 本包今天装**三个谓词 ＋ 一个唯一入口**（结构提案 `§5`）：
 * <ul>
 *   <li>{@link com.dddgn.alice.region.authz.AreaPermission} —— ⭐ **唯一入口**；回答 **D 维**（这一格允不允许动：
 *       认领 · job 区覆盖 · 理由声明 · 刀 2 的位置规则）；</li>
 *   <li>{@link com.dddgn.alice.region.authz.AreaPermissionLevel} —— **E 维**（授权到哪一档）；
 *       ⚠️ **包内可见** ⇒ 编译器保证**没有第二个授权入口**；</li>
 *   <li>{@link com.dddgn.alice.region.authz.Quota} —— **F 维**（还能动几次）＋ 既有的执行期预算。</li>
 * </ul>
 * ⛔ **留痕不在这里**（2026-10-01 用户裁定）：授权放行留痕搬去了
 * {@code ledger/ModifyAudit#logAllowGrant} —— 它回答"**谁被授予了**"，与"实际写了什么"同族。
 * ⚠️ **`protection/ZoneAuthority` 已整体消失**（裁决面进本包、留痕进 `ledger/`、额度进 `Quota`）。
 *
 * <p>@alice-skeleton 骨架（9 份 `package-info.java` 统一节名与次序，2026-10-02 用户裁定「内容结构必须统一」）：① 谁进得来（准入） · ② 这个包是什么 · ③ 判据 · ④ 本包 ⛔ 不做什么 · ⑤ 回收条件 · ⑥ 门禁 · ⑦ 沿革 / 未裁 · ⑧ 今天的状态。⭐ 节号**按角色固定**，所以本文件的号**会跳** —— 那不表示缺内容，只表示本包没有这个角色；⛔ 空节比缺节更坏，所以不许补空标题。
 *
 * <h2>② 这个包是什么（为什么嵌在 `region/` 里 —— ⛔ 不是平级）</h2>
 *
 * <p>用户 2026-10-01 第六轮的论据（逐字）：「**authz 在实际结果上只对 region 有作用，
 * 非 region 没有意义，两个概念是互相绑定的**」。
 * ⭐ **实测支持他**：{@code AreaPermission.authorize} 在
 * {@code AreaData.isClaimed(...) == false} 时**直接返回 `NOT_GATED`** ⇒
 * **`authz` 的作用域确实 = `region`**（未认领的格子根本不进这条判据）。
 * ⇒ 采纳 `region/authz/`（结构提案 `§9.1` 的"改荐 (b)"**已被推翻**，最终 `§9.3` #7 落 (a)）。
 *
 * <h2>③ 判据（⭐⭐ 嵌套的前提 —— ⛔ 不满足就是"用容器名撒谎"）</h2>
 *
 * <p>用户同轮裁定的口径：`authz/` **只剩「谓词 ＋ 初始权限表 ＋ 覆盖检查」**。
 * ⇒ 下面三件**必须先踢出去**，⛔ 否则本包会变成"把三个维度塞进「区域管理」名下"：
 *
 * <table border="1">
 *   <tr><th>件</th><th>它是什么</th><th>去哪</th><th>今天在哪</th></tr>
 *   <tr><td>{@code Tenure}</td><td><b>地理归属</b>（Alice 的地 / 别人的地）</td>
 *       <td>留**写入维度的策略矩阵**里</td>
 *       <td>⚠️ **仍未踢**：{@code write/WritePolicyMatrix.Tenure}（与 region **无关**的第三个义；
 *       去留见 `D-563`/`O116`，排在最后）</td></tr>
 *   <tr><td>{@code ModifyAudit}</td><td><b>留痕 / 归因账</b></td>
 *       <td>{@code ledger/}</td><td>✅ **已落**（刀 4 ①：{@code ledger/ModifyAudit}；授权放行留痕
 *       {@code logAllowGrant} 也在那儿）</td></tr>
 *   <tr><td>{@code Quota}</td><td><b>额度</b>（还能动几次）</td>
 *       <td>⚠️ 结构提案原荐 `job/` 侧，⭐ **用户 2026-10-01 第二次裁定改为 `region/authz/`**
 *       （落 `job/` 会造 12 包反向依赖、门禁抓不到 ⇒ 台账 `O131`）</td>
 *       <td>✅ **已落**：{@code region/authz/Quota}（605 行 / 26 生产文件引用面）</td></tr>
 * </table>
 *
 * <p>⇒ ⭐ **刀 4 的顺序（已走完）**：① `ModifyAudit`→`ledger/` · ② `WriteGrant`→`Attribution` 原语 ·
 * ③ `WriteBudget`→`Quota` · ④ 拆 `AreaPermission`（本包入住）。
 *
 * <p><b>（b）本包装的那一个判据（拆完之后的实测形状）</b>
 *
 * <p>入口是 {@code AreaPermission}（"**一个判据，多处消费**"：候选扫描 ×2 · 破坏闸门 · 放置闸门 ·
 * 能力闸门**都问同一个入口**，⛔ 不许各写一套 —— 重演"六份重复"的旧账）。
 * 拒绝码是**稳定词表**（`protected_area` / `job_region_read_only` / `job_region_break_not_allowed` /
 * `job_region_place_not_scaffold` / `job_region_place_quota` / `job_region_reason_required` /
 * `protected_block_entity`）—— 调用方、日志、夹具都按它归因。
 *
 * <p>⚠️ **一处口径更正（2026-10-01 拆之前实测，别再沿用旧说法）**：结构提案 `§5`/`§2` 说
 * "330 行、**一个函数**装三件事" —— **不成立**。实测：`authorize` 函数 **96 行（其中注释 36 行
 * ⇒ 代码 61 行）**，D/E/F 三块的代码量分别是 ≈28 / ≈15 / **6** 行；330 是**整个类**的行数，
 * 而类里过半是 javadoc ＋ 门面包装（6 个入口）＋ 留痕 ＋ `permanentDenial`。
 * ⇒ **拆仍然是对的**（三个问题确实是三个问题、且各自会独立变），但"臃肿"的**物理位置**要说准：
 * 拆完剩下的是**门面**，不是"三个纠缠的判据"。
 *
 * <h2>⑥ 门禁</h2>
 *
 * <p>⚠️ <b>本包今天没有门禁盯着</b> —— 结构提案 `§7` #6 的方向断言
 * （`region/authz/` 不许 import `{task, action, job}` · `region/` 不许 import `{task, job}`）**尚未裁定**
 * ⇒ ⛔ 本节不假装它已生效。
 *
 * <h2>⑦ 沿革 / 未裁</h2>
 *
 * <p>⚠️ <b>未裁</b>：上面那条方向断言尚未裁定 ⇒ 如实登记在台账 `O130`。
 */
package com.dddgn.alice.region.authz;
