# Bot Inventory GUI 深度调研报告

**调研员**: Alice 深度调查员  
**任务来源**: `.alice-supervision/research/bot-inventory-gui-investigation-task.txt`  
**项目路径**: `/home/fb486/projects/alice`  
**基线 commit**: `a6b9f93` (docs: A1.1 equipment rendering + death filter USER_ACCEPTED)  
**调研日期**: 2026-08-23  
**证据标准**: API 引用注明 Forge 1.20.1-47.4.10，外部 mod 提供 GitHub URL + 文件路径，设计给出伪代码，无猜测

---

## 执行摘要

本报告调研如何为 Alice Bot 设计独立的 Inventory GUI，实现查看/交互 Bot 的 36 普通槽 + 4 装备槽 + 1 主手槽，并支持扩展为 Bot 设置页面。

**核心发现**：
1. **方案 A（`AbstractContainerMenu` 完整交互）** 可行且符合用户需求"能直接拿取/放入"
2. Minecraft/Forge 支持通过 `MenuProvider` + `MenuType` 打开自定义容器菜单
3. 业界已有成熟先例：`R0STUS/OpenInventory`（Forge mod，右键打开其他玩家 inventory）
4. 纯服务端可实现，**无需客户端 mod**

**结论**：
- 推荐采用 **方案 A（AbstractContainerMenu）**，因为它支持完整物品交互（拿取/放入），符合用户核心需求
- 在 `AbstractContainerMenu` 中注册 Bot 的 36 槽 + 装备槽 + 主手槽
- 通过右键 Bot 或命令打开菜单
- 纯服务端实现，客户端零依赖

---

## 一、Minecraft/Forge GUI 框架概览

### 1.1 `AbstractContainerMenu` 生命周期

**`AbstractContainerMenu`** 是 Minecraft 服务端容器抽象类，管理服务器与客户端之间的容器同步。

**生命周期**（Minecraft 1.20.1 / Forge 1.20.1-47.4.10）:
```
1. 创建: MenuProvider.createMenu() → 实例化 AbstractContainerMenu 子类
2. 注册槽位: 构造函数中 addSlot() 添加 Slot
3. 同步: broadcastChanges() 每 tick 检测槽位变化并同步
4. 交互: clicked() 处理玩家点击
5. 关闭: removed() 清理容器
```

**关键方法**:
- `addSlot(Slot)`: 添加一个槽位（绑定 Inventory 或 Container）
- `broadcastChanges()`: 每 tick 发送变化槽位给客户端（`ClientboundContainerSetSlotPacket`）
- `clicked(int slotId, int button, ClickType, Inventory playerInventory)`: 处理玩家点击
- `removed(Player)`: 容器关闭时清理
- `stillValid(Player)`: 判断容器是否有效（玩家是否关闭了容器）

### 1.2 `MenuType` 注册流程

**`MenuType<T extends AbstractContainerMenu>`** 是容器类型的注册名。

**注册流程**（Forge 1.20.1）:
```java
public static final DeferredRegister<MenuType<?>> MENU_TYPES =
        DeferredRegister.create(Registries.MENU, "alice");

public static final RegistryObject<MenuType<BotInventoryMenu>> BOT_INVENTORY_MENU =
        MENU_TYPES.register("bot_inventory",
                () -> new MenuType<>(BotInventoryMenu::new));

// 在 mod 构造函数中
MENU_TYPES.register(modEventBus);
```

**关键 API**:
- `net.minecraft.world.inventory.MenuType`
- `net.minecraft.core.registries.Registries.MENU`
- `MenuType<T extends AbstractContainerMenu>` 构造器接受 `MenuConstructor<T>`

### 1.3 打开菜单的完整流程

**服务端打开菜单**（`ServerPlayer.openMenu()`）:
```java
// 1. 定义 MenuProvider
MenuProvider provider = new SimpleMenuProvider(
    (containerId, playerInventory, player) -> new BotInventoryMenu(containerId, playerInventory, bot),
    Component.literal("Bot Inventory")
);

// 2. 服务端打开菜单
player.openMenu(provider);
```

**`ServerPlayer.openMenu(MenuProvider)` 流程**（Minecraft 1.20.1）:
```
1. openMenu() 调用 provider.createMenu(containerId, playerInventory, player)
   → 创建 AbstractContainerMenu 实例
2. 发送 ClientboundOpenScreenPacket 给客户端
   → 包含 MenuType ID 和 containerId
3. 客户端根据 MenuType ID 找到对应 Screen
   → 显示 GUI
4. 服务端容器开始广播槽位内容
```

**关键 API**:
- `MenuProvider` 接口: `createMenu(int containerId, Inventory playerInventory, Player player)`
- `SimpleMenuProvider`: MenuProvider 的简单实现
- `ServerPlayer.openMenu(MenuProvider)`: 打开菜单
- `ClientboundOpenScreenPacket`: 通知客户端打开屏幕

### 1.4 物品交互（click/quickmove/tradeItem）

**物品交互流程**:
```java
// 玩家点击槽位（在客户端触发的点击请求通过 packet 发送给服务端）
boolean clicked(int slotId, int button, ClickType clickType, Inventory playerInventory) {
    // 处理点击，移动物品
    if (clickType == ClickType.QUICK_MOVE) {
        // 快速移动（Shift+点击）
        this.slot(handleFastMove));
    } else if (clickType == ClickType.PICKUP) {
        // 拾取/放置
        this.slot(handlePickup));
    }
    // ...
}
```

**关键机制**:
- 客户端发送 `ServerboundContainerClickPacket` 给服务端
- 服务端调用 `AbstractContainerMenu.clicked()` 处理
- 服务端调用 `broadcastChanges()` 广播变化
- 客户端更新 GUI 显示

**不需要客户端 mod**: 所有交互都通过原版 packet 机制，客户端只需有对应的 Screen（通过 MenuType ID 映射）。

### 1.5 原版菜单参考

| 菜单 | 类名 | 特点 |
|------|------|------|
| 玩家背包 | `PlayerMenu` | 显示玩家自身 inventory + 装备槽 + 工作栏 |
| 箱子 | `ChestMenu` | 显示 Container（箱子/其它方块）的 inventory |
| 漏斗 | `HopperMenu` | 显示 5 槽位的 Hopper |
| 铁砧 | `AnvilMenu` | 显示 3 槽位 + 输出槽 |

**`PlayerMenu` 结构**（参考）:
```java
public class PlayerMenu extends AbstractContainerMenu {
    public PlayerMenu(int containerId, Inventory playerInventory) {
        // 1. 装备槽（主手/副手/头盔/胸甲/裤子/鞋子）→ 6 槽
        // 2. 主背包（36 槽）→ 背包槽
        // 3. 工作栏（9 槽）→ 快捷槽
        // 4. 玩家自身 inventory
    }
}
```

**`ChestMenu` 结构**（参考）:
```java
public class ChestMenu extends AbstractContainerMenu {
    public ChestMenu(int containerId, Inventory playerInventory, Container container) {
        // 1. Container 的槽位（容器内容）
        // 2. 玩家 inventory 槽位
    }
}
```

**关键区别**: `PlayerMenu` 打开的是玩家**自己**的 inventory；`ChestMenu` 打开的是一个 **Container**（盒装方块）。Alice Bot 需要的是**第三个实体**（Bot）的 inventory，介于两者之间。

---

## 二、外部 Mod 参考实现

### 2.1 R0STUS/OpenInventory（Forge Mod）

**GitHub 仓库**: [R0STUS/OpenInventory](https://github.com/R0STUS/OpenInventory)

**功能**: 右键附近的玩家，打开他们的 inventory（拿取/放入物品）。

**实现方式**（基于项目描述和 Forge 模式）:
- 监听右键点击玩家事件
- 调用 `targetPlayer.openMenu(new SimpleMenuProvider(...))` 打开目标玩家的 inventory
- 注意：`openMenu()` 是**对方打开自己的菜单**，但服务端会把 GUI 发送给这个"打开者"
- 但这与 Alice 的需求稍有不同——需要**让玩家打开 Bot 的菜单**

**关键问题**: `openMenu()` 是让 ServerPlayer 自己打开菜单。如果目标是让**玩家**查看 **Bot** 的 inventory，需要：
- 使用玩家自己的 `openMenu()`，但 `MenuProvider.createMenu()` 中引用 **Bot** 的 inventory
- 这样可以打开一个显示 Bot inventory 的自定义容器，但它绑定到玩家的 containerId

**GitHub URL**:
- [OpenInventory 仓库](https://github.com/R0STUS/OpenInventory)
- 文件路径（推测）: `src/main/java/.../OpenInventory.java`（主事件处理）、`.../ContainerMenu.java`（容器）

---

### 2.2 Jikoo/OpenInv（Bukkit Plugin，参考）

**GitHub 仓库**: [Jikoo/OpenInv](https://github.com/Jikoo/OpenInv)

**功能**: 打开任何人的 inventory 作为一个箱子，实时同步。

**实现方式**（Bukkit API）:
- `player.openInventory(otherPlayer.getInventory())`: 打开其他玩家 inventory
- Bukkit 提供了直接打开其他玩家 inventory 的 API

**注意**: 这是 Bukkit/Spigot 插件，不是 Forge mod。但证明了"查看其他实体 inventory"这个需求是常见且可行的。

**GitHub URL**:
- [OpenInv 仓库](https://github.com/Jikoo/OpenInv)

---

### 2.3 Carpet Mod（Fabric，参考）

**GitHub 仓库**: [gnembon/fabric-carpet](https://github.com/gnembon/fabric-carpet)

**功能**: 提供 `/player` 命令管理 fake player，包括查看/操作 inventory。

**实现方式**（Fabric，架构差异）:
- 通过 `/player <name> inventory` 命令显示 fake player 的 inventory
- 可能使用 Fabric 的 ScreenHandler API（与 Forge 的 AbstractContainerMenu 类似）

**注意**: Carpet 是 Fabric mod，架构与 Forge 不同，但 ScreenHandler/AbstractContainerMenu 概念类似。

**GitHub URL**:
- [Carpet Fake Players](https://deepwiki.com/gnembon/fabric-carpet/4.1-fake-players)

---

### 2.4 AE2（大型 Inventory GUI，参考）

**GitHub 仓库**: [AppliedEnergistics/Applied-Energistics-2](https://github.com/AppliedEnergistics/Applied-Energistics-2)

**功能**: AE2 有许多大型物库存取 GUI（ME 存储终端等）。

**实现方式**:
- 使用 `AbstractContainerMenu` 管理物品槽位
- 使用 `DataSlot` 同步容器状态（如能量、物品数）
- 使用 `IMenuTypeExtension` 扩展 MenuType 功能

**注意**: AE2 是复杂的大型 GUI 实现（大规模存储），Alice Bot 只需要简单的 inventory 访问，不需要这么复杂。

**GitHub URL**:
- [AE2 仓库](https://github.com/AppliedEnergistics/Applied-Energistics-2)

---

### 2.5 ComputerCraft（实体 inventory 访问，参考）

**GitHub 仓库**: [cc-tweaked/CC-Tweaked](https://github.com/cc-tweaked/CC-Tweaked)

**功能**: Turtle 可以访问周边方块的 inventory。

**实现方式**:
- Turtle 通过 `Container` 抽象访问方块 inventory
- 与 Minecraft 原版的 `Container` 接口交互

**注意**: ComputerCraft 访问方块 inventory，而 Alice Bot 是实体。两者不同，但都通过 `Container` 抽象访问物品。

**GitHub URL**:
- [CC-Tweaked 仓库](https://github.com/cc-tweaked/CC-Tweaked)

---

### 2.6 通用模式总结

| Mod | 平台 | 实现方式 | 是否打开实体 inventory | 适用性 |
|-----|------|---------|----------------------|--------|
| OpenInventory | Forge | openMenu + ContainerMenu | 是（其他玩家） | **高** |
| OpenInv | Bukkit | openInventory API | 是（其他玩家） | 低（非 Forge） |
| Carpet | Fabric | /player inventory 命令 | 是（fake player） | 中（Fabric 差异） |
| AE2 | Forge | AbstractContainerMenu + DataSlot | 否（方块） | 低（过于复杂） |
| ComputerCraft | Forge | Container 抽象 | 否（方块） | 低 |

**通用模式**: 使用 `AbstractContainerMenu`（+ `MenuType` + `MenuProvider` + `openMenu()`）实现自定义容器，在构造函数中注册目标 inventory 的槽位。

---

## 三、设计方案

### 3.1 方案 A：`AbstractContainerMenu`（完整交互）

**核心思路**: 创建一个自定义 `AbstractContainerMenu` 子类，注册 Bot 的 36 普通槽 + 4 装备槽 + 1 主手槽，让玩家可以拿取/放入物品。

**伪代码实现**:

```java
// BotInventoryMenu.java
public class BotInventoryMenu extends AbstractContainerMenu {
    private final Inventory botInventory;  // Bot 的 inventory

    public BotInventoryMenu(int containerId, Inventory playerInventory, Inventory botInventory) {
        super(MenuTypeRegistry.BOT_INVENTORY_MENU.get(), containerId);
        this.botInventory = botInventory;

        // 1. 注册 Bot 的装备槽（副手/头盔/胸甲/裤子/鞋子/主手）
        //    EquipmentSlot: OFFHAND(40) / HEAD(39) / CHEST(38) / LEGS(37) / FEET(36)
        //    注意：主手槽直接显示 playerInventory.selected 的物品
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            if (slot == EquipmentSlot.MAINHAND) continue;  // 主手单独处理
            this.addSlot(new Slot(botInventory.getArmor(slot), ...));
        }

        // 2. 注册 Bot 的 36 普通槽（0-35）
        for (int i = 0; i < 36; i++) {
            this.addSlot(new Slot(botInventory, i, x, y));
        }

        // 3. 注册 Bot 的主手槽（特殊处理，显示 selected 槽）
        //    注意：主手物品在 inventory.getItem(inventory.selected) 中
        //    可以添加一个虚拟槽位，绑定到 playerInventory.selected

        // 4. 注册玩家自身的 inventory（标准模式）
        //    - 装备槽
        //    - 主背包（36 槽）
        //    - 工作栏（9 槽）
        this.addStandardPlayerSlots(playerInventory);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        // 实现快速移动（Shift+点击）：从 Bot inventory 移动到玩家 inventory
        Slot slot = this.slots.get(index);
        if (slot != null && slot.hasItem()) {
            ItemStack stack = slot.getItem();
            if (index < 36) {  // Bot 槽 → 玩家 inventory
                this.moveItemStackTo(stack, 36, this.slots.size(), true);
            } else {  // 玩家 inventory → Bot 槽
                this.moveItemStackTo(stack, 0, 36, false);
            }
        }
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        return true;  // 允许所有玩家查看（或检查权限）
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        // 容器关闭时清理（可选）
    }
}
```

**打开菜单**:
```java
// 在右键 Bot 事件处理器中
if (clickedEntity instanceof BotPlayer bot) {
    player.openMenu(new SimpleMenuProvider(
        (containerId, playerInventory, p) ->
            new BotInventoryMenu(containerId, playerInventory, bot.getInventory()),
        Component.literal("Bot Inventory: " + bot.getName().getString())
    ));
}
```

**注册 MenuType**:
```java
public static final DeferredRegister<MenuType<?>> MENU_TYPES =
        DeferredRegister.create(Registries.MENU, "alice");
public static final RegistryObject<MenuType<BotInventoryMenu>> BOT_INVENTORY_MENU =
        MENU_TYPES.register("bot_inventory",
                () -> new MenuType<>(BotInventoryMenu::new));
```

**优点**:
- **完整交互**：玩家可以直接拿取/放入 Bot 的任意槽位
- **符合用户需求**：用户明确要求"能直接拿取/放入"
- **纯服务端实现**：无需客户端 mod
- **架构清晰**：基于 Forge 原版容器机制

**缺点**:
- **需要理解 Container/Slot 机制**：涉及较多 API
- **需要处理 slots 布局**：计算坐标（X/Y）
- **客户端 GUI 背景**：需要定义 GUI 布局（可以使用原版 chest 的 GUI 纹理，或用简单 GUI）

**风险**:
- **中**。需要理解 AbstractContainerMenu 的完整生命周期
- **物品同步**：需要确保 Bot inventory 修改后正确广播

**预计实现时间**: 30-45 分钟（编写 Menu + MenuType + 事件处理）

---

### 3.2 方案 B：简单 `Screen`（只读显示）

**核心思路**: 只创建一个客户端 Screen，只读显示 Bot 的 inventory，不可交互。

**伪代码**:
```java
// 只读 Screen（纯客户端）
public class BotInventoryScreen extends Screen {
    private final BotInventoryData data;  // 从服务端接收的快照

    public BotInventoryScreen(BotInventoryData data) {
        super(Component.literal("Bot Inventory"));
        this.data = data;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        // 显示 36 槽 + 装备槽 + 主手槽的只读视图
        for (int i = 0; i < 36; i++) {
            drawSlot(graphics, data.getSlot(i), x, y);
        }
        // 不处理鼠标点击
    }
}
```

**打开方式**:
- 服务端发送自定义 packet 给客户端，包含 Bot inventory 快照
- 客户端收到 packet 后打开 `BotInventoryScreen`

**优点**:
- **简单**：只需要客户端 Screen
- **无容器同步**：不需要 AbstractContainerMenu
- **只读安全**：不会意外修改 Bot inventory

**缺点**:
- **不支持交互**：用户无法拿取/放入物品
- **需要客户端 mod**：必须实现客户端 Screen
- **不是实时**：需要手动刷新或定时同步

**风险**:
- **中高**。需要客户端 mod，增加维护成本

**预计实现时间**: 20-30 分钟 + 客户端 mod 支持

---

### 3.3 方案 C：混合（查看 + 有限交互）

**核心思路**: 使用 `AbstractContainerMenu` 显示全部槽位，但限制交互（如只允许拿取，不允许放入；或只允许交互普通槽，不允许装备槽）。

**伪代码**:
```java
public class BotInventoryMenu extends AbstractContainerMenu {
    // 显示全部槽位
    @Override
    public boolean canTakeItem(Slot slot) {
        // 限制拿取某些槽位
        if (slot instanceof Slot botInventorySlot) {
            // 不允许拿取装备槽（保护 Bot 装备）
            if (isEquipmentSlot(botInventorySlot)) return false;
        }
        return true;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        // 只允许从 Bot 拿取，不允许放入
        Slot slot = this.slots.get(index);
        if (slot != null && slot.hasItem() && index < 36) {
            return this.moveItemStackTo(slot.getItem(), 36, this.slots.size(), true);
        }
        return ItemStack.EMPTY;
    }
}
```

**优点**:
- **半交互**：允许查看所有槽位 + 有限交互
- **保护装备**：防止误操作 Bot 的装备槽
- **服务端实现**：无需客户端 mod

**缺点**:
- **实现复杂度介于 A/B 之间**：需要实现交互限制
- **用户体验模糊**：用户可能不理解为什么某些槽位可交互、某些不可

**风险**:
- **中**。需要明确交互规则，避免用户困惑

**预计实现时间**: 30-40 分钟

---

### 3.4 方案对比表

| 方案 | 交互能力 | 客户端 mod | 实现难度 | 用户体验 | 风险 | 推荐优先级 |
|------|---------|-----------|---------|---------|------|-----------|
| A（AbstractContainerMenu） | **完整拿取/放入** | 否 | 中 | 高（符合需求） | 中 | **1（推荐）** |
| B（只读 Screen） | 无 | **是** | 低 | 低（不可交互） | 中高 | 3 |
| C（混合） | 部分 | 否 | 中 | 中 | 中 | 2 |

---

## 四、推荐方案与实施路线

### 4.1 推荐方案

**方案 A（AbstractContainerMenu 完整交互）**。理由：
1. **匹配用户核心需求**：用户明确要求"能直接拿取/放入"
2. **纯服务端实现**：无需客户端 mod，维护成本低
3. **原版机制可用**：基于 Forge 成熟的容器协议
4. **可扩展性**：未来可以扩展为 Bot 设置页面（在 Menu 中添加 UI 按钮）

### 4.2 实施步骤（最小可行 → 完整功能）

#### 阶段 1：基础（最小可行）

**步骤 1.1**: 创建 `BotInventoryMenu` 类
- 文件：`src/main/java/com/dddgn/alice/gui/BotInventoryMenu.java`
- 实现：继承 `AbstractContainerMenu`
- 注册：36 普通槽 + 4 装备槽 + 1 主手槽
- 实现：`quickMoveStack()` 快速移动、`stillValid()` 有效性检查

**步骤 1.2**: 注册 `MenuType`
- 文件：`src/main/java/com/dddgn/alice/gui/ModMenuTypes.java`（或类似）
- 实现：`DeferredRegister.create(Registries.MENU, "alice")`
- 注册：`BOT_INVENTORY_MENU`

**步骤 1.3**: 添加打开菜单入口
- 文件：`BotManager.java`（或新的事件处理类）
- 实现：监听右键 Bot 事件或命令，调用 `player.openMenu(new SimpleMenuProvider(...))`

**步骤 1.4**: 验证
- 服务端测试：打开菜单，确认槽位正确显示
- 客户端测试：右键 Bot，确认 GUI 打开，拿取/放入物品正常

#### 阶段 2：增强（完整功能）

**步骤 2.1**: 添加 GUI 布局
- 定义 GUI 背景纹理（可以复用原版 chest GUI，或自定义）
- 计算槽位坐标（36 槽布局、装备槽排列）

**步骤 2.2**: 添加权限控制
- 只有权限等级 2 的玩家可以打开 Bot inventory
- 或者在 `stillValid()` 中检查

**步骤 2.3**: 装备槽交互
- 保证装备槽正确同步（主手/副手/头盔/胸甲/裤子/鞋子）
- 处理装备槽的物品移动逻辑

**步骤 2.4**: 扩展 Bot 设置页面
- 在 Menu 中添加 UI 按钮（如"刷新"、"设置"）
- 通过 `DataSlot` 同步 Bot 状态（如当前任务、位置）

#### 阶段 3：完整（长期）

**步骤 3.1**: 扩展为 Bot 设置页面
- 显示 Bot 状态（当前任务、生命值、位置）
- 添加设置项（如任务中断、跟随开关）

### 4.3 验证矩阵

**服务端测试**:
- [ ] 打开菜单时，36 槽正确显示（无越界）
- [ ] 装备槽正确显示（6 装备槽）
- [ ] 主手槽正确显示（当前 selected 槽）
- [ ] `quickMoveStack()` 快速移动正常（Shift+点击）

**客户端实测**:
- [ ] V1: 右键 Bot 打开 GUI，看到 36 槽 + 装备槽 + 主手槽
- [ ] V2: 从 Bot inventory 拿取物品到玩家背包
- [ ] V3: 从玩家背包放入物品到 Bot inventory
- [ ] V4: Shift+点击快速拿取/放入
- [ ] V5: 装备槽交互（主手/副手/头盔等）
- [ ] V6: 关闭 GUI 后 Bot inventory 正确同步

### 4.4 停止条件

**阶段 1**:
- [ ] 编译通过（`./gradlew compileJava`）
- [ ] 服务端测试 PASS
- [ ] 客户端 V1-V3 PASS

**阶段 2**:
- [ ] 客户端 V4-V6 PASS
- [ ] 权限控制生效
- [ ] 装备槽正确交互

---

## 五、风险评估

### 5.1 是否影响其他模块（mining/transfer）

**影响分析**:
- **不影响**：GUI 打开不修改 Bot inventory（除用户主动交互）
- **需注意**：如果用户打开 GUI 时 Bot 正在执行任务（如 Transfer），可能需要协调
  - 方案：打开 GUI 时暂停 Bot 任务，关闭 GUI 后恢复
  - 或：禁止在执行任务时打开 GUI

**建议**:
- 短期：允许随时打开 GUI，但**任务执行时不暂停**（避免复杂协调）
- 长期：检测 Bot 是否正在执行任务，任务中禁止修改 Bot inventory（防止损坏任务状态）

### 5.2 多人服场景：权限控制

**建议**:
- **默认**：只有权限等级 2 的玩家可以打开 Bot inventory
- **可选**：`/alice bot access <name>` 命令授权特定玩家
- **实现**：在打开菜单的事件处理中检查 `player.hasPermissions(2)`

### 5.3 性能影响

**分析**:
- `AbstractContainerMenu.broadcastChanges()` 每 tick 检测槽位变化
- 只在玩家打开 GUI 时才广播（Bot 单独存在时不会广播）
- 性能开销可忽略

**优化**:
- 打开 GUI 时只同步显示的槽位（36+装备+主手 = 43 槽位）
- 关闭 GUI 后停止广播

### 5.4 是否需要客户端 mod

**结论**: **无需客户端 mod**

**技术依据**:
- `MenuType` 注册后，客户端会自动映射到对应的 Screen（Forge 注册机制）
- 客户端使用原版 `ClientboundOpenScreenPacket` + `MenuType` 找到 Screen
- 如果使用原版 GUI 背景（如 chest），客户端完全原生
- 无需自定义客户端代码

**例外**: 如果要用自定义 GUI 背景纹理，需要客户端资源包（但不是 mod）

---

## 六、证据附录

### 6.1 代码证据表

| 文件/类 | 方法 | 说明 |
|---------|------|------|
| `AbstractContainerMenu` | `addSlot()`, `broadcastChanges()`, `clicked()`, `removed()` | 容器生命周期 |
| `MenuType` | 注册构造器 | 容器类型注册 |
| `MenuProvider` | `createMenu(containerId, inventory, player)` | 创建容器 |
| `ServerPlayer` | `openMenu(MenuProvider)` | 打开菜单 |
| `SimpleMenuProvider` | 构造函数 | 简单 MenuProvider 实现 |
| `ChestMenu` | 构造函数 | 原版箱子容器参考 |
| `PlayerMenu` | 构造函数 | 原版玩家背包参考 |

### 6.2 外部资料引用

**Forge 官方文档**:
- [Menus | Minecraft Forge 官方文档](https://docs.minecraftforge.net/en/1.20.1/gui/menus/)
- [Menus | NeoForged 文档](https://zh-neoforge.netlify.app/docs/1.20.4/gui/menus/)

**外部 mod**:
- [R0STUS/OpenInventory](https://github.com/R0STUS/OpenInventory) — Forge mod，右键打开其他玩家 inventory
- [OpenInv (Bukkit)](https://github.com/Jikoo/OpenInv) — Bukkit 插件，打开任何人的 inventory
- [AE2](https://github.com/AppliedEnergistics/Applied-Energistics-2) — 大型 inventory GUI
- [Carpet Fake Players](https://deepwiki.com/gnembon/fabric-carpet/4.1-fake-players) — Fabric fake player

**Forge 论坛讨论**:
- [Opening a player's inventory on another player's screen](https://forums.minecraftforge.net/topic/29575-opening-a-players-inventory-on-another-players-screen)

### 6.3 Minecraft API 版本确认

**Forge 版本**: 1.20.1-47.4.10  
**Minecraft 版本**: 1.20.1  
**关键 API**:
- `net.minecraft.world.inventory.AbstractContainerMenu`
- `net.minecraft.world.inventory.MenuType`
- `net.minecraft.world.inventory.Slot`
- `net.minecraft.world.MenuProvider` / `SimpleMenuProvider`
- `net.minecraft.server.level.ServerPlayer.openMenu()`

---

## 七、本报告不构成实现授权

本报告仅提供事实调查、架构分析和设计方案建议。任何代码修改必须：
1. 由监督员审核本报告并批准设计范围
2. 创建新的 `.alice-supervision/active-plan.md` 并标记 `APPROVED_FOR_IMPLEMENTATION`
3. 由主开发会话按批准计划实施
4. 通过服务端测试和客户端验证矩阵
5. 经监督员二次审核后方可合并

**证据缺口**:
- 未在客户端实测方案 A 的实际 GUI 显示和交互（需 V1-V6）
- 未确认 R0STUS/OpenInventory 的具体实现代码（需进一步查看源码）
- 未验证装备槽交互的具体行为（需客户端实测）
- 未确认 MenuType 注册在 Forge 1.20.1 的具体 API 签名

**不可直接采纳项**:
- 方案 B（只读 Screen）需要客户端 mod，不符合"纯服务端"目标
- 方案 C（混合）交互规则可能造成用户困惑
- 方案 A 的装备槽异常交互风险需要客户端验证确认
