/**
 * **执行面（`action/`）** —— ⭐「**只装执行件，以及这些执行件的调用器**」（用户 2026-10-01 逐字裁定）。
 *
 * <p>⚠️ 本包的名字**不叫 `act/`** —— 用户 2026-10-01 明示「**顶包名保持 `action/`**」
 * （先例 = `D-455` 逐字「`action/` 微操作」；改名要动 39＋ 个消费者）。
 *
 * <p>@alice-skeleton 骨架（9 份 `package-info.java` 统一节名与次序，2026-10-02 用户裁定「内容结构必须统一」）：① 谁进得来（准入） · ② 这个包是什么 · ③ 判据 · ④ 本包 ⛔ 不做什么 · ⑤ 回收条件 · ⑥ 门禁 · ⑦ 沿革 / 未裁 · ⑧ 今天的状态。⭐ 节号**按角色固定**，所以本文件的号**会跳** —— 那不表示缺内容，只表示本包没有这个角色；⛔ 空节比缺节更坏，所以不许补空标题。
 *
 * <h2>② 这个包是什么（形状：原语住根 · 域执行件住子包）</h2>
 *
 * <pre>
 *   action/                       ← 根 = <b>跨域共享原语</b>（谁都能用，它不认识任何一个域）
 *   ├── BlockInteraction          破坏/放置原语（对齐 Baritone <code>MovementHelper</code>）
 *   ├── BlockBreakSession         破坏会话（工具选择 / 进度 / ABORT / 超时）
 *   ├── MenuSession               容器访问会话
 *   ├── MenuCodes                 菜单码表（<code>MenuSession</code> 的<b>同包</b>码表）
 *   ├── ContainerSemantics        容器语义
 *   └── mining/                   ← <b>域执行件</b>（2026-10-01 刀 1）
 *       ├── MineBlockRunner       挖掘执行器（走到站位 → 放支撑 → 破坏）
 *       └── ChainMining           连锁挖掘模组适配（Ore Excavation，软依赖 + 反射）
 * </pre>
 *
 *
 * <h2>③ 判据（为什么是这几件进来、那几件不进来）</h2>
 *
 * <p>⭐ **判据是"是不是执行件"**（会真的改世界 / 逐 tick 推进一个动作），⛔ **不是**"主题分类"、
 * ⛔ **不是**"有没有自己的专属包"（那只是**结果**）、⛔ **不是**"有没有调用点"。
 * 「**维持单向依赖链**」是这个判据的**机械可检形式**，⛔ 不是理由本身。
 *
 * <p>因此**明确不进本包**的（各自有家，或属待裁）：
 * <ul>
 *   <li>{@code task/mining/MiningBudget} · {@code MiningProfile} —— **值对象 ＋ 分档表 ＋ 模式常量**，
 *       ⛔ 不是执行件（前者还**委托** {@link com.dddgn.alice.action.BlockInteraction#estimateBreakTicks}
 *       算成本，⛔ 无重复实现）；</li>
 *   <li>{@code BlockerClearPlanner} · {@code MiningPlanner} —— 查询型**规划器**；</li>
 *   <li>{@code reach/} · {@code pathing/} · {@code write/} · {@code ledger/} —— 各自有专属域；</li>
 *   <li>{@code compat/ftbteams/*} · {@code compat/ftbchunks/*} —— 只读桥，消费者是 <b>命令面/保护面</b>，
 *       ⛔ 不是执行面（用户 2026-10-01 裁「三桥留 {@code compat/}」）。</li>
 * </ul>
 *
 *
 * <h2>④ 本包 ⛔ 不做什么（本包<b>不</b>负责的事，防误解）</h2>
 *
 * <p>⭐ 用户 2026-10-01 逐字：「{@code act/} **不是**所有执行调用点的唯一入口」——
 * 有专属域的执行件（如 {@code pathing/movement/} 的 {@code *Execution}）**按自己的调用逻辑走**。
 * <p>通用规则（取代早先那条被撤回的"例外界定"）：**可以依赖别的域对外提供的包装口，
 * ⛔ 不许依赖它的内部件** —— 例：{@code MineBlockRunner} 用 {@code pathing/path/PathRetryRunner}
 * 是合法的（那是 {@code pathing/} 对外的包装口，Baritone 的 {@code pathExecutor} 同理）。
 *
 * <p>⚠️ 层链的**完整**声明在 {@code step/package-info.java}（含"按子包声明"这条 2026-10-01 裁定）。
 *
 * <h2>⑥ 门禁（三条 —— `tools/check-layer-direction.py`，2026-10-01 刀 1 同刀落地）</h2>
 *
 * <ol>
 *   <li>⭐ **根 ✗→ 域子包**：{@code action/*.java}（**非递归**）不得引用
 *       {@code com.dddgn.alice.action.<域>.} —— 根是**跨域共享原语**，⛔ 不认识任何一个域执行件；
 *       反过来 **域子包 → 根 是允许的**（执行件当然要用原语）。</li>
 *   <li>⭐ **低层 ✗→ {@code action/<域>/}**：{@code pathing/} · {@code reach/} · {@code write/} ·
 *       {@code log/} · {@code ledger/}（层链里在 {@code action/} **之下**的那些）不得引用域子包。
 *       ⚠️ 今天成立**只是运气** —— 实测生产侧**没有任何一处**从下面调 {@code MineBlockRunner}；
 *       没有门禁，{@code survey/42 §1.2} 那个环（{@code action ↔ pathing}）会**换个名字长回来**。</li>
 *   <li>⭐ **人口下限 / 反空转**：域子包必须真的存在且非空 —— ⛔ 不许靠"把域搬空"让 ①② 空过。</li>
 * </ol>
 *
 *
 */
package com.dddgn.alice.action;
