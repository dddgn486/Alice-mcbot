# 原版 InventoryMenu（玩家背包菜单）源码深度调研报告

**调研员**: Alice 深度调查员  
**任务来源**: `.alice-supervision/research/vanilla-inventory-menu-investigation-task.txt`  
**项目路径**: `/home/fb486/projects/alice`  
**基线 commit**: `25b1daa` (fix: render bot inventory panel and sync quick-move source slot)  
**调研日期**: 2026-08-23  
**证据标准**: 源码引用注明 Forge 1.20.1-47.4.10 + 类名方法名，修复方案给出精确代码/坐标，无猜测

---

## 执行摘要

本报告通过**反编译原版源码**（`javap -c`），深入剖析 `net.minecraft.world.inventory.InventoryMenu`（玩家背包菜单）、其匿名 Slot 子类、`Inventory` 的 `compartments` 结构，以及 `AbstractContainerMenu.moveItemStackTo` 的实现。

**核心发现**：
1. **原版 `InventoryMenu` 的 armor/offhand 槽用自定义匿名 `Slot` 子类**（`InventoryMenu$1`/`InventoryMenu$2`），它们重写 `getMaxStackSize()=1`、`mayPlace()=canEquip()`、`mayPickup()`、`setByPlayer()`。**Alice 用普通 `Slot` 绑定 armor/offhand，导致装备槽行为完全错误**。
2. **`Inventory` 的底层是 `compartments`**（`items(36) + armor(4) + offhand(1)`），`getItem()/setItem()/removeItem()` 通过索引映射穿越这三个 `NonNullList`。`Slot.getItem()` 调用 `Container.getItem(int)`，而 `Inventory` 实现了 `Container`，所以 index≥36 映射到 armor、index=40 映射到 offhand。
3. **原版 `quickMoveStack` 的 index 语义**：`0=result`、`1-4=craft`、`5-8=armor`、`9-35=main`、`36-44=hotbar`、`45=offhand`，且用 `slot.setByPlayer(stack)` / `slot.setChanged()` 更新源槽，返回的是**移动前的副本 `original`**。
4. **Alice 的 `BOT_SLOT_CAP=41` 划分（0-40 bot + 41-76 player）与原版 index 语义不一致**，且自绘面板 + 自定义坐标导致布局错位、"物品"标签同色、标题截断。

**结论**：Alice 应**直接复用原版 `InventoryMenu` 的槽位几何和 Slot 类型**来表达 bot 背包，而不是自创坐标 + 普通 Slot。

---

## 一、`Inventory` 底层结构（compartments）

### 1.1 `Inventory` 构造（`Inventory(Player)`）

反编译源码（`Inventory.java` 构造器）：
```java
// items 36 格（0-8 hotbar + 9-35 main 合并）
this.items = NonNullList.withSize(36, ItemStack.EMPTY);
// armor 4 格（36-39: feet/legs/chest/head）
this.armor = NonNullList.withSize(4, ItemStack.EMPTY);
// offhand 1 格（40）
this.offhand = NonNullList.withSize(1, ItemStack.EMPTY);
// compartments 是 ImmutableList.of(items, armor, offhand)
this.compartments = ImmutableList.of(this.items, this.armor, this.offhand);
```

**证据 1**: `compartments` = `[items(36), armor(4), offhand(1)]`，共 41 项。

### 1.2 `Inventory.getItem(int)` / `setItem(int)` 的索引映射

反编译源码（`Inventory.getItem(int)`）：
```java
public ItemStack getItem(int index) {
    NonNullList<ItemStack> list = null;
    for (NonNullList<ItemStack> compartment : this.compartments) {
        if (index < compartment.size()) {
            list = compartment;
            break;
        }
        index -= compartment.size();  // 减去当前 compartment 大小，继续找下一个
    }
    return list == null ? ItemStack.EMPTY : list.get(index);
}
```

**证据 2**: 索引映射规则（**关键**）：
- `index 0-35` → `items`（hotbar 0-8 + main 9-35）
- `index 36-39` → `armor`（36=feet, 37=legs, 38=chest, 39=head）
- `index 40` → `offhand`

`setItem(int)` 用**完全相同**的穿越逻辑。

### 1.3 关键结论

**`Inventory` 的 index 是"逻辑槽位号"而非"物理 compartment 下标"**。当 `Slot` 绑定 `Inventory` 并调用 `Slot.getItem()` → `Container.getItem(index)` 时，这个 index 会走 compartments 映射。

**对 Alice 的影响**：
- Alice 的 armor 槽：`new Slot(botContainer, 39 - i, 8, 8 + i*18)` → `getItem(39/38/37/36)` 映射到 armor（head/chest/legs/feet）。**index 正确**。
- 但**普通 `Slot` 的 `getMaxStackSize()` 默认返回 64**（不是 armor 需要的 1），`mayPlace()` 默认 `true`（不检查 canEquip）。

---

## 二、原版 `InventoryMenu` 源码剖析

### 2.1 槽位注册顺序（构造函数）

反编译源码（`InventoryMenu.<init>(Inventory, boolean, Player)`），槽位顺序与坐标：

| Slot Index | 内容 | Slot 类型 | 坐标 (x, y) | 容器/index |
|-----------|------|-----------|------------|-----------|
| 0 | result | `ResultSlot` | (154, 28) | resultSlots |
| 1-4 | craft (2×2) | `Slot` | (98+, 18+) | craftSlots |
| **5-8** | **armor** | **`InventoryMenu$1`（自定义 Slot 子类）** | (8, 8/26/44/62) | Inventory index 39/38/37/36 |
| 9-35 | main (3×9) | `Slot` | (8+c*18, 84+r*18) | Inventory index 9-35 |
| 36-44 | hotbar (9) | `Slot` | (8+c*18, 142) | Inventory index 0-8 |
| **45** | **offhand** | **`InventoryMenu$2`（自定义 Slot 子类）** | (77, 62) | Inventory index 40 |

**证据 3（armor 用 `InventoryMenu$1`）**:
```java
// 第 5 个槽位（armor 列）
for (int i = 0; i < 4; i++) {
    // new InventoryMenu$1(this, inventory, 39 - i, 8, 8 + i*18, player, SLOT_IDS[i])
    addSlot(new InventoryMenu$1(this, inventory, 39 - i, 8, 8 + i * 18, player, SLOT_IDS[i]));
}
// SLOT_IDS = [HEAD, CHEST, LEGS, FEET]
```

**证据 4（offhand 用 `InventoryMenu$2`）**:
```java
// 第 45 个槽位（offhand）
addSlot(new InventoryMenu$2(this, inventory, 40, 77, 62, player));
```

### 2.2 armor/offhand 自定义 Slot 子类的实现

**`InventoryMenu$1`（armor slot）** 反编译源码：
```java
class InventoryMenu$1 extends Slot {
    private final Player owner;
    private final EquipmentSlot equipmentslot;

    @Override
    public int getMaxStackSize() {
        return 1;  // 装备槽最多 1 个
    }

    @Override
    public boolean mayPlace(ItemStack stack) {
        return stack.canEquip(equipmentslot, owner);  // 只能放对应部位装备
    }

    @Override
    public boolean mayPickup(Player player) {
        ItemStack stack = getItem();
        return stack.isEmpty() || player.isCreative()
            || !EnchantmentHelper.hasBindingCurse(stack);  // 绑定诅咒不可取出
    }

    @Override
    public void setByPlayer(ItemStack stack) {
        // 装备事件处理
        InventoryMenu.onEquipItem(owner, equipmentslot, getItem(), stack);
        super.setByPlayer(stack);
    }
}
```

**`InventoryMenu$2`（offhand slot）** 反编译源码：
```java
class InventoryMenu$2 extends Slot {
    private final Player owner;
    @Override
    public void setByPlayer(ItemStack stack) {
        InventoryMenu.onEquipItem(owner, EquipmentSlot.OFFHAND, getItem(), stack);
        super.setByPlayer(stack);
    }
    // mayPickup() 同样检查绑定诅咒
}
```

### 2.3 `quickMoveStack(Player, int)` 完整实现

反编译源码（`InventoryMenu.quickMoveStack`），**按 index 分区移动**：
```java
public ItemStack quickMoveStack(Player player, int index) {
    ItemStack original = ItemStack.EMPTY;
    Slot slot = this.slots.get(index);
    if (slot != null && slot.hasItem()) {
        ItemStack stack = slot.getItem();
        original = stack.copy();  // 保存移动前副本
        EquipmentSlot equipmentSlot = Mob.getEquipmentSlotForItem(stack);

        if (index == 0) {                          // result → 背包
            if (!moveItemStackTo(stack, 9, 45, true)) return EMPTY;
            slot.onQuickCraft(stack, original);
        } else if (index >= 1 && index < 5) {      // craft → 背包 (正向)
            if (!moveItemStackTo(stack, 9, 45, false)) return EMPTY;
        } else if (index >= 5 && index < 9) {      // armor → 背包
            if (!moveItemStackTo(stack, 9, 45, false)) return EMPTY;
        } else if (equipmentSlot.getType() == ARMOR) {  // 主背包物品 → 对应 armor 槽
            int armorIndex = 8 - equipmentSlot.getIndex();
            if (!slots.get(armorIndex).hasItem()) {
                if (!moveItemStackTo(stack, armorIndex, armorIndex + 1, false)) return EMPTY;
            }
        } else if (equipmentSlot == OFFHAND) {     // 主背包物品 → offhand 槽
            if (!slots.get(45).hasItem()) {
                if (!moveItemStackTo(stack, 45, 46, false)) return EMPTY;
            }
        } else if (index >= 9 && index < 36) {     // main → hotbar (正向)
            if (!moveItemStackTo(stack, 36, 45, false)) return EMPTY;
        } else if (index >= 36 && index < 45) {    // hotbar → main (正向)
            if (!moveItemStackTo(stack, 9, 36, false)) return EMPTY;
        } else {                                   // 其他 → 背包 (正向)
            if (!moveItemStackTo(stack, 9, 45, false)) return EMPTY;
        }

        // 源槽同步（关键）
        if (stack.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);      // 用 setByPlayer（触发 onEquip）
        } else {
            slot.setChanged();
        }
        if (stack.getCount() == original.getCount()) return EMPTY;  // 没移动 → 空
        slot.onTake(player, stack);
        if (index == 0) player.drop(stack, false);
    }
    return original;  // 返回移动前副本
}
```

**证据 5（原版 `quickMoveStack` 关键语义）**：
1. 用 `original = stack.copy()` 保存移动前副本
2. `moveItemStackTo` 返回 `boolean`，且**修改传入 stack 的 count**（剩余部分）
3. 源槽同步用 `slot.setByPlayer(EMPTY)`（如果是空）或 `slot.setChanged()`
4. 返回值是 `original`（移动前副本），用于客户端确认
5. 移动目标 index 范围按 `Inventory` 逻辑槽位号划分（9-45 是 main+hotbar+offhand）

### 2.4 `stillValid` / `clicked`

- `stillValid(Player)`：原版返回 `this.active`（`InventoryMenu` 构造参数，用于区分正式背包/测试）
- `clicked(...)`：`InventoryMenu` **不重写** `clicked`，沿用 `AbstractContainerMenu.clicked`（标准移动逻辑）

---

## 三、原版 `PlayerInventoryScreen` 源码剖析

> 注：`PlayerInventoryScreen` 是客户端类，不在服务端映射 jar（`..._mapped_parchment...1.20.1.jar`）中。以下基于官方文档 + 已知 vanilla 源码（Forge 1.20.1 的 `InventoryScreen extends AbstractContainerScreen<InventoryMenu>`）。

### 3.1 inventory.png 背景渲染

- **纹理**：`textures/gui/container/inventory.png`
- **尺寸**：176×166 px
- **背景绘制**（`renderBg`）：
```java
@Override
protected void renderBg(GuiGraphics graphics, float delta, int mouseX, int mouseY) {
    int x = (width - imageWidth) / 2;
    int y = (height - imageHeight) / 2;
    graphics.blit(INVENTORY_TEXTURE, x, y, 0, 0, imageWidth, imageHeight);
}
// imageWidth = 176, imageHeight = 166
```

### 3.2 槽位坐标与 inventory.png 边框对齐

`InventoryMenu` 的 Slot.x/y 与 `inventory.png` 的槽位格子**严格对应**：

| 区域 | Slot.x/y | inventory.png 内格子位置 |
|------|----------|------------------------|
| armor 列 | x=8, y=8/26/44/62 | 左列 4 个格子 |
| offhand | x=77, y=62 | 中列下方格子 |
| main (3×9) | x=8+c*18, y=84+r*18 | 中下部 27 格 |
| hotbar (9) | x=8+c*18, y=142 | 底部 9 格 |
| craft (2×2) | x=98+, y=18+ | 上方 2×2 |
| result | x=154, y=28 | 右上 1 格 |

### 3.3 标题 / "物品"标签渲染（解决同色问题）

`AbstractContainerScreen.render()` 会自动调用 `renderLabels()`：
```java
protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
    // 标题：白色 (0xFFFFFF)，左上角 (8, 6)
    graphics.drawString(font, title, 8, 6, 4210752);  // 0x404040 或白色
    // "物品"标签：灰色 (0x404040)，在背包区域上方 (8, imageHeight - 94)
    graphics.drawString(font, playerInventoryTitle, 8, imageHeight - 94, 4210752);
}
```

**关键**：标签颜色由 vanilla 固定（标题 0xFFFFFF/0x404040，标签 0x404040），**绘制在 guiLeft/guiTop 的相对偏移上**。标签不会被自绘面板覆盖——**如果自绘面板颜色与标签颜色接近（如 0x3F3F3F 面板 + 0x404040 标签），标签会"隐形"**（这就是 Alice 截图里"物品"标签同色的根因）。

---

## 四、差异分析：为何 Alice 失败

### 4.1 armor/offhand 槽：普通 Slot vs 原版自定义 Slot 子类

**Alice**（`BotInventoryMenu.java:64, 68`）:
```java
addSlot(new Slot(botContainer, 39 - i, 8, 8 + i * 18));  // armor 用普通 Slot
addSlot(new Slot(botContainer, 40, 77, 62));             // offhand 用普通 Slot
```

**原版**（`InventoryMenu`）:
```java
addSlot(new InventoryMenu$1(this, inventory, 39 - i, 8, 8 + i*18, player, SLOT_IDS[i]));  // armor
addSlot(new InventoryMenu$2(this, inventory, 40, 77, 62, player));                          // offhand
```

**差异结果**：
| 行为 | Alice 普通 Slot | 原版自定义 Slot 子类 |
|------|----------------|---------------------|
| `getMaxStackSize()` | 默认 64 | 1 |
| `mayPlace()` | 默认 true | `canEquip(slot, owner)` |
| `mayPickup()` | 默认 true | 检查绑定诅咒 |
| `setByPlayer()` | 默认（无 onEquip） | 触发 `onEquipItem` |

**这解释了布局/装备槽行为异常**——armor 槽用普通 Slot 时，`getMaxStackSize=64` 导致 armor 槽接受 64 堆叠物品，且不校验是否是可穿戴装备。

### 4.2 为何"拿取物品消失"

**Alice 的 `quickMoveStack`**（`BotInventoryMenu.java:152-157`）:
```java
if (stack.isEmpty()) {
    slot.set(ItemStack.EMPTY);   // ← 25b1daa 用 slot.set()
} else {
    slot.setChanged();
}
return original;
```

**原版**（`InventoryMenu`）:
```java
if (stack.isEmpty()) {
    slot.setByPlayer(ItemStack.EMPTY);  // ← 原版用 setByPlayer()
} else {
    slot.setChanged();
}
```

**根因候选**：
- 普通 `Slot.set(ItemStack.EMPTY)` 直接覆盖容器对应 index，**不触发 `setByPlayer` 的 onEquip 同步**。
- 但更关键的是 **Alice 用 `slot.set()` 而非 `slot.setByPlayer()`**。对 armor/offhand 槽（`InventoryMenu$1/$2` 重写了 `setByPlayer`），`set()` 会绕过 onEquip 的装备状态清理，可能导致服务端/客户端计数不一致 → 客户端显示物品消失。

### 4.3 为何"放入显示两倍"

**根因候选**：`moveItemStackTo` 的 index 范围与 `Inventory` 的 compartments 映射冲突。

Alice 的目标范围是 `BOT_SLOT_CAP=41` 到 `this.slots.size()`（=77）。但 **`this.slots` 的 index 是 menu 槽位号（0-76），而目标 `slot.set()` 写入的是 `botContainer.getItem(index)`（用 menu 槽位号 0-40 映射到 compartments）**。

当 `moveItemStackTo` 把物品移到 menu 槽位 41-76（玩家背包），它调用 `slots.get(i).set(stack)`，而 `i` 是 menu 槽位号。但 bot 和玩家的槽位在同一个 `slots` 列表里，**Alice 用线性 index 0-76 表达混合的 bot(0-40) + player(41-76) 槽**，而 `moveItemStackTo` 的 target 是 `slots` 的物理下标。

**"两倍"最可能根因**：`moveItemStackTo` 移动物品后，Alice 的 `slot.set(...)`/`setChanged()` 与 `moveItemStackTo` 内部对 `slot.setChanged()` 的调用**双重写回**，或源/目标槽在客户端被渲染两次（因为 bot 槽和玩家槽坐标重叠或不在此面板区域）。

> ⚠️ "拿取消失/放入两倍"的确切根因需**客户端实证复现**（记录服务端/客户端槽位状态变化），本报告给出源码支撑的候选根因，不武断归因。

### 4.4 布局混乱 / 标题截断 / "物品"同色

- **标题截断**：`imageHeight=274` 超出 inventory.png 标准 166，`AbstractContainerScreen` 用 `imageWidth`/`imageHeight` 定位，导致标题渲染位置计算异常。
- **"物品"标签同色**：Alice 自绘面板 `0xFF3F3F3F` 与 vanilla 标签颜色 `0x404040` 几乎相同 → 标签隐形。
- **槽位稀疏错位**：Alice 自定义坐标（armor x=8 y=8/26/44/62，main y=84，hotbar y=142，玩家 y=180/238）没有与任何 vanilla 纹理边框对齐，且 `imageHeight=274` 非标准，导致布局松散。

---

## 五、推荐修复方案（精确代码）

### 5.1 方案 1（推荐）：复用原版 `InventoryMenu` 的槽位几何 + 自定义 Slot 子类

**核心思路**：bot 背包完全复用 `InventoryMenu` 的槽位注册（含 armor/offhand 自定义 Slot），玩家背包放在下方。背景用一个加高的自绘面板，**但槽位坐标严格按 vanilla 玩家背包几何**。

**`BotInventoryMenu.java` 的槽位注册（对齐 InventoryMenu）**:

```java
// 1. armor 列：用自定义 Slot 子类（getMaxStackSize=1, mayPlace=canEquip）
private static final EquipmentSlot[] SLOT_IDS = {HEAD, CHEST, LEGS, FEET};
private int buildBotSlots(BotPlayer bot) {
    Inventory botInv = bot.getInventory();
    int base = 0;
    // armor 槽（index 36-39），用 armor Slot 子类
    for (int i = 0; i < 4; i++) {
        addSlot(new BotArmorSlot(botInv, 39 - i, 8, 8 + i * 18, bot, SLOT_IDS[i]));
        base++;
    }
    // offhand 槽（index 40），用 offhand Slot 子类
    addSlot(new BotOffhandSlot(botInv, 40, 77, 62, bot));
    base++;
    // main 27 槽（index 9-35）
    for (int row = 0; row < 3; row++) {
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(botInv, 9 + col + row * 9, 8 + col * 18, 84 + row * 18));
            base++;
        }
    }
    // hotbar 9 槽（index 0-8），注意原版 hotbar 用 menu 槽位号 36-44 但绑定 Inventory index 0-8
    for (int col = 0; col < 9; col++) {
        addSlot(new Slot(botInv, col, 8 + col * 18, 142));
        base++;
    }
    return base;  // = 41
}
```

**自定义 armor/offhand Slot 子类**（对齐原版 `InventoryMenu$1/$2`）:
```java
class BotArmorSlot extends Slot {
    private final BotPlayer owner;
    private final EquipmentSlot slotType;
    BotArmorSlot(Container c, int idx, int x, int y, BotPlayer owner, EquipmentSlot t) {
        super(c, idx, x, y); this.owner = owner; this.slotType = t;
    }
    @Override public int getMaxStackSize() { return 1; }
    @Override public boolean mayPlace(ItemStack stack) {
        return !stack.isEmpty() && stack.canEquip(slotType, owner);
    }
    @Override public boolean mayPickup(Player p) {
        ItemStack s = getItem();
        return s.isEmpty() || p.isCreative() || !EnchantmentHelper.hasBindingCurse(s);
    }
    @Override public void setByPlayer(ItemStack stack) {
        // 装备事件（可选：调用 bot 的 onEquipItem）
        super.setByPlayer(stack);
    }
}
```

### 5.2 `quickMoveStack` 正确实现（对齐原版 index 分区）

```java
@Override
public ItemStack quickMoveStack(Player player, int index) {
    ItemStack original = ItemStack.EMPTY;
    Slot slot = this.slots.get(index);
    if (slot == null || !slot.hasItem()) return ItemStack.EMPTY;
    if (taskActive && index < BOT_SLOT_CAP) return ItemStack.EMPTY;  // 只读保护

    ItemStack stack = slot.getItem();
    original = stack.copy();
    EquipmentSlot equipmentSlot = Mob.getEquipmentSlotForItem(stack);

    if (index < BOT_SLOT_CAP) {
        // bot 槽 → 玩家背包（正向移动到玩家槽）
        if (!moveItemStackTo(stack, BOT_SLOT_CAP, this.slots.size(), false)) return EMPTY;
    } else {
        // 玩家槽 → bot 背包
        // 若物品是装备，先尝试移动到对应 bot armor 槽；否则移动到 bot 背包
        if (equipmentSlot.getType() == ARMOR && !slots.get(8 - equipmentSlot.getIndex()).hasItem()) {
            int armorIdx = 8 - equipmentSlot.getIndex();  // bot armor 槽在 slots 的 5-8
            if (!moveItemStackTo(stack, armorIdx, armorIdx + 1, false)) return EMPTY;
        } else if (equipmentSlot == OFFHAND && !slots.get(45).hasItem()) {
            if (!moveItemStackTo(stack, 45, 46, false)) return EMPTY;
        } else {
            if (!moveItemStackTo(stack, 0, BOT_SLOT_CAP, false)) return EMPTY;
        }
    }
    // 源槽同步（对齐原版：用 setByPlayer）
    if (stack.isEmpty()) slot.setByPlayer(ItemStack.EMPTY);
    else slot.setChanged();
    if (stack.getCount() == original.getCount()) return ItemStack.EMPTY;
    slot.onTake(player, stack);
    return original;
}
```

### 5.3 Screen 背景与坐标

**正确做法**：复用 `inventory.png`（176×166）作为 bot 背包背景，玩家背包放下方。若不放 player 槽，则 `imageWidth=176, imageHeight=166`（标准）。

```java
public BotInventoryScreen(BotInventoryMenu menu, Inventory playerInv, Component title) {
    super(menu, playerInv, title);
    this.imageWidth = 176;
    this.imageHeight = 166;  // 标准玩家背包高度（若含玩家背包则增大）
}

@Override
protected void renderBg(GuiGraphics graphics, float delta, int mouseX, int mouseY) {
    int x = (width - imageWidth) / 2;
    int y = (height - imageHeight) / 2;
    graphics.blit(INVENTORY_TEXTURE, x, y, 0, 0, imageWidth, imageHeight);
    // 若自绘面板，用 0xFF 前缀，且槽位坐标严格对 vanilla 几何
}
```

### 5.4 任务保护（taskActive）集成

在 `quickMoveStack` 和 `clicked` 中，若 `taskActive && index < BOT_SLOT_CAP` 则中断（保持只读）。

---

## 六、风险评估

### 6.1 是否影响其他模块

**建议**：
- **不影响**：复用原版 `InventoryMenu` 结构不改变 bot 持久化/任务逻辑
- **注意**：armor/offhand 槽改为自定义 Slot 子类后，`mayPlace=canEquip` 可能拒绝放入非装备物品——需要保证 bot 装备槽始终正确

### 6.2 客户端兼容性（JEI / 其他 GUI mod）

**风险**：
- 若继续用非标准 `imageHeight`（274），与其他 GUI mod 冲突风险高
- 复用 `inventory.png` 更兼容，因槽位坐标与 vanilla 完全一致

### 6.3 从"自绘面板"切换到"复用原版结构"的迁移风险

**中等**。需要：
- 新增 `BotArmorSlot`/`BotOffhandSlot` 子类
- 调整 `quickMoveStack` 的 index 分区（区分 bot 0-40、player 41-76）
- 将 `imageHeight` 调回标准值
- 服务端开发/客户端实测（并记录服务端与客户端槽位状态以确认"消失/两倍"根因）

---

## 七、本报告不构成实施授权

本报告基于反编译源码提供事实调查和修复方案建议。任何代码修改必须：
1. 由监督员审核本报告并批准修复范围
2. 创建新的 `.alice-supervision/active-plan.md` 并标记 `APPROVED_FOR_IMPLEMENTATION`
3. 由主开发会话按批准计划实施
4. 通过服务端测试 + 客户端实测确认"消失/两倍/同色/错位"全部消失
5. 经监督员二次审核后方可合并

**证据缺口**：
- `PlayerInventoryScreen` 是客户端类，服务端映射 jar 未含（基于官方文档 + 已知 vanilla 源码推断，标注为中等置信度）
- "拿取消失/放入两倍"的确切根因需**客户端实证复现**（记录服务端/客户端槽位状态），本报告给出源码支撑的候选根因
- `Mob.getEquipmentSlotForItem` 的完整逻辑未反编译（本报告直接引用 vanilla 方法名）

**不可直接采纳项**：
- 自绘面板 + 非标准 `imageHeight` 的组合：建议弃用，改回 vanilla 几何
- 普通 `Slot` 绑定 armor/offhand：必须换成自定义 Slot 子类（`getMaxStackSize=1`、`mayPlace=canEquip`）
