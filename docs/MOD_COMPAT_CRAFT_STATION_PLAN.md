# 模组兼容范例 · 第一步：**可切换的合成工作站**（设计 v2，2026-09-13）

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

## 5. 待你确认

1. **Refined Storage 要不要撤掉**？（它不是你说的那个；留着无害但客户端更杂）
2. 场景用 **Sophisticated 箱子 + bot 自己装升级** 这条路可以吗？（比猜 NBT 稳）
3. 第一步先做 **S1-3 只读探针**（看现场事实），还是直接 S1-1→S1-5 一起做？
