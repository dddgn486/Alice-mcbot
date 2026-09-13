# 模组兼容范例 · 第一步：**可切换的合成工作站**（设计 v2，2026-09-13）

> **正式译名**（用户 2026-09-13 更正）：**精妙存储**（Sophisticated Storage）与 **精妙背包**（Sophisticated Backpacks）。
> 两者是**分开的两个模组**，但**共用 Sophisticated Core**（升级框架与"升级标签页"都在 Core）
> ⇒ 同一个适配器将来天然覆盖背包，不必写第二套。**背包的 jar 待用户截图后再装**。
>
> **用户裁定（本轮）**：① Refined Storage **不撤**（AE 的兄弟模组，暂不做兼容，但不会冲突）；
> ② "bot 自己用菜单协议把合成升级点进升级槽"**可以**，且**装升级本身算一种"配置行为"**（自成一类）；
> ③ **"点开标签页"单独算一层**；④ 对"**会更改槽位信息的子标签页**"的菜单也照此办理；
> ⑤ 若 AI 有更好的适配办法可以提。
>
> **v1 更正**：v1 把目标模组认成了 **Refined Storage**（中文也译"精致存储"）。
> 用户澄清后确认目标是 **Sophisticated Storage（精致存储，P3pp3rF1y）**：
> *"每个容器（背包，箱子，潜影盒）都能装合成升级，打开容器后，右侧会有标签，点击标签就能打开"* + *"合成升级的页签"*。
> v1 的 RS 调查保留在 **§4 附录**（它是将来的另一个目标，事实没错，只是不是本轮）。
>
> 用户裁定（原话）："现在只是做**兼容范例测试**，先实现**工作站的可切换**，不急着完全适配其他模组。"

## 0. 本轮装了什么

| 模组 | 版本 | sha1 | 说明 |
|---|---|---|---|
| **Sophisticated Storage** | `1.20.1-1.4.86.2131` | `f01592882a63856927de2b5ef90aa0ce9924a41e` | 目标模组（箱子/木桶/潜影盒 + 升级 + 右侧标签页） |
| **Sophisticated Core** | `1.20.1-1.5.1.2335` | `24d7f2ee72127b7f1148245e57979e87b0b189ec` | **必需前置**（升级框架与"升级标签页"实现都在这里） |
| （误装）Refined Storage | `1.12.4` | `014bf4de4975f6310d22a6ed06c6a7e1e0805641` | §4；**待用户决定是否撤掉** |

`mods.toml` 事实：Storage 要求 `forge [47.1,)` + `sophisticatedcore [1.3.82.+,)`（我们装的是 1.5.1.2335 ✓）；Core 无依赖。
**背包（Sophisticated Backpacks）共用同一个 Core** ⇒ 同一个适配器将来天然覆盖背包，不用写第二套。

## 1. 目标模组的关键事实（全部有出处）

调查手段：jar 内 `META-INF/mods.toml` + `javap -p` 签名 + 上游 `P3pp3rF1y/SophisticatedCore` **branch `1.20.x`** 源码 + jar 内语言文件。
证据文件：`upgrades/crafting/CraftingUpgradeContainer.java`、`CraftingUpgradeWrapper.java`、`common/gui/StorageContainerMenuBase.java`、`common/gui/UpgradeContainerBase.java`。

### 1.1 **"合成升级的标签页"确认存在，而且它就是你说的那样**

- `item.sophisticatedstorage.crafting_upgrade`，tooltip 原文：**"Crafting table in an upgrade tab"**；
- `gui.sophisticatedcore.upgrades.crafting = Craft`；`CraftingUpgradeTab` + `ICraftingUIPart`（`onCraftingSlotsDisplayed/Hidden`）就是右侧标签；
- 容器的升级槽 → `UpgradeContainerRegistry.instantiateContainer` → `CraftingUpgradeContainer`。

### 1.2 **九个格子确实"存东西"，关掉不归还**（你的第 2 条 ✓）

```java
// CraftingUpgradeWrapper
inventory = new ItemStackHandler(9) { ... upgrade.addTagElement("craftingInventory", serializeNBT()); ... };
// CraftingUpgradeContainer
private final CraftingItemHandler craftMatrix;   // extends TransientCraftingContainer
```
矩阵 9 格的内容**序列化进那颗升级物品自身的 NBT**（`craftingInventory`）⇒ 随容器存档持久化，
**关闭 GUI 不会放回**（对比原版工作台：随开随灭）。你观察到的现象与实现完全一致。

### 1.3 **合成产物的 QuickMove 目标真的可切换**（你的第 3 条 ✓，而且有按钮名）

```java
// CraftingUpgradeWrapper
public boolean shouldShiftClickIntoStorage() { return NBTHelper.getBoolean(upgrade, "shiftClickIntoStorage").orElse(true); }
public boolean insertIntoStorageOrPlayer(Player p, ItemStack s) {
    if (shouldShiftClickIntoStorage() && insertIntoInventory(s, storageWrapper.getInventoryHandler(), false).isEmpty()) return true;
    return p.getInventory().add(s);
}
```
语言文件里的两个按钮名（就是你看到的那个切换）：
```
gui.sophisticatedstorage.upgrades.buttons.shift_click_into_storage   = Shift Click Result Into Storage
gui.sophisticatedstorage.upgrades.buttons.shift_click_into_inventory = Shift Click Result Into Player's Inventory
```
默认 = **进容器**（`orElse(true)`）。另有 `refill_crafting_grid`（默认 false）与按钮 `Refill Crafting Grid / Do Not Refill Crafting Grid`。

### 1.4 **决定性的工程事实：标签页是"纯视觉"的，槽位一直在菜单里**

```java
// CraftingUpgradeContainer 构造：矩阵 9 槽 + 结果槽，全部在 (-100, -100)
slots.add(new SlotSuppliedHandler(..., slot, -100, -100) { ... });
craftingResultSlot = new ResultSlot(player, craftMatrix, craftResult, slot, -100, -100) { ... };
// StorageContainerMenuBase#addUpgradeSettingsContainers：装了升级就加槽
for (UpgradeContainerBase<?, ?> container : upgradeContainers.values()) { container.getSlots().forEach(this::addUpgradeSlot); container.onInit(); }
storageWrapper.getOpenTabId().ifPresent(id -> upgradeContainers.get(id).setIsOpen(true));   // 只影响"哪个是打开的"
```
⇒ **只要升级装着，槽位就在菜单里（只是坐标 -100,-100 画在屏幕外）**。
**服务端 bot 不需要"点标签"、不需要发包**就能摆料/取产物 —— 这对我们是天大的好消息。

### 1.5 **矩阵/结果都是原版类型** ⇒ 通用发现器直接可用

| 角色 | 实际类型 | 我们的判据（**零模组知识**） |
|---|---|---|
| 9 格矩阵 | `CraftingItemHandler extends net.minecraft.world.inventory.TransientCraftingContainer` | `slot.container instanceof CraftingContainer` |
| 结果槽 | `new ResultSlot(player, craftMatrix, craftResult, …, -100, -100)`（原版类） | `slot.container instanceof ResultContainer` / `instanceof ResultSlot` |
| 尺寸 | `CraftingContainer.getWidth()/getHeight()`（原版接口，已在 Forge 映射 jar 上 `javap` 验证） | 直接读 |

⇒ **§1.1–1.5 合起来**：这个模组的合成标签页是"**原版网格 + 原版结果槽 + 槽位常驻菜单 + 目标可切换**"，
是"**不硬编码合成方式**"这条缝的**理想第一个范例**。

## 2. 对 Alice 的影响（四个维度，仍然成立）

| # | 维度 | 结论 |
|---|---|---|
| 2.1 | **槽位必须"发现"** | Sophisticated 菜单的槽序由"存储格数 + 升级槽 + 各升级贡献的槽"动态拼成 ⇒ 硬编码下标必错；`CraftingContainer`/`ResultContainer` 判据可解 |
| 2.2 | **结果槽协议是第二维** | 同一个 `QUICK_MOVE`：原版 = 合 1 次进背包；Sophisticated = **按"Shift Click Result Into…"设置进容器或玩家背包**，且 `StorageContainerMenuBase` 有 `getRepeatedQuickMoveLimit` ⇒ 可能**重复多次**。⇒ 先不猜：第一步只**读**事实，执行协议第二步实测后定 |
| 2.3 | **材料来源是第三维** | `extractFromStorageOrPlayer` = **先容器、再玩家背包**；`insertIntoStorageOrPlayer` 同理 ⇒ 我们"玩家背包 −M / 产物 +1"的守恒断言口径不适用 |
| 2.4 | **持久 = 写入** | A3 的 `no_world_write` 只对"随开随灭"的原版网格成立。这里矩阵内容存在**升级物品的 NBT**里 ⇒ 摆料/清理 = **对世界状态的写入**（与 RS 的方块实体同理）⇒ 需要新 `WriteReason` + A 表条目 |
| 2.5 | **站点获取同形** | 存储容器 = 世界方块（背包类将来是持物品），打开方式仍是"右键 → 菜单" ⇒ 现有"找方块→走近→右键→开菜单"形状可复用 |

## 2.6 分层模型（回应"升级=配置行为""标签页=单独一层"）

把一个"工作站"拆成**六层**，每层各自有**可见的产物**与**如实失败码**，谁都不许越层替下一层做决定：

| 层 | 名字 | 做什么 | 产物 / 失败码 | 是否改世界 |
|---|---|---|---|---|
| L0 | **站点定位** `findStation` | 找"能做这件事的东西"：世界方块 / 手持物品 / 玩家状态 | `StationRef` / `station_not_found` | 否 |
| L1 | **打开菜单** `openMenu` | 右键/使用物品 → `MenuSession` 到 `OPEN` | 菜单实例 / `menu_open_failed` | 否（打开菜单=读取事件，D-163 已裁定） |
| L2 | **站点装配（配置行为）** `provision` | **装上升级**这类"一次性配置"：改的是容器/物品的持久数据 | `station_provisioned` / `provision_slot_busy` / `upgrade_missing` | **是**（容器 NBT） |
| L3 | **状态选择** `selectState` | **点开标签页/子页**：把菜单切到"目标网格可见/可寻址"的那个状态 | `state_selected` / `state_change_unverified` / `state_has_no_grid` | **是**（`openTabId`/`KEEP_TAB_OPEN` 是持久可见状态） |
| L4 | **网格发现** `discoverGrid` | **唯一判据来源**：原版 `CraftingContainer`/`ResultContainer` 判出槽位集合与尺寸 | `GridSpec` / `no_grid` … | 否 |
| L5 | **取法 + 清理** `take` / `clear` | 按协议合成、按"持久口径"清理，并把 L2/L3 的状态**复位** | 产物数 / `result_not_taken` | 是 |

**用户裁定的落点**：
- **L2 装升级 = 配置行为** ⇒ 它**不属于合成任务**：合成任务只接受"**已具备**能力"的站点，
  没装就**如实报 `station_not_provisioned`**，绝不偷偷替用户改配置。装配由**独立的一层/一个任务**承担，
  有独立的写入授权（将来 `WriteReason.STATION_PROVISION`）与账本。
- **L3 点开标签页 = 单独一层** ⇒ 即使实测发现"我们不用点也能点槽位"，这一层**仍然保留**：
  ① `openTabId` / `KEEP_TAB_OPEN` 是**持久且用户可见**的状态（`StorageContainerMenuBase.removeOpenTabIfKeepOff`
  读的是玩家设置）⇒ 我们改过就必须**复位**；② 子页菜单里这层是**必须**的（见下）。

### 2.6.1 我建议的"更好的适配办法"：**差分状态枚举**（不需要任何模组概念）

不去理解"升级/标签页/子页"这些**模组词汇**，只看**菜单的客观事实**：
1. `listStates(menu)`：把 `menu.slots` 按 **`(container 实例, isActive, 坐标)`** 分组 ⇒ 得到若干"**槽位组**"，
   每一组就是一个候选状态（不需要知道它叫什么、是谁提供的）；
2. 对**每个**状态组跑一次 `discoverGrid` ⇒ 得到 `状态 → GridSpec` 表；
3. 若多个状态都能给出网格：**让玩家选**（不做自动选优）；
4. 切换后**重新发现**并断言"目标槽位组的可寻址性真的变了"，否则 `state_change_unverified`
   —— **绝不允许"我以为切过去了"**；
5. 收尾把 L2/L3 的状态复位（与写入类副作用同规格）。

这样"**会更改槽位信息的子标签页**"天然被覆盖：因为我们的所有断言都建立在**当前状态下的发现结果**上，
**从不跨状态缓存 `GridSpec`** —— 这正是"槽位错位"这类幽灵 bug 的根治办法。

**只在必要时才升级到"显式标签页适配器"**（C1→C2，且必须实测证据）：若某模组的子页在服务端**完全不可观测**
（槽位集合不因切页而变，内容却在服务端按包替换），才为它写最小适配器发包，并且**必须**用
"切换前后 `discoverGrid`/槽位内容确实不同"作为成功证据。没有该证据就**不启用**。

### 2.6.2 与"服务端槽位常驻"的关系（实测为准，不靠推理）

源码事实：`CraftingUpgradeContainer` 把 9 格矩阵 + 结果槽一律建在 **`(-100,-100)`**；
`StorageContainerMenuBase.addUpgradeSettingsContainers` **装了升级就把槽加进菜单**；
`SlotSuppliedHandler`/匿名 `ResultSlot` **没有覆盖 `isActive()`**；`getOpenTabId()/setIsOpen` 只用于
"哪个合成容器是打开的那个"（`getOpenOrFirstCraftingContainer`）与客户端渲染（`ICraftingUIPart`）。
⇒ **预测**：服务端 bot **不点标签页也能点那些槽位**（与你在**客户端**看到的"点开才显示"并不矛盾 ——
那是渲染层，`onCraftingSlotsDisplayed/Hidden`）。
**这句只是预测**：S1-3 探针会打印"**打开前/打开后**的槽位数、坐标与 `isActive()`"，
用**差分实测**决定 L3 到底要不要发包（不猜）。无论结果如何，L3 都保留（见上）。

## 3. 第一步范围（可切换的工作站）

**目标**：把"合成工作站"做成**一等、可选、可切换**的对象；以 **Sophisticated 箱子（升级标签页）** 作为第二个范例；**只读优先**，执行接入留给第二步。

- **S1-1 通用发现器** `task/craft/GridDiscovery`（只读、零模组知识）
  `discover(menu) → {gridSlots[], width, height, resultSlot, inventoryFirst/Last, matrixClass, resultSlotClass}`
  + 拒绝码 `no_grid / no_result_slot / ambiguous_grid / grid_shape_mismatch`。
  **回归判据**：随身 2×2 与工作台 3×3 必须复现 D-163/D-165 记录的布局。
- **S1-2 工作站描述符 + 玩家可切换**（不做自动选优）
  `{ kind: INVENTORY|BLOCK, 打开方式, 网格能力, TakeProtocol, MaterialSource }`；
  `/alice craft station <auto|inventory|table|block>`；`bot_report` 增一行候选与原因。
- **S1-3 只读探针**（零参数）：打开最近的可合成菜单并打印
  `menu 类 / 槽数 / 矩阵类与尺寸 / grid 下标 / 结果槽下标与类 / 各槽 x,y / 玩家背包区间 / 拒绝码`，
  并**零写入**报告"矩阵里现在有没有东西"。
  **预期看点**：Sophisticated 箱子会打印出 **`x=-100,y=-100`** 的隐藏槽 —— 这就是"标签页是纯视觉"的现场证据。
- **S1-4 场景 + 夹具**（零参数）
  场景 `/function alice_test:sc_craft_course`：孤立平台 + 一个 Sophisticated 箱子 + 发料（材料 + 一颗合成升级）。
  **升级怎么装**：让 **bot 自己用菜单协议**把它点进升级槽（不靠猜 NBT），然后**关掉再开一次**菜单（新槽位才进 `slots`），
  再用发现器找到 3×3 + 结果槽 ⇒ 合成 ⇒ **清理**（矩阵内容要按"持久"口径清）。
  *（备选：`data merge block` 直写 NBT；但那要猜 `upgradeInventory` 路径，**不做**）*
- **S1-5 执行接入**：A2/A3 改为经描述符取 spec（**对原版保持行为等价**，电池回归证明）；
  **Sophisticated 站点本步只在探针里只读** —— 协议/来源两维要先有一次实测。

**本步不做**：机器适配器、熔炉/烹饪类站点（A4）、LLM 自动选站、背包（Sophisticated Backpacks）、RS 网络写入。

## 4. 附录：Refined Storage 1.12.4 调查摘要（非本轮目标，事实保留）

已在固定客户端装了 `refinedstorage-1.12.4.jar`（sha1 `014bf4de…`，无必需依赖，`loaderVersion [47,)`）。
要点（供将来立项）：合成是**独立方块** `refinedstorage:crafting_grid`（`GridType` 是方块属性，升级不给网格加页签）；
矩阵/结果是原版 `TransientCraftingContainer` 3×3 / `ResultContainer`，槽位是**原版槽子类**；
菜单槽序动态拼装；`quickMoveStack` 被 `BaseContainerMenu` 覆盖 → 结果槽 shift = `onCraftedShift`
（**合到满堆** + 玩家背包优先 + 溢出进网络）；单次取会**从网络补满**矩阵（材料来自网络，不在玩家背包）。
`Crafting Upgrade` 是给 Crafter 等机器插的**能耗升级**，与合成网格无关。

## 5. 状态

| 事项 | 状态 |
|---|---|
| Refined Storage 是否撤掉 | ✅ 已裁定：**不撤**（AE 的兄弟模组，暂不做兼容，不会冲突） |
| 装升级的路径 | ✅ 已裁定：bot 用菜单协议点进升级槽**可以**；升级 = **配置行为**（独立一层） |
| 标签页 | ✅ 已裁定：**单独一层**；且"子标签页会改槽位信息"的菜单也照此办理 |
| **精妙背包（Sophisticated Backpacks）** | ⏳ **待用户截图后再装**（与存储分开的模组，共用 Core） |
| 第一步先做探针还是全做 | ⏳ **用户稍后决定（决定 3）** |
| 截图 | ⏳ 等用户发（游戏内 F2 落在客户端 `screenshots/`，AI 可直接读） |
