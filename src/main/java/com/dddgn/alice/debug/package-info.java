/**
 * **调试面**（`D-492` 规矩 R2）：**发行后玩家也能用** ⇒ 它是**产品面**，不是开发期夹具。
 *
 * <p>用户 2026-09-27 原话（本条规矩的由来）：
 * 「**调试工具和测试工具不是一个概念** —— 调试工具在有发行包后，**玩家也可以使用来做调试**；
 * 但测试工具或者说夹具应该**完全只用于开发时的测试**，是用来**验证**，而不是**调整调试**。」
 *
 * <p>三桶（`D-491` 实测：三类东西长期混在 `task/` 一个包里，而"夹具"只有一个文件名正则）：
 * <ul>
 *   <li>{@code task/} = <b>生产</b>；</li>
 *   <li>{@code debug/}（本包）= <b>调试面</b>（产品面）；</li>
 *   <li>{@code fixture/} = <b>开发期测试夹具</b>（只用于验证，可剔除）。</li>
 * </ul>
 *
 * <h2>入桶判据（规矩 R1，机械可算）</h2>
 * <p><b>被玩家入口引用</b> ⇒ 住在<b>本包</b>：{@code item/}（`/give` 可得）或 {@code command/}（`/alice …`）。
 * 实测：87 个 `*CheckTask` 里 **13 个**被玩家入口引用，其中 **11 个同时是电池步** ⇒ 那 11 个**归本包**
 * （玩家能到 = 产品面），并登记"它同时是电池步"。
 *
 * <h2>依赖方向（规矩 R3）</h2>
 * <pre>
 *   debug/（本包） ✗→ fixture/   ← 产品面<b>不得</b>依赖开发期物
 *   fixture/       →  可引用本包  ← 夹具复用调试工具是自然的
 *   task/（生产）  ✗→ 本包        ← 生产不得依赖调试面（`P6/A` 的包级推广）
 * </pre>
 *
 * <h2>⚠️ 它是产品面，所以纪律比夹具更严</h2>
 * <ul>
 *   <li><b>有稳定入口</b>（游戏内物品 / 命令）⇒ 改动要按产品面处理：入口不许无声改名、行为不许无声改变；</li>
 *   <li><b>允许发料</b>（玩家用调试物品时，物品自己给工具 = 明确的测试意图）——
 *       但**生产路径不许发料**（`D-479` 的 `P6/A`；`tools/check-provision-containment.sh` 管这一族）；</li>
 *   <li><b>⚠️ 已知欠账</b>：`build.gradle:95-97` 仍在宣传**已被删除**的 `BotSelftest`（2026-09-13）
 *       —— 本包（调试面）的**入口文档化**本来就欠账，迁移时一并清。</li>
 * </ul>
 *
 * <p><b>当前迁移状态</b>：三桶分离**推后「一口气做」**（`D-492` 规矩 R5）。本文件先**立规矩** ——
 * 从 `D-492` 起，**新写的调试类直接放本包，不再新增到 `task/` 顶层**（规矩 R4）；
 * 现存 13 个 `*DiagnosticTask` / 5 个 `*ProbeTask` / 双向可达的 `*CheckTask` 待迁移时按桶搬入。
 *
 * <p>⚠️ <b>`item/` 与 `command/` 是「注册位置」，搬不动</b> ⇒ 规则是：它们**只做入口**（调用本包），**逻辑住在本包**。
 *
 * <h2>⚠️ 2026-09-30 修订（用户裁「乙」，`D-551`／`D-552`）：`bot/` 也算「注册位置」</h2>
 * <p>「注册位置」= **允许**依赖本包的位置（它们只做注册／派发，逻辑住在本包）。
 * 原表只有 {@code item/} 与 {@code command/}；2026-09-30 用户裁定把 {@code bot/} 加进来 ——
 * 依据（实测）：{@code bot/BotManager} 的 {@code assignXxx} 才是**真正的派发枢纽**（它自己 {@code new} 夹具），
 * 与 {@code item/}／{@code command/} 在"注册"这件事上是**同一角色**；模组入口（根包 {@code AliceMod}，
 * 做 {@code EVENT_BUS.register(X.class)}）同理。
 * <p>⚠️ 这条修订把「{@code bot/} 引用本包」从**违规**变成**已登记**，
 * 但⛔ <b>没有</b>放宽 `debug/` ✗→ `fixture/`，也⛔ 没有放宽 `task/` 之外其它生产包
 * —— 判据已可执行：{@code tools/check-layer-direction.py} 的 {@code REGISTRATION_POSITIONS}（4 条合成臂盯着）。
 *
 * <h2>⚠️ 2026-09-30 修订（用户裁「甲」）：本包的判据 = <b>命令可达</b>，⛔ 不再是「`/give` 物品可达」</h2>
 * <p>用户逐字：「<b>真正在发行包会用到的一般都是调试指令</b>，或者我们在 GUI 加调用调试指令的按钮」
 * ＋ 对那 36 个 `/give` 调试物品的裁定：「<b>留，但只当开发期入口</b>」。
 * <p>⇒ <b>发行包的「玩家调试面」载体 = 命令（＋ GUI 按钮，而按钮调的就是命令）</b>；
 * {@code item/}（{@code /give} 可得）退为<b>开发期入口</b> ⇒ ⛔ <b>不再</b>把引用者提升到本包。
 * <ul>
 *   <li><b>入桶判据（新）</b>：带 {@code R2} 枚举标记 且 <b>命令可达</b>
 *       （{@code command/} 直接引用，或该类的 {@code BotManager} 派发方法<b>被 {@code command/} 调用</b>
 *       ⇒ 派发表 {@code docs/TASK_DISPATCH_TABLE.csv} 的 <b>{@code cmd_reachable=yes}</b>）⇒ 住<b>本包</b>；</li>
 *   <li>带标记但<b>只被 {@code item/}／`bot/` 派发／验证侧</b>够到 ⇒ 那是开发期物 ⇒ 住 {@code fixture/}（<b>可剔除</b>）。</li>
 * </ul>
 * <p>⭐ <b>落地的读数（这批裁定的直接后果）</b>：原本判入本包的 <b>40</b> 个类里，
 * <b>命令可达只有 6 个</b>（{@code Ascend} / {@code Chain} / {@code Descend} / {@code Diagonal} /
 * {@code PathSession} / {@code Traverse} {@code DiagnosticTask} —— 全是<b>寻路诊断</b>，
 * 挂在 {@code /alice pathing {…}} 下）；其余 <b>33</b> 个只有 {@code /give` 物品一条路
 * ⇒ 已改判 {@code fixture/}（1 个 {@code TransferCheckTask} 留在 {@code task/} 待夹具波）。
 * <p>⚠️ <b>副产品</b>：{@code debug/ ✗→ fixture/}（{@code R3}）的冲突因此<b>大幅自消</b> ——
 * 搬走的那些不再需要夹具支撑；<b>仍残留 1 条</b>：{@code PathSessionDiagnosticTask} 引用
 * {@code task.FixtureScript}（夹具波搬它时会红，⛔ 已登记、未裁）。
 * <p>⚠️ {@code R2} 表里那句「{@code *CheckTask}／{@code *ProbeTask}／{@code *DiagnosticTask} 三类且玩家可达」
 * <b>仍然成立</b>，改的只是「玩家可达」的<b>判据</b>（物品 ⇒ 命令）。
 */
package com.dddgn.alice.debug;
