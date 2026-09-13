# 模组兼容范例 · 第一步：**可切换的合成工作站**（设计，2026-09-13）

> 用户裁定（原话）："现在只是做**兼容范例测试**，先实现**工作站的可切换**，不急着完全适配其他模组。"
> 本文 = 模组调查（事实 + 证据）+ 第一步设计。**尚未写代码**。

## 0. 先回答那个问题：是不是不该硬编码合成方式

**是，不该。** 而且好消息是骨架已经对了一半 —— 真正的硬编码只有 **4 处**，其余已经是数据/协议：

| 层 | 现状 | 模组会不会撞坏 |
|---|---|---|
| `InventoryCraft.GridSpec(gridSlots,width,height,resultSlot,invFirst,invLast)` | **数据**；`craft(menu, recipe, count, spec)` 对任意 `AbstractContainerMenu` 生效 | 不用改 |
| `MenuSession` | **通用菜单协议**（点击/关闭/生命周期） | 不用改 |
| `RecipeQuery` 只读查询 | 按**配方类型 id** 路由（不是序列化器 id） | 不用改 |
| ① 槽位下标常量（`inventorySpec()` / `tableSpec()`） | 硬编码"2×2 随身 / 3×3 工作台"的已知布局 | ❌ 模组菜单下标完全不同 |
| ② 结果槽协议假设 = `QUICK_MOVE`（原版 `ResultSlot` 语义） | 硬编码 | ❌ RS 的 `QUICK_MOVE` 是**完全另一套**（见 §1.3） |
| ③ 站点获取 = `findTable` + `standPointNear` + `openTable`（世界方块） | 硬编码"站点是世界里的一块方块" | ⚠️ RS 恰好**也是方块**（运气好）；"身上 3×3"那类不是 |
| ④ `RecipeQuery.inventoryGrid` 是 **boolean**，`verdict = CRAFTABLE : NEEDS_TABLE`（`RecipeQuery.java:176`） | 硬编码"**有没有 3×3**"这个二分 | ❌ 身上 3×3 ⇒ 假阴性；模组网格 ⇒ 无路线 |
| ⑤ 没有熔炉形状 | A4 未做 | ❌ 熔炉类站点（含背包熔炉升级）是另一种执行形状 |

## 1. 模组调查（事实，全部可追溯）

### 1.1 装的是哪个

| 项 | 值 |
|---|---|
| 文件 | `refinedstorage-1.12.4.jar`（3 300 191 B） |
| 来源 | Modrinth CDN `https://cdn.modrinth.com/data/KDvYkUg3/versions/ZITLFjjf/refinedstorage-1.12.4.jar` |
| sha1 | `014bf4de4975f6310d22a6ed06c6a7e1e0805641`（与 Modrinth 元数据一致） |
| 元数据 | `mods.toml`: `modId=refinedstorage` `version=1.12.4` `loaderVersion="[47,)"`、**无 `[[dependencies]]`** ⇒ 无需附加依赖；与 Forge 47.4.10 兼容 |
| 1.20.1 现实 | Modrinth 上 1.20.1 只有 **RS1 1.12.x**（`1.12.4` 是 forge+neoforge 双载荷）。RS2 的"网格升级页签"形态在 1.20.1 上不存在 |
| 已安装 | 已放入固定客户端 `mods/`（sha1 已核对）。**未入库**（仓库不跟踪 mod jar） |

调查手段：读 jar 内 `META-INF/mods.toml` + `javap -p` 看类/方法签名 + 从 MIT 许可的上游仓库 `refinedmods/refinedstorage` tag `v1.12.4` 取源文件（`GridContainerMenu` / `ResultCraftingGridSlot` / `CraftingGridSlot` / `CraftingGridBehavior` / `GridNetworkNode` / `BaseContainerMenu` / `TransferManager` / `ServerConfig`）。

### 1.2 关键事实（RS1 的合成网格长什么样）

1. **网格与结果容器属于"网格对象"（方块实体），不属于菜单**：
   `GridNetworkNode.java:114` `private final CraftingContainer matrix = new TransientCraftingContainer(craftingContainer, 3, 3);`
   `GridNetworkNode.java:79` `private final ResultContainer result = new ResultContainer();`
   ⇒ 矩阵是**原版 `TransientCraftingContainer` 3×3**、结果是**原版 `ResultContainer`**。
2. **槽位类是纯原版槽**：`CraftingGridSlot extends net.minecraft.world.inventory.Slot`（容器=矩阵）；
   `ResultCraftingGridSlot extends net.minecraft.world.inventory.ResultSlot`（容器=结果）。
3. **菜单槽位下标是"动态拼出来的"，不是固定布局**：`GridContainerMenu.initSlots()` 先 `slots.clear()`，
   然后 `addFilterSlots()` →（便携网格另加）→ `addCraftingSlots()`（**9 个矩阵槽 + 1 个结果槽**）→ 玩家背包。
   ⇒ 合成槽在哪个下标取决于过滤器槽数量/是否便携/网格类型（CRAFTING/PATTERN/FLUID）。**硬编码下标必错。**
4. **`QUICK_MOVE`（shift-click 结果槽）走的是 RS 自己的覆盖实现**：
   `BaseContainerMenu.quickMoveStack(player, slotIndex)` → `transferManager.transfer(slotIndex)`；
   `GridContainerMenu` 的 `notFoundHandler`：`if (slot == craftingResultSlot) { grid.onCraftedShift(getPlayer()); }`
   `CraftingGridBehavior.onCraftedShift`：**do/while 一次合到满堆**，产物 `ItemHandlerHelper.insertItem(new PlayerMainInvWrapper(player.getInventory()), …)`
   ⇒ **玩家背包优先，溢出进网络，再余量丢地上**；材料从记录下来的 `usedItems` 统一从网络扣除。
5. **单次取（普通点击）会从网络"补满"矩阵**：
   `ResultCraftingGridSlot.onTake` 覆盖了原版（源码注释原文：*"Overriding logic from the super onTake method for Grid behaviors like refilling stacks from the network"*）
   → `grid.onCrafted(player, null, null)` → `CraftingGridBehavior.onCrafted`：某格只剩 1 个且 `network != null && grid.isGridActive()`
   ⇒ `network.extractItem(slot, 1, PERFORM)` **把这一格补回 1 个**；余数（remainder）去向 = 玩家背包 → 网络 → 掉地上。
6. **网格类型是方块属性，不是升级给的**：`GridNetworkNode` 的 `type` 在构造时传入（`GridType.CRAFTING`）；
   方块有独立的 `refinedstorage:crafting_grid`。`Crafting Upgrade` 是给 **Crafter 等机器**插的**能耗升级**
   （`ServerConfig.Upgrades.craftingUpgradeUsage = 5`），**不给网格加"合成页签"**。

### 1.3 与你描述的三条对照

| 你的描述 | 调查结论 |
|---|---|
| "九个格子实际上能**存储东西**，关闭背包不会自动放回" | ✅ **成立**。矩阵属于网格对象（事实 1/2），关 GUI 不归还；摆进去的东西**留在方块里** |
| "合成产物的 QuickMove **可以切换目标**（背包 / 玩家背包）" | ⚠️ **部分成立，但不是开关**：shift-click 固定是"**玩家背包优先 → 溢出进网络**"（事实 4）；而**普通取**产物落在**光标**上，之后放哪里由取的人决定 —— 也就是说"目标"是由**用哪种点击协议**决定的，不是某个设置项。这条对我们正好：协议可以写进工作站描述符 |
| "**合成升级**的页签" | ❌ **RS1 里不存在这条链路**：网格类型是方块属性（事实 6），合成升级装在机器上。"升级 → 合成页签"是 RS2 的形态，而 1.20.1 只有 RS1。**待你确认你指的是哪个 GUI**（下一步的只读探针会把它照出来） |

### 1.4 顺带确认的原版事实（发现器可行性）

`javap` 查 Forge 47.4.10 映射 jar：`net.minecraft.world.inventory.CraftingContainer` **有** `getWidth()` / `getHeight()`；
`TransientCraftingContainer` 实现之；`ResultContainer` 是普通 `Container`。
⇒ **只用原版 API 就能"发现"网格**：`slot.container instanceof CraftingContainer` ⇒ 网格槽；
`slot.container instanceof ResultContainer` ⇒ 结果槽；尺寸取 `getWidth()/getHeight()`。
三种场景各只有**一个** `CraftingContainer`（随身 2×2 / 工作台 3×3 / RS 3×3），**不会歧义**。

## 2. 对 Alice 的影响（现在哪里会坏，以及为什么）

| # | 影响 | 说明 |
|---|---|---|
| 2.1 | **槽位必须"发现"** | 见 §1.2 事实 3。`GridSpec` 保留，但**由发现器产出**，不再用常量 |
| 2.2 | **结果槽协议是第二维** | 同一个 `ClickType.QUICK_MOVE` 在 RS 上 = "合到满堆 + 背包优先"，在原版 = "合 1 次 + 进背包"。⇒ 描述符必须带 `TakeProtocol`，且**实测选定** |
| 2.3 | **材料来源是第三维** | RS 从**网络**补料；`RecipeQuery.countInInventory` 的"缺料"与"材料 −M / 产物 +1"守恒断言**口径全错**。⇒ 描述符带 `MaterialSource`（`INVENTORY` / `UNKNOWN`→只读），断言按来源分口径 |
| 2.4 | **"零世界写入"不再成立** | A3 的 `no_world_write` 成立只是因为原版网格**随开随灭**。RS 矩阵**持久在方块实体里** ⇒ 摆料/清理 = **对世界状态的写入**。现有 `consumeContainerWrite` 只被 `TransferTask` 用（`A11`），合成摆料没有登记 ⇒ 需要新 `WriteReason` + A 表条目 |
| 2.5 | **站点获取恰好同形** | RS 合成网格也是"方块 + 右键" ⇒ 现有 `findTable → 走近 → 右键 → 开菜单` 的形状可复用，只需把"找哪块方块"参数化（按**方块 id** 查，不引编译期依赖） |

## 3. 第一步范围（可切换的工作站，不碰"完整适配"）

**目标**：让"合成工作站"成为**一等、可选、可切换**的对象，并把 RS 作为**第二个范例**验证这条缝；本步**只读优先**，执行接入留给第二步。

### S1-1 发现器（只读，通用，零模组知识）
`task/craft/GridDiscovery`：
- 输入 `AbstractContainerMenu`，输出 `Discovered{gridSlots[], width, height, resultSlot, inventoryFirst/Lost, matrixClass, resultSlotClass}`；
- 拒绝码（如实、不猜）：`no_grid` / `no_result_slot` / `ambiguous_grid` / `grid_shape_mismatch`（`width*height != gridSlots.length`）；
- **回归证明**：在随身 2×2 / 工作台 3×3 上必须复现 D-163/D-165 记录的布局（这是"发现器没写错"的硬证据）。

### S1-2 工作站描述符 + 玩家可切换（确定性事实 + 人工选择）
`task/craft/CraftStation`：
```
{ kind: INVENTORY | BLOCK,  站点名, 打开方式, 网格能力(width×height),
  TakeProtocol: SINGLE_TAKE | QUICK_MOVE,  MaterialSource: INVENTORY | UNKNOWN }
```
- 候选 **由代码产出**（每个候选：能不能用 + 为什么不能用），**不做自动选优**；
- 切换入口（玩家）：`/alice craft station <auto|inventory|table|block>`（或一个物品），默认 `auto` = 今天的顺序（随身 → 工作台）；
- `bot_report` 多一行：`合成工作站：auto（候选：随身 2×2 ✓ / 工作台（无）/ 方块网格（无））`；
- **LlmClient/决策层不动**（A5 才谈）；本步的"切换"是**玩家/命令**层的选择。

### S1-3 只读探针（零参数，先把 RS 现场事实照出来）
复用/扩展既有 `alice:menu_probe`（D-163）或新增 `alice:craft_grid_probe`：
打开最近的可合成菜单 → 打印 `menu 类 / 槽数 / 矩阵类与 3×3 / grid 下标 / 结果槽下标与类 / 玩家背包区间 / 拒绝码`，
并**只读**报告"矩阵里现在有没有东西"（用来**零写入**地证明"关掉不归还"：你手动放进去的东西，重开还在）。
**判据**：随身/工作台两行与 §1.4 记录一致；RS 行 = 真实布局（下一步据此定协议）。

### S1-4 RS 范例场景（数据包函数，零参数）
`alice_test:rs_craft_course`：相邻放 `refinedstorage:creative_controller`（供电）+ `refinedstorage:creative_storage_block`（无限库存）
+ `refinedstorage:crafting_grid`（站点），把玩家放到旁边。
**待实测**：RS1 的三块相邻是否就成网（这是"场景能不能跑"的前提，代码不猜）。

### S1-5 执行层接入（本步只做"已验证协议"的那一半）
- A2/A3 改为经 `CraftStation` 取 spec（**对随身/工作台保持行为等价**，用电池回归证明）；
- **RS 站点本步不接执行**，只在探针里只读 —— 因为 ② 与 ③（协议/来源）必须先由**一次实测**定下来。

### 本步**不做**
机器适配器（D-183 排序）、熔炉类站点（A4）、**LLM 自动选站**、背包类站点（Traveler's Backpack）、RS 网络能力的写入（`C2+`）。

## 4. 第二步（预告，不在本轮）

1. 用实测结果给 RS 站点定 `TakeProtocol` / `MaterialSource`，写进描述符；
2. 守恒断言按来源分口径（材料在网络上时，改判"网络库存变化"或只判产物）；
3. 注册 `WriteReason.CRAFT_GRID_WRITE` + A 表条目（持久矩阵的摆料/清理 = 世界写入）；
4. 夹具：`alice:craft_station_check` 增加"选定 RS 站点 → 合成 → 清理 → 账本对账"。

## 5. 需要你确认

1. 你说的"合成升级的页签"具体是**哪个 GUI**？（RS1 里合成是独立的 `Crafting Grid` 方块；升级装在机器上）
2. 第一步按上面 S1-1..S1-5 走吗？还是先把 **S1-3 只读探针**单独做完看事实？
3. 场景用 `Creative Controller + Creative Storage Block + Crafting Grid` 三块相邻，可以吗？
