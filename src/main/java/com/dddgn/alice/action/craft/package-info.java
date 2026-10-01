/**
 * **合成域执行件（`action/craft/`）** —— 「动作 / 动作逻辑」里**合成**那一个域的执行件。
 *
 * <p>层位与准入：见 {@code com.dddgn.alice.action}（`action/` 根 = **跨域共享原语**）与
 * <b>{@code D-569}</b> §一 <b>R1</b>（用户 2026-10-01 逐字：「`action` 理论上**只存放动作、动作逻辑**，
 * 它天生就是要被 `step` 以及其他包调用，**不应该单独立包且本身可被单向依赖调用**的行为操作都应该在这个包」）。
 *
 * <h2>一 · 三条准入（`D-569` R1；⛔ 缺一条就不该进本包）</h2>
 * <ol>
 *   <li>它<b>是"动作 / 动作逻辑"</b> —— ⛔ 只读查询（{@code RecipeQuery}）· 纯数据表（{@code MachineRecipeFacts}）
 *       · 只读扫描（{@code GridDiscovery}）都<b>不算</b> ⇒ 那三个已裁进 {@code staging/} 等拆解。</li>
 *   <li>它<b>不该单独立包</b>（人太少 / 职责不成一层）。</li>
 *   <li>它<b>可被单向依赖调用</b>（出边不许到<b>上层</b>包）。⚠️ 实测：本域的类今天只 import
 *       {@code bot/BotPlayer}（实体句柄**叶子**）· {@code write/} · {@code action/} 根 · {@code pathing/} ·
 *       {@code region/} · {@code ledger/} · {@code log/} —— ⛔ 无<b>真</b>上层依赖。</li>
 * </ol>
 *
 * <h2>二 · 本域的入住名单（`D-569` / `O138`，用户 2026-10-01 裁）</h2>
 *
 * <p>{@code task/craft/} 的 10 个类里，<b>6</b> 个判进本包：
 * {@code TableCraft} · {@code InventoryCraft} · {@code FurnaceStation} · {@code StationPlacement} ·
 * {@code StationProvision} · {@code MachineCycle}。
 * <p>⚠️ <b>没进来的 4 个</b>（⛔ 别当成漏了）：{@code CraftStation} ⇒ 走 {@code D-568} #5 的
 * {@code compat/<mod>/}（sophisticatedstorage，⚠️ **切分与否仍待裁**）· {@code RecipeQuery} ·
 * {@code MachineRecipeFacts} · {@code GridDiscovery} ⇒ {@code staging/}（它们**不是动作**）。
 *
 * <h2>⚠️ 三 · 今天只立家、零入住（`P1` 立家先于搬迁）</h2>
 *
 * <p>本波（`D-569` R3「先搬优先」）搬的是**已有家**的那 7 个（`survival/` 4 · `road/` 1 · `transfer/` 2）。
 * 本包<b>同刀要改的门禁</b>（⛔ 缺一处当场红，`R4`「门禁与迁移同刀」）：
 * <ol>
 *   <li>{@code tools/check-layer-direction.py} 的 {@code ACTION_DOMAINS} 加 {@code "craft"}
 *       ＋ 按实际人口给 {@code MIN_ACTION_DOMAIN_FILES} 设下限（反空转，`D-569` 同族）。</li>
 *   <li>{@code tools/check-layer-direction.py} 的 {@code ACTION_ROOT_FILES} 人口下限**不变**（根还是 5 个原语）。</li>
 *   <li>两条既有断言对新子包**自动生效**：① {@code action/} **根 ✗→ 域子包** ② 低层
 *       （{@code pathing}·{@code reach}·{@code write}·{@code log}·{@code ledger}）**✗→ {@code action/<域>}**
 *       —— ⭐ 后者已**实测**：这 6 个类今天**零**低层引用（那次唯一的命中是
 *       {@code write/WritePolicyMatrix} 自己的嵌套 {@code enum Task}，同名遮蔽 ⇒ **假阳性**）。</li>
 * </ol>
 *
 * @see com.dddgn.alice.action action/ 根 = 跨域共享原语
 * @see com.dddgn.alice.action.mining 同族先例（2026-10-01 刀 1 立的第一个域子包）
 */
package com.dddgn.alice.action.craft;
