/**
 * **模组功能适配层（`compat/`）** —— 一个**具体模组**的一条**具体能力**，以**软依赖 ＋ 反射**实现。
 *
 * <p>⚠️ 2026-10-01（刀 1）后本包**顶层不再有类**：原 {@code compat/ChainMining} 是**执行件**
 * （它调 {@code MiningScheduler.INSTANCE.startMining(...)} 让模组**真的去挖**）⇒ 按用户
 * 2026-10-01 的裁定「{@code act/} 只装执行件，以及这些执行件的调用器」搬进
 * {@code com.dddgn.alice.action.mining}。本包现在只剩两个子包。
 *
 * <p>@alice-skeleton 骨架（9 份 `package-info.java` 统一节名与次序，2026-10-02 用户裁定「内容结构必须统一」）：① 谁进得来（准入） · ② 这个包是什么 · ③ 判据 · ④ 本包 ⛔ 不做什么 · ⑤ 回收条件 · ⑥ 门禁 · ⑦ 沿革 / 未裁 · ⑧ 今天的状态。⭐ 节号**按角色固定**，所以本文件的号**会跳** —— 那不表示缺内容，只表示本包没有这个角色；⛔ 空节比缺节更坏，所以不许补空标题。
 *
 * <h2>② 这个包是什么（本包的定义 —— 刀 1 起写死在这里）</h2>
 *
 * <p>⭐ **{@code compat/} = "具体模组的功能适配层"** —— 每一件都满足：
 * <ol>
 *   <li>绑定**某一个**模组（FTB Chunks / FTB Teams / …），⛔ 不是通用扫描器；</li>
 *   <li>该模组**不是**编译期依赖（玩家可以不带它玩）⇒ 反射 ＋ 缺模组即降级；</li>
 *   <li>签名**逐条对装好的 jar 核过**（⛔ 不许猜 —— `D-318` 的教训）。</li>
 * </ol>
 *
 * <h2>④ 本包 ⛔ 不做什么（什么<b>不</b>属于这里 —— 2026-10-01 的两条裁定）</h2>
 *
 * <ul>
 *   <li>⛔ **执行件**：会真的改世界的适配器（如 {@code ChainMining}）属
 *       {@code action/} 的**域子包**，不属本包 —— 这样「Alice 能让世界变的**全部入口**」
 *       第一次成为一个**可以一眼看全的包**。</li>
 *   <li>⛔ **{@code capability/} 不通婚**：用户 2026-10-01 裁「三桥**留 {@code compat/}**」——
 *       {@code capability/} 是「服务端 C1 **只读接口扫描器**」（扫 Forge Capability：
 *       {@code IItemHandler} / {@code IEnergyStorage} / {@code IFluidHandler}，输出
 *       **unsided raw readonly facts**、**不猜槽位角色**），它是**通用**的；
 *       本包是**具体模组**的。合并会让「未知模组能力默认只读」那条红线**失去可执行边界**。</li>
 * </ul>
 *
 * <h2>⑧ 今天的状态（今天的成员）</h2>
 *
 * <ul>
 *   <li>{@code ftbchunks/FtbChunksBridge} —— 只回答"这一格 FTB 认领允不允许这只 bot 编辑方块"，
 *       ⛔ 写路径一个字都不碰；消费者 = {@code protection/ThirdPartyProtection}。</li>
 *   <li>{@code ftbteams/FtbTeamsBridge} —— FTB Teams 的**只读**桥；消费者 = {@code command/BotCommand}。</li>
 *   <li>{@code ftbteams/FtbPartyBinder} —— 让假人继承创建者的 FTB 身份，**全部通过 FTB 自己的命令**完成。</li>
 *   <li>{@code ftbteams/FtbCommandRunner} —— 以某个玩家身份执行一条命令；⚠️ **生产消费者 0**
 *       （只有本包自己 ＋ 1 个夹具）⇒ 按"**域内私有**"留在这里，⛔ 不搬进 {@code action/}。</li>
 * </ul>
 *
 * <p>⚠️ 模组适配是**需求驱动**、⛔ 不是覆盖率驱动（`D-219`）：只为**当前存档真正用到**的东西适配。
 */
package com.dddgn.alice.compat;
