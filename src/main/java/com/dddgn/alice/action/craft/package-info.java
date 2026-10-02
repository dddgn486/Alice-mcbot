/**
 * **合成域执行件（`action/craft/`）** —— 「动作 / 动作逻辑」里**合成**那一个域的执行件。
 *
 * <p>层位与准入：见 {@code com.dddgn.alice.action}（`action/` 根 = **跨域共享原语**）与
 * <b>{@code D-569}</b> §一 <b>R1</b>（用户 2026-10-01 逐字：「`action` 理论上**只存放动作、动作逻辑**，
 * 它天生就是要被 `step` 以及其他包调用，**不应该单独立包且本身可被单向依赖调用**的行为操作都应该在这个包」）。
 *
 * <h2>一 · 准入（`D-569` R1 ＋ 2026-10-02 追加裁定）</h2>
 * <ol>
 *   <li>它<b>是"动作 / 动作逻辑"</b>；</li>
 *   <li>它<b>不该单独立包</b>（人太少 / 职责不成一层）；</li>
 *   <li>它<b>可被单向依赖调用</b>（出边不许到<b>上层</b>包）。</li>
 * </ol>
 * <p>⭐ <b>追加裁定（2026-10-02，用户逐字）：「允许 `action` 包里的动作自足，放自己的依赖部件」</b>
 * —— 即：域执行件**所依赖的只读部件／数据表**（查询器 · 事实读取器 · 网格发现器）
 * <b>随域入本包</b>，⛔ 不为了"它们不是动作"而把它们分出去。理由：分出去会让本包**不自足**
 * ⇒ 要么违 {@code staging/} 的「新代码不许 import `staging/`」禁令，要么退回"每个动作类自己重写一遍"
 * （`D-290`「声明了没人用 ⇒ 删」的反面：**同一份事实被抄两遍**）。
 * ⚠️ 本条<b>只放宽"是不是动作"这一条</b>，⛔ 不放宽第 3 条（层方向仍是硬约束）。
 *
 * <h2>二 · 入住名单 = `task/craft/` 整簇 10 类（2026-10-02 落地）</h2>
 *
 * <p><b>动作件 7</b>：{@code TableCraft} · {@code InventoryCraft} · {@code FurnaceStation} ·
 * {@code StationPlacement} · {@code StationProvision} · {@code MachineCycle} · {@code CraftStation}。
 * <p><b>随域自足的只读部件 3</b>：{@code GridDiscovery}（合成网格发现器，**红线载体**：
 * 「未知模组默认只读、不猜槽位」）· {@code RecipeQuery}（只读配方查询原语，`D-185`）·
 * {@code MachineRecipeFacts}（上游自述的机器输入/输出事实读取器，`D-204`）。
 *
 * <p>⚠️ <b>为什么整簇搬</b>（逐条实测，⛔ 不是"图省事"）：这一簇的依赖边<b>闭合</b>且**跨过了原分类线** ——
 * {@code TableCraft}·{@code InventoryCraft}·{@code FurnaceStation}·{@code StationProvision} 用
 * {@code GridDiscovery}（**4 处**）· {@code InventoryCraft}·{@code StationProvision}·{@code MachineCycle} 用
 * {@code RecipeQuery.countInInventory}（**4 处**）· {@code RecipeQuery} 用 {@code MachineRecipeFacts}。
 * ⇒ 只搬"动作"那一半，会立刻造出 {@code action/craft/ → task/craft/} 的**向上边**。
 * ⚠️ 且**门禁当时抓不到它**：{@code check-layer-direction} 的域规则断的是「<b>低层</b> ✗→ `action/<域>`」，
 * 而 `task/` **不在低层表里**（`O90` §⑤ 逐字记过同一缺口）。
 *
 * <h2>三 · 本刀同刀改的门禁（`R4`「门禁与迁移同刀」）</h2>
 * <ol>
 *   <li>{@code tools/check-layer-direction.py}：{@code ACTION_DOMAINS} 加 {@code "craft"}
 *       ＋ {@code MIN_ACTION_DOMAIN_FILES} 按实际人口设下限（反空转）。</li>
 *   <li>{@code action/} <b>根</b>人口下限**不变**（根仍是 5 个跨域共享原语）。</li>
 *   <li>两条既有断言对新子包**自动生效**：① {@code action/} <b>根 ✗→ 域子包</b>
 *       ② 低层（{@code pathing}·{@code reach}·{@code write}·{@code log}·{@code ledger}）**✗→ {@code action/<域>}**
 *       —— ⭐ <b>本刀收工时复算</b>：这 5 个包对本包的引用<b>各 0 个文件</b>。</li>
 * </ol>
 *
 * @see com.dddgn.alice.action action/ 根 = 跨域共享原语
 * @see com.dddgn.alice.action.mining 同族先例（2026-10-01 刀 1 立的第一个域子包）
 */
package com.dddgn.alice.action.craft;
