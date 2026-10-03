/**
 * ⭐ **区域（`region/`）** —— 回答四个问题：**区域是什么 · 谁声明的 · 谁能覆盖谁 · 什么时候消失**。
 *
 * <p>用户 2026-10-01 逐字：「**任务区干脆改成 job 区域好了**；**authz 这个名字我采纳**；
 * `protection` 现在按含义应该改成 **region 包**，但是我的建议是**把 authz 包和 region 包采纳，
 * 而且 region 为顶层**，然后**首先要做的是整理这两个包的结构**，还要**一个一个清理带 Zone 的类**，
 * 把**这两个包原来的旧代码全部清理干净，一起重构**」。
 *
 * <p>@alice-skeleton 骨架（9 份 `package-info.java` 统一节名与次序，2026-10-02 用户裁定「内容结构必须统一」）：① 谁进得来（准入） · ② 这个包是什么 · ③ 判据 · ④ 本包 ⛔ 不做什么 · ⑤ 回收条件 · ⑥ 门禁 · ⑦ 沿革 / 未裁 · ⑧ 今天的状态。⭐ 节号**按角色固定**，所以本文件的号**会跳** —— 那不表示缺内容，只表示本包没有这个角色；⛔ 空节比缺节更坏，所以不许补空标题。
 *
 * <h2>② 这个包是什么（⭐⭐ 两个词的分工 —— 2026-10-01 用户定案，⛔ 起名与读码都按这条）</h2>
 *
 * <p>用户逐字：「**`region` 是项目里的确定概念，也就是保护区、任务区等，`area` 是一个统称的抽象概念，
 * 上层下层都能用，但是注释要写清楚**」。
 *
 * <table border="1">
 *   <tr><th>词</th><th>是什么</th><th>用在哪</th></tr>
 *   <tr><td><b>{@code region}</b></td>
 *       <td>⭐ 项目里的**确定概念**：**保护区** · **任务区（job 区）** · **安全区** ——
 *           有名字、有声明入口、有生命周期的**区域类型**（＝本包回答的四问的那个对象）</td>
 *       <td>本包名 · 区域类型本身（{@code AreaData} 的认领集 / {@code JobRegionRegistry.JobRegion}）·
 *           区域型作业与目标的招牌（`region_lumber`）</td></tr>
 *   <tr><td><b>{@code Area}</b></td>
 *       <td>⭐ **统称的抽象概念**（泛称）—— ⛔ **不是一种区域类型**，不携带权限/生命周期语义</td>
 *       <td>**上层下层都能用**：数据载体（{@code AreaData}）· 声明出来的实例（{@code JobRegion}）·
 *           作业内几何（{@code WorkingArea}）· 对"这一格"的裁决（{@code AreaPermission}/
 *           {@code AreaPermissionLevel}）· **玩家划的那块作业范围**（{@code LumberAreaState.Area}）</td></tr>
 *   <tr><td>⛔ <b>{@code zone}</b></td>
 *       <td>**已废词** —— 刀 5 只清了**类名**（你当时裁的是「一个一个清理带 `Zone` 的**类**」），
 *           **字段/方法/日志/存档键级残留仍在**</td>
 *       <td>⛔ 新代码不许再用；残留清单与分批见台账 `O135`</td></tr>
 * </table>
 *
 * <p>⚠️ **用 {@code Area} 的硬要求**：它**不携带具体语义** ⇒ **每一处都要让读者就地看懂它指什么**
 * （是玩家的那块地？作业范围？认领集？声明出来的实例？）—— ⛔ 不许只留一个 `area` 让后人猜。
 * ✅ 正例：{@code LumberAreaState.Area} 的 javadoc 逐字写了"**具体指玩家用 `/alice region set`
 * 划的那块矩形**，⛔ 不是区域类型"。
 *
 * <p>⚠️ **⛔ 不随之改的两类**（它们是**数据/接口**，不是类型名）：① **存档键**（`alice_lumber_regions` ·
 * `"region"`/`"regions"` · `alice_safe_zones`/`"safe_chunks"`）② **玩家命令与 kind 字面量**
 * （`/alice region …` · `region_lumber`）—— 改名会破既有存档/接口，⛔ 除非同刀写迁移。
 *
 * <p>⚠️ **当前状态：`protection/` → `region/` 只搬了 3 个「原样类」**（刀 3，结构提案 `§8` 步 2）：
 * {@link com.dddgn.alice.region.JobRegionRegistry}（job 区）·
 * {@link com.dddgn.alice.region.ClaimService}（区域管理的**写入口**，原 `ProtectionClaimService`）·
 * {@link com.dddgn.alice.region.MapGeometry}（区域几何，原 `ProtectionMapGeometry`）。
 * ⛔ **其余 5 个类仍在 `protection/`**（`§2` 的 9 类落点表还没走完：`AreaData` 要拆三份、
 * `LedgerScope` 去 `ledger/`、`BlockBreakSafety` 去 `action/`、
 * `ReturnPointData` 待指、`ThirdPartyProtection` 去 `region/authz/`）。
 * ⭐ **2026-10-01 刀 4 第 4 件已落**：`protection/ZoneAuthority` **整体消失** ——
 * 裁决面（D/E/F 三个谓词 ＋ 唯一入口 `AreaPermission`）进 {@link com.dddgn.alice.region.authz}、
 * 授权留痕进 `ledger/ModifyAudit`、区内额度进 `region/authz/Quota`。
 * ⇒ 本包**今天不是「`protection/` 的替代品」**，而是它的**新家**，搬家是逐步的。
 *
 *
 * <h2>③ 判据（四问 ⇒ ⛔ 不建四个子包 —— 这是契约，不是偏好）</h2>
 *
 * <p>四问**今天全在同一处**（{@link com.dddgn.alice.region.JobRegionRegistry#declare} 建实例 ·
 * `kind`/`owner` 谁声明 · 覆盖检查 谁能覆盖谁 · {@code release} 何时消失）——
 * 它们是**一个生命周期**，⛔ 不是四种东西。拆成四个子包 = 把一个生命周期劈成四段跨包调用，
 * 而它们**永远一起变**（改一个字段必然动其它三处）⇒ 制造四份耦合、换不到任何独立性（结构提案 `§9.2`）。
 * ⇒ ⭐ **四问写在本文里当契约**，⛔ **不做成目录**。
 *
 *
 * <h2>④ 本包 ⛔ 不做什么（不装什么）</h2>
 *
 * <p>⭐ 「**我改了要不要记 / 要不要还**」是**第三个问题** ⇒ 归 `ledger/`，⛔ **不许塞回本包**
 * （结构提案 `§1` 的三条边界）；「**这一格允不允许动**」是**执行期**问题 ⇒ 归
 * {@link com.dddgn.alice.region.authz}（本包的子包）。
 *
 *
 * <h2>⑦ 沿革 / 未裁（⚠️ 未裁、会挡路的 —— 撞到再问，⛔ 不预先阻塞）</h2>
 *
 * <ul>
 *   <li>`§7` #2：`ReturnPointData`（返程点）落哪 —— `pathing/` 还是 `bot/`；</li>
 *   <li>`§7` #3：`BlockBreakSafety` 的方块语义落 `action/`；</li>
 *   <li>`§9.3` #8：`D-338` 那条链（`SafeReturnTask` ＋ `ReturnPointData`）整体落哪 ·
 *       #9：安全区降为标记位的落法细节；</li>
 *   <li>⭐ `§7` #6：**层序门禁 `region/` 的新方向断言**（`region/` 不许 import `task/`·`job/` 等）
 *       —— 今天**没有门禁盯着本包**（如实登记，台账 `O130`）。</li>
 * </ul>
 *
 * <h2>⑧ 今天的状态（今天有哪几种区域 —— `D-565` 之后的口径）</h2>
 *
 * <table border="1">
 *   <tr><th>区域</th><th>谁声明</th><th>生命周期</th></tr>
 *   <tr><td><b>保护区</b>（玩家认领的区块）</td><td>玩家（`AreaData.claim`）</td>
 *       <td>持久；与任务无关</td></tr>
 *   <tr><td><b>job 区</b>（原「任务区」）</td><td>任务（{@code JobRegionRegistry.declare}）</td>
 *       <td>区块级、**随 `scopeId` 生灭**（终态/被替换/显式打断 ⇒ 权威自动消失）</td></tr>
 *   <tr><td><b>安全区</b></td><td>玩家（`AreaData.declareSafe`）</td>
 *       <td>⭐ `D-565` ②：退化为「**保护区上的一个标记位**」，与保护区**同权限**；
 *       专属语义只剩「**返程首选目的地**」</td></tr>
 *   <tr><td>⛔ <b>归位点</b></td><td>玩家（每 bot 一份）</td>
 *       <td>⛔ **不参与覆盖规则**（`D-565` ③：「玩家自主设定，自己负责」）；
 *       返程时**跳过区域几何**（`task/SafeReturnTask`）</td></tr>
 * </table>
 *
 * <p>⭐ **覆盖规则只剩两条**（`D-565`，⛔ 单向）：`job` 区 → 保护区 ✅ ·
 * `job` 区 → 安全区 ✅ · **任何区域 → `job` 区 ⛔ 拒绝**。
 *
 * <p>⛔ **`working area`（工作区域）不是区域类型**：它是 **`job` 内概念**（方块级扫描面），
 * ⛔ 不与 region 并列（用户 2026-10-01 裁定）。
 *
 *
 */
package com.dddgn.alice.region;
