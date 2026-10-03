/**
 * **原语层（`step/`）** —— 「逐 tick 推进到**单格结论**」的最小执行单元。
 *
 * <p>⚠️⚠️ <b>当前状态：只立家，零入住。</b> 本包今天<b>只有这一个文件</b> —— 三个目标类
 * （{@code task/Step.java} 注册口 · {@code task/mining/MineStep.java} · {@code task/collecting/CollectStep.java}）
 * ⛔ <b>仍住在 {@code task/} 下</b>，搬家被 <b>`O65` §② 的三条触发条件</b>挡着（<b>今天一条都不成立</b>）。
 * ⇒ 本文件是<b>结构声明 ＋ 搬家时的同刀清单</b>，⛔ 不是"这层已经开始用了"。
 *
 * <p>@alice-skeleton 骨架（9 份 `package-info.java` 统一节名与次序，2026-10-02 用户裁定「内容结构必须统一」）：① 谁进得来（准入） · ② 这个包是什么 · ③ 判据 · ④ 本包 ⛔ 不做什么 · ⑤ 回收条件 · ⑥ 门禁 · ⑦ 沿革 / 未裁 · ⑧ 今天的状态。⭐ 节号**按角色固定**，所以本文件的号**会跳** —— 那不表示缺内容，只表示本包没有这个角色；⛔ 空节比缺节更坏，所以不许补空标题。
 *
 * <h2>③ 判据（搬家时<b>必须同刀</b>改的四件事 —— 先量好，⛔ 别到时现找）</h2>
 *
 * <p>本项目纪律「<b>门禁与迁移必须同刀</b>」（`D-492` `R4`），而 step 这一刀的门禁面<b>已经量清</b>
 * （`O65` §③ ＋ 本文件写作时的复核）：
 * <ol>
 *   <li>{@code tools/check-task-orchestration-split.py} —— ⚠️⚠️ <b>最硬的一条</b>：
 *       它现在逐条断言「实现了 {@code Step} 的类<b>必须住在 {@code task/} 下</b>」
 *       （`task/` 前缀判据，⛔ 不是"住哪个包都行"）＋ 两个写死的路径常量 {@code STEP}／{@code STEP_5B}
 *       ＋ 反向检查 {@code TASK_DIR.rglob("*Step.java")} ＋ 人口下限 {@code STEP_IMPL_MIN = 2}。
 *       ⇒ <b>不改它，搬家当场红</b>。</li>
 *   <li>{@code tools/check-layer-direction.py} —— 加本包的<b>反向规则</b>（{@code step/} ✗→ {@code task/}／{@code job/}，
 *       理由 = `reach/` 那次实测的教训：「只搬一个会造出新循环」）＋ 人口下限。</li>
 *   <li>{@code docs/TASK_TOP_LEVEL_FREEZE.txt}（`P2` 单向阀名单）—— {@code task/Step.java} 在名单里
 *       ⇒ 搬走时<b>同一提交内删行</b>（⛔ `check-task-top-freeze.py` 刻意<b>不提供</b> {@code --write}）。</li>
 *   <li>本包 ＋ 各调用点的 {@code package} 行与 {@code import} 改写（先例 = `reach/` `D-460` ·
 *       `pathing/` `D-461` · `write/` `D-462`，都是"<b>只改 package 行 ＋ import</b>"）。</li>
 * </ol>
 * <p>并按 {@code P3} 的<b>三条件同时成立</b>收尾：① {@code task/} 旧文件已不存在 ② 本包文件存在且
 * {@code package} 行已改 ③ 全仓<b>零</b> {@code com.dddgn.alice.task.<该类>} 残留引用
 * （⛔ 半搬是最贵的形态）。
 *
 *
 * <h2>⑥ 门禁（与"同名门禁"的关系 —— ⚠️ 一条已登记、但今天<b>不该</b>顺手改的债）</h2>
 *
 * <p>{@code tools/check-duplicate-class-names.py} 的 {@code DUP_EXEMPT} 里 {@code Step} 这一组有 <b>3</b> 个同名：
 * 本波的主角 {@code task/Step.java}（注册口）· {@code compat/ftbteams/FtbPartyBinder} 里的
 * {@code record Step(String what, boolean ok, String detail)} · {@code task/RegressionBatteryTask} 里的
 * {@code private record Step(String name, …)}。三处语义毫不相干。
 * <p>⚠️ 那条豁免的「归属」写的是 <b>本刀</b>，但它的<b>复核触发</b>写的是
 * 「{@code Step} 接口搬到 {@code step/} 包时（届时这条必须同刀处理）」—— ⭐ <b>以后者为准</b>：
 * 今天搬不了 step，就不该单独改名（那正是 `R5` 要防的"零散搬"）。
 *
 * @see com.dddgn.alice.task.Step step 原语注册口（今天仍在 {@code task/}）
 *
 * <h2>⑦ 沿革 / 未裁</h2>
 * <p><b>（a）这一层是哪来的（`D-455` 的一格收窄）</b>
 *
 * <p>{@code D-455} 定的是三层，其中第二格的<b>名字</b>就是「动作原语」：
 * <pre>
 *   action/（微操作）  &lt;  task/（<b>动作原语</b>）  &lt;  job/（高级任务 = 原语序列 ＋ 机制）
 * </pre>
 * <p>后来 {@code D-466}／{@code D-469} 把 {@code task/MineTask} 里"<b>跑一格</b>"的那一段切出来，
 * 放进新原语 {@code task/mining/MineStep}（先例 = `step 5a`／`D-493` 的收集侧第二刀），
 * {@code D-539} 又给"哪些类是原语"补上了<b>注册口</b>（{@code task/Step} 标记接口）。
 * ⇒ <b>`task/` 那一格因此一分为二</b>：<b>原语</b>（逐 tick · 单格结论）与<b>编排</b>（序列 · 相位 · 额度 · 重试）。
 *
 * <p>⭐ 本包就是那<b>前半格</b>的家。它要表达的层链（下 → 上）：
 * <pre>
 *   pathing/ · reach/   &lt;  write/   &lt;  action/   &lt;  <b>step/（本包）</b>  &lt;  task/   &lt;  job/
 *     内核几何 / 触及      写入治理     微操作         <b>原语</b>           编排       高级任务
 * </pre>
 * <p>⚠️ <b>诚实边界</b>（`O65` §④ 逐字的口径）：这条链今天是<b>意图</b>，⛔ <b>包结构上还没有表达出来</b> ——
 * 三个 step 类散在三处、和 <b>142 个 {@code task/} 顶层类</b>挤在一起。这与 `MiningPlan` 那次不同：
 * <b>不是"名字说谎"，是"层还没分出来"</b>。
 *
 *  * <p><b>（b）为什么今天⛔ 不搬（′三条触发条件，`O65` §②；用户 2026-09-30 明示"不动 `step/` 搬家"）</b>
 *
 * <p><b>先给结论：没有技术拦路</b>（`O65` §① 已逐条核实，剥注释后）——
 * {@code MineStep}（265 行）只<b>向下</b>依赖 {@code action/} · {@code reach/} · {@code write/} · {@code bot/} ·
 * {@code compat/} · {@code log/}；{@code CollectStep}（1038 行）多两处 {@code task/mining/} 的依赖
 * （{@code GainStepRunner} · {@code MiningProfile}），而<b>这两个自己零 {@code task/} 依赖</b>
 * ⇒ <b>跟着一起搬就没有反向依赖</b>。所以问题是<b>收益触发</b>，不是"能不能"。
 *
 * <p><b>触发条件（任一成立就搬）</b>：
 * <ol>
 *   <li><b>(a)</b> ⭐ 最自然 = <b>横切闸门④ 第二半那项检查开工时</b> —— 它要判「新功能落在<b>内核路径之外</b>
 *       ＝ 新 step ／ 新执行器」，而<b>路径前缀是它唯一可判的形状</b>；今天 step 没有路径前缀
 *       ⇒ 不搬，那项检查只能<b>逐类写死</b>（＝回到 `O59` §3 那个病）。</li>
 *   <li><b>(b)</b> <b>原语人口 ≥ 3 个独立 step</b>（今天 <b>2</b> 个）—— 第 3 个出现时性价比最好
 *       （对照本项目其它层的人工下限：{@code reach/} ≥ 4 · {@code action/} ≥ 6 · {@code write/} ≥ 6）。</li>
 *   <li><b>(c)</b> <b>{@code task/collecting/} 的"为 1 个文件建的包"现状被打破时</b>（要么长大、要么并走）。</li>
 * </ol>
 *
 *
 */
package com.dddgn.alice.step;
