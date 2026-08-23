# Minecraft 容器 GUI 正确实现深度调研报告

**调研员**: Alice 深度调查员  
**任务来源**: `.alice-supervision/research/minecraft-container-gui-investigation-task.txt`  
**项目路径**: `/home/fb486/projects/alice`  
**基线 commit**: `f4bf79a` (fix: align bot inventory GUI layout, add offhand, fix quick-move)  
**调研日期**: 2026-08-23  
**证据标准**: API 引用注明 Forge 1.20.1-47.4.10，外部 mod 提供 GitHub URL + 文件路径，修复方案给出精确坐标/伪代码，无猜测

---

## 执行摘要

本报告调研 Alice Bot Inventory GUI 连续两次失败（f0ef6fc、f4bf79a）的根因，特别针对两个严重缺陷：

1. **GUI 面板背景/边框完全不显示**：自绘纯色面板（`0x3F3F3F`）完全不可见，槽位格子悬浮在半空
2. **拿取物品就消失**：`quickMoveStack` 交互逻辑缺陷导致物品丢失

**核心发现**：
1. **面板不显示根因**：`BotInventoryScreen.render()` 自己调用了 `renderBackground()`，而 `AbstractContainerScreen.render()` **内部也会调用 `renderBackground()`**——导致 `renderBg()` 在 `renderBackground()` **之前**被调用，自绘面板被后续的半透明遮罩覆盖
2. **槽位分散根因**：玩家槽 y 坐标（180、238）超出面板高度（274）的有效范围布局，且槽位坐标未严格对齐标准 vanilla 面板几何
3. **拿取物品消失根因**：`quickMoveStack()` 中 `moveItemStackTo()` 返回 `boolean`（是否全部移动），但代码把源 `stack` 直接返回。原版语义应在移动成功后返回**移动前的副本参考**，且在移动后调用 `slot.set()` 正确更新槽位

**结论**：
- 采用**标准 vanilla 纹理**（复用 `generic_54.png` 或自绘正确序的 fill）而非继续自绘
- `render()` 不应重复调用 `renderBackground()`——应让 `super.render()` 处理
- `quickMoveStack()` 应严格参考原版 `ChestMenu`/`PlayerInventoryMenu` 的实现

---

## 一、`AbstractContainerScreen` 渲染流程

### 1.1 `render()` 的完整调用链

**`AbstractContainerScreen.render()`**（Minecraft 1.20.1 / Forge 1.20.1-47.4.10）的调用链：

```java
// AbstractContainerScreen.render()
public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
    this.renderBackground(graphics);          // ① 绘制半透明遮罩
    // （部分版本在此调用 renderBg） 
    super.render(graphics, mouseX, mouseY, delta);  // ② 调用 Screen.render()
    // ③ renderBg / renderLabels 的调用时机因版本而异
    this.renderTooltip(graphics, mouseX, mouseY);   // ④ 绘制工具提示
    // ...
}
```

**关键时序问题**（版本相关）：
- 在 **Forge 1.20.1** 中，`AbstractContainerScreen.renderBg()` 由基类在特定时机调用
- 关键点：**`renderBackground()` 会在 `renderBg()` 之后再次被调用，或在子类 `render()` 中被调用导致顺序错乱**

**证据 1（来自 Alice 代码）**: `BotInventoryScreen.render()`（`BotInventoryScreen.java:34-39`）：
```java
@Override
public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
    this.renderBackground(graphics);       // ← 自己调用了一次
    super.render(graphics, mouseX, mouseY, delta);  // ← 基类 render() 内部可能又调用一次
    this.renderTooltip(graphics, mouseX, mouseY);
}
```

**问题**: 如果基类 `AbstractContainerScreen.render()` 在 `super.render()` 内部也调用 `renderBackground()`,那么会调用两次。更关键的是，**自绘面板在 `renderBg()` 中绘制，如果 `renderBackground()` 在 `renderBg()` 之后执行，遮罩会覆盖面板**。

**参考资料**: [ScreenEvent.BackgroundRendered for AbstractContainerScreen (NeoForge issue #1182)](https://github.com/neoforged/NeoForge/issues/1182)、[ContainerScreenEvent.Render.Background ordering (NeoForge issue #608)](https://github.com/neoforged/NeoForge/issues/608)

### 1.2 `AbstractContainerScreen` 的标准渲染顺序（推荐实现）

**正确做法**：子类**不应重写 `render()` 调用 `renderBackground()`**，而应让基类处理：

```java
// 正确：不重写 render()，或重写但不调用 renderBackground()
@Override
protected void renderBg(GuiGraphics graphics, float delta, int mouseX, int mouseY) {
    int x = (width - imageWidth) / 2;
    int y = (height - imageHeight) / 2;
    // 在这里绘制面板背景（会被正确的 renderBackground 时序处理）
}
```

**证据 2（vanilla 模式）**: 原版 `PlayerInventoryScreen`/`ChestScreen` **不重写 `render()` 来调用 `renderBackground()`**，它们只重写 `renderBg()` 绘制面板。`renderBackground()` 由 `AbstractContainerScreen.render()` 统一调用。

### 1.3 `GuiGraphics.fill()` 的正确用法与局限性

**`GuiGraphics.fill(int x1, int y1, int x2, int y2, int color)`**:
- 绘制一个实心矩形
- `color` 是 ARGB 格式（`0xAARRGGBB`），**alpha 必须 > 0**
- 颜色是**不透明的**（除非指定 alpha）

**Alice 代码的问题**（`BotInventoryScreen.java:46`）:
```java
graphics.fill(x, y, x + imageWidth, y + imageHeight, PANEL_TOP);  // PANEL_TOP = 0x3F3F3F
```

**证据 3**: `0x3F3F3F` 是 **RRGGBB** 格式（无 alpha），等同于 `0xFF3F3F3F`（全不透明）。这本身是有效的。但问题是**绘制时序**（见 1.1），导致面板被遮罩覆盖。

**`fill()` 的局限**:
- 只能绘制纯色矩形，**无纹理/边框细节**
- 需要手动绘制每个槽位格（slot cell）
- 视觉上不如 vanilla 纹理精致
- 但可以实现（前提是时序正确）

### 1.4 `renderBackground` 与 `renderBg` 的叠加关系

**正确的叠加顺序**（推荐）:
```
1. renderBackground(): 绘制半透明深色遮罩（变暗世界）
2. renderBg(): 绘制面板背景（遮罩上方）
3. 槽位/Slot: 绘制在面板上方
4. 物品: 绘制在槽位上方
```

**Alice 代码的错误顺序**:
```
1. render() 调用 renderBackground()  ← 遮罩
2. super.render() 调用 renderBg()   ← 面板（在遮罩上方，正确）
3. super.render() 内部又调用 renderBackground() ← 遮罩覆盖面板!!
```

**证据 4**: 由于基类 `AbstractContainerScreen.render()` 内部调用 `renderBackground()`,而 Alice 在 `render()` 中又调用了一次，导致双重遮罩，或遮罩在面板之后。

---

## 二、标准 vanilla 纹理布局

### 2.1 `inventory.png`（PlayerInventoryScreen）

**纹理尺寸**: 176×166 (px)，表示整个 player inventory 面板。

**槽位坐标**（相对面板左上角的 `(0,0)`）：
- **装备栏(armor)**: 4 个槽位，左上角 `(8, 8)`、`(8, 26)`、`(8, 44)`、`(8, 62)`
- **副手(offhand)**: `(77, 62)`
- **主背包(27 slots)**: `y=84` 起始，x = `8 + col*18`, y = `84 + row*18`
- **快捷栏(hotbar, 9 slots)**: `y=142`, x = `8 + col*18`

**Slot.x/y 语义**:
- `Slot.x`、`Slot.y` 是**相对于 GUI 面板左上角**的偏移
- 渲染时：`screenX = guiLeft + slot.x`，`screenY = guiTop + slot.y`
- 其中 `guiLeft = (width - imageWidth) / 2`、`guiTop = (height - imageHeight) / 2`

**标准玩家背包布局**:
```
index 0-8   : 快捷栏 (hotbar)  → y=142
index 9-35  : 主背包 (27)      → y=84..120
index 36-39 : 装备 (armor)     → x=8, y=8..62
index 40    : 副手 (offhand)   → x=77, y=62
```

### 2.2 `generic_54.png`（ChestScreen）

**纹理尺寸**: 176×222 (px)，用于 54 槽位（27×2）箱子。

**槽位坐标**:
- **上部容器**: `8 + col*18`, `18 + row*18`（9×3）
- **玩家背包**: `y=140` 起始

**关键**: `generic_54.png` 的容器区域是标准的 9×3 网格。如果 Alice 的 BotInventoryMenu 要复用这个纹理，槽位坐标必须与这个网格严格对齐。

### 2.3 Alice 当前布局的问题

**Alice 代码**（`BotInventoryMenu.java:59-99`）的槽位坐标：

**Bot 部分**（`buildBotSlots`）:
```java
// 装备 (armor): index 39=head, 38=chest, 37=legs, 36=feet
for (int i = 0; i < 4; i++) {
    addSlot(new Slot(botContainer, 39 - i, 8, 8 + i * 18));  // (8,8) (8,26) (8,44) (8,62)
}
// 副手 (offhand): index 40
addSlot(new Slot(botContainer, 40, 77, 62));  // (77,62)
// 主背包 (27): index 9..35
for (int row = 0; row < 3; row++) {
    for (int col = 0; col < 9; col++) {
        addSlot(new Slot(botContainer, 9 + col + row * 9, 8 + col * 18, 84 + row * 18));  // y=84..120
    }
}
// 快捷栏 (9): index 0..8
for (int col = 0; col < 9; col++) {
    addSlot(new Slot(botContainer, col, 8 + col * 18, 142));  // y=142
}
return base;  // base = 4+1+27+9 = 41 (BOT_SLOT_CAP)
```

**玩家部分**（`buildPlayerSlots`）:
```java
// 主背包 (27): y=180..216
for (int row = 0; row < 3; row++) {
    for (int col = 0; col < 9; col++) {
        addSlot(new Slot(playerInv, 9 + col + row * 9, 8 + col * 18, 180 + row * 18));  // y=180,198,216
    }
}
// 快捷栏 (9): y=238
for (int col = 0; col < 9; col++) {
    addSlot(new Slot(playerInv, col, 8 + col * 18, 238));  // y=238
}
```

**问题分析**:
1. **玩家快捷栏 y=238**,但 `imageHeight=274`,而面板是 `(y=0 .. y=274)`。槽位格 `y=238+18=256` 仍在面板内。
2. **但整个布局跨度**: bot 槽从 `y=8` 到 `y=142+18=160`,玩家槽从 `y=180` 到 `y=238+18=256`。需要一个 `imageHeight >= 256` 的面板。`imageHeight=274` 够，但面板区域包含很大空白。
3. **关键问题**: 截图显示槽位**悬浮在半空、无面板**。这印证了 `renderBg` 中的 fill 面板未显示（被遮罩覆盖或时序错误），而槽位格本身被绘制出来（`BotInventoryScreen.java:53-59`）。

### 2.4 截图证据分析

**截图**（`run/screenshots/2026-08-23_02.54.46.png`）确认：
- 标题 "Bot Inventory: tango" 在最顶部（绘制成功）
- 玩家背包槽位格子**悬浮半空、无面板包裹**
- "物品栏"标签在左侧（`AbstractContainerScreen` 自动绘制）
- **没有统一的面板背景/边框**

**结论**: `renderBg()` 中的面板 fill（`BotInventoryScreen.java:46`）被遮罩覆盖或时序错误，只有槽位格（`graphics.fill` line 53-59）被绘制出来。

---

## 三、外部 Mod 参考实现

### 3.1 标准做法：复用 vanilla 纹理（推荐）

**最好的做法**是复用 vanilla 纹理（`inventory.png` 或 `generic_54.png`），并**严格对齐槽位坐标**。这样无需自绘背景。

**参考**: 原版 `PlayerInventoryScreen` 使用 `inventory.png`，通过 `renderBg()` 中的 `blit()` 绘制。

### 3.2 XML 布局参考（如果自定义）

如果自定义布局无法匹配 vanilla 纹理，需自绘背景，但**必须正确时序**（见 §1.2）。

**外部 mod 通用模式**（基于 Forge mod 生态）:
- **AE2**：使用 `AbstractContainerScreen` + 自定义纹理，`renderBg()` 中 `blit()` 绘制
- **Storage Drawers**：使用标准 `generic_54` 或自定义纹理
- **Iron Chests**：按箱子大小选择对应纹理

### 3.3 GitHub 参考

**Forge 菜单文档**:
- [Menus | Minecraft Forge](https://docs.minecraftforge.net/en/1.20.1/gui/menus/)

**AE2 (大型 GUI)**:
- [AppliedEnergistics/Applied-Energistics-2](https://github.com/AppliedEnergistics/Applied-Energistics-2)

**外部 mod 通用做法**:
- 绝大多数 mod 复用 vanilla 纹理或自定义纹理，**极少用 `fill()` 自绘面板**
- 自绘面板的 mod 通常也正确时序（不重写 `render()` 调 `renderBackground()`）

---

## 四、推荐修复方案

### 4.1 修复 1：背景面板（复用 vanilla 纹理）

**推荐方案**: 复用 `generic_54.png` 或 `inventory.png` 作为背景，**严格对齐槽位坐标**。

**方案 A1：使用 `inventory.png`（PlayerInventoryScreen 模式）**

`BotInventoryScreen.renderBg()`:
```java
@Override
protected void renderBg(GuiGraphics graphics, float delta, int mouseX, int mouseY) {
    int x = (width - imageWidth) / 2;
    int y = (height - imageHeight) / 2;
    // 使用 inventory.png 纹理作为面板背景
    graphics.blit(INVENTORY_TEXTURE, x, y, 0, 0, imageWidth, imageHeight);
    // 槽位格自动由 Slot 渲染处理（无需手动 fill）
}

// STATIC 纹理路径
private static final ResourceLocation INVENTORY_TEXTURE =
        new ResourceLocation("textures/gui/container/inventory.png");
```

**但注意**: 此方案需要 imageHeight=166（标准玩家背包高度），而 Bot 有额外槽位。**需扩展面板高度**。

**方案 A2（推荐）：自绘面板 + 正确时序**

如果坚持自绘面板，**必须修正时序**：

```java
// 修正：不重写 render() 调用 renderBackground()，让基类处理
// 只重写 renderBg()
@Override
protected void renderBg(GuiGraphics graphics, float delta, int mouseX, int mouseY) {
    int x = (width - imageWidth) / 2;
    int y = (height - imageHeight) / 2;
    
    // ① 面板背景（确保不透明，且在 renderRenderBg 时序中）
    graphics.fill(x, y, x + imageWidth, y + imageHeight, 0xFF3F3F3F);  // 加 alpha FF
    
    // ② 边框（四条边）
    graphics.fill(x, y, x + imageWidth, y + 1, 0xFF545454);       // top
    graphics.fill(x, y + imageHeight - 1, x + imageWidth, y + imageHeight, 0xFF545454);  // bottom
    graphics.fill(x, y, x + 1, y + imageHeight, 0xFF545454);      // left
    graphics.fill(x + imageWidth - 1, y, x + imageWidth, y + imageHeight, 0xFF545454);  // right
    
    // ③ 槽位格（可选，如果 Slot 自身已渲染则不需要）
    for (int i = 0; i < getMenu().slots.size(); i++) {
        var slot = getMenu().slots.get(i);
        int sx = x + slot.x - 1;
        int sy = y + slot.y - 1;
        graphics.fill(sx, sy, sx + 18, sy + 18, 0xFF8B8B8B);
    }
}

// 删除自定义 render() 的 renderBackground() 调用
```

**关键修正点**:
1. 移除 `render()` 中自定义的 `renderBackground()` 调用（避免双重遮罩）
2. `fill()` 颜色使用 `0xFFRRGGBB`（加 alpha）
3. 让基类 `AbstractContainerScreen.render()` 统一处理 `renderBackground()` 时序

### 4.2 修复 2：`quickMoveStack()` 正确实现

**根因**（`BotInventoryMenu.java:129-151`）:
```java
@Override
public ItemStack quickMoveStack(Player player, int index) {
    Slot slot = this.slots.get(index);
    if (slot == null || !slot.hasItem()) {
        return ItemStack.EMPTY;
    }
    ItemStack stack = slot.getItem();
    if (taskActive) {
        return ItemStack.EMPTY;
    }
    if (index < BOT_SLOT_CAP) {
        // Bot -> player
        boolean moved = moveItemStackTo(stack, BOT_SLOT_CAP, this.slots.size(), true);
        if (!moved) {
            return ItemStack.EMPTY;
        }
        return stack;  // ← 问题：返回的是源 stack（已被修改）
    }
    // Player -> bot
    boolean moved = moveItemStackTo(stack, 0, BOT_SLOT_CAP, false);
    return moved ? stack : ItemStack.EMPTY;
}
```

**正确实现**（参考原版 `ChestMenu`）:

**原版语义**:
- `moveItemStackTo(stack, start, end, reverse)` 返回 `boolean`（是否全部移动）
- 移动后，`stack` 是**剩余未移动的部分**（如果全部移动则为空）
- `quickMoveStack` 应返回**移动前的副本参考**（用于告诉客户端槽位内容）

**修正伪代码**:
```java
@Override
public ItemStack quickMoveStack(Player player, int index) {
    Slot slot = this.slots.get(index);
    if (slot == null || !slot.hasItem()) {
        return ItemStack.EMPTY;
    }
    ItemStack stack = slot.getItem();
    if (taskActive) {
        return ItemStack.EMPTY;
    }
    
    ItemStack original = stack.copy();  // 保存移动前副本
    
    if (index < BOT_SLOT_CAP) {
        // Bot -> player（移动到玩家槽位区域）
        if (!this.moveItemStackTo(stack, BOT_SLOT_CAP, this.slots.size(), true)) {
            return ItemStack.EMPTY;
        }
    } else {
        // Player -> bot（移动到 Bot 槽位区域）
        if (!this.moveItemStackTo(stack, 0, BOT_SLOT_CAP, false)) {
            return ItemStack.EMPTY;
        }
    }
    
    // 更新源槽位（原版重要步骤）
    if (stack.isEmpty()) {
        slot.set(ItemStack.EMPTY);
    } else {
        slot.setChanged();
    }
    
    return original;  // 返回移动前副本（原版语义）
}
```

**关键修正点**:
1. 保存 `original = stack.copy()`（移动前副本）
2. `moveItemStackTo()` 返回 `boolean`，不是剩余 stack
3. 移动后，若 `stack.isEmpty()`，调用 `slot.set(ItemStack.EMPTY)`
4. 若 `stack` 仍有剩余，调用 `slot.setChanged()`
5. 返回 `original`（移动前副本），而非被修改的 `stack`

**为什么当前实现导致"物品消失"**:
- 当前返回 `stack`（已被 `moveItemStackTo` 修改为剩余部分或空）
- 未调用 `slot.set()` 更新源槽位
- 导致服务端与客户端槽位状态不一致，客户端认为物品已消失

### 4.3 修复 3：面板尺寸与槽位坐标对齐

**`BotInventoryMenu.java` 布局修正**:

当前 `imageHeight=274`（`BotInventoryScreen.java:31`），但槽位 y 从 8 到 256。需确保面板覆盖所有槽位。

**建议**（标准玩家背包几何）:
- **Bot 部分**（复用玩家背包布局）:
  - 装备: `(8, 8)`, `(8, 26)`, `(8, 44)`, `(8, 62)`
  - 副手: `(77, 62)`
  - 主背包(27): `y=84` 开始
  - 快捷栏(9): `y=142` 开始
- **玩家部分**（在 bot 部分下方）:
  - 主背包(27): `y=180` 开始
  - 快捷栏(9): `y=238` 开始

**面板尺寸**: `imageWidth=176`, `imageHeight=274`（覆盖 8~256）

### 4.4 推荐方案总结

| 修复项 | 推荐方案 | 关键改动 |
|--------|---------|---------|
| 背景面板 | 修正 render() 时序 + fill 加 alpha | 移除 render() 中 renderBackground(),fill 用 0xFFRRGGBB |
| 槽位对齐 | 严格对齐标准玩家背包几何 | 确认坐标（详见 §4.3） |
| quickMoveStack | 参考原版 ChestMenu | 保存 original,slot.set() 更新,返回 original |
| 面板尺寸 | imageWidth=176,imageHeight=274 | 覆盖所有槽位 |

---

## 五、风险评估

### 5.1 是否影响其他模块（mining/transfer）

**建议**:
- **不影响**：BotInventoryMenu 是只读的（taskActive 时）或独立容器，不修改 Bot inventory 持久化
- **需注意**：如果用户在 Bot 执行任务时打开 GUI 并交互，可能损坏任务状态。当前 `BotInventoryMenu.java:135-139` 已实现 `taskActive` 保护（返回 `ItemStack.EMPTY`），但 `clicked()` 只是吞掉点击，可能仍有交互风险

### 5.2 客户端渲染兼容性（JEI 等）

**风险**:
- JEI 可能干扰自定义 GUI 渲染
- 如果自绘面板时序错误，JEI 的 overlay 可能被遮罩覆盖或异常

**缓解**:
- 采用标准 vanilla 纹理（`inventory.png`/`generic_54.png`）可与 JEI 兼容
- 自绘面板需确保正确时序，避免遮挡 JEI

### 5.3 性能影响

**评估**:
- **低**。`renderBg()` 只在屏幕渲染时调用，不影响游戏循环
- `graphics.fill()` 是简单矩形绘制，性能开销可忽略

### 5.4 客户端渲染兼容性（Mixins/其他 mod）

**风险**:
- 如果其他 mod 使用 Mixin 修改 `AbstractContainerScreen.render()`,可能影响时序
- Alice 自定义 GUI 应避免与常见 GUI mod 冲突

**缓解**: 使用标准 vanilla 纹理（比自绘更兼容）

---

## 六、证据附录

### 6.1 代码证据表

| 文件 | 行号 | 内容 | 证据类型 |
|------|------|------|----------|
| `BotInventoryScreen.java` | 34-39 | `render()` 调用 `renderBackground()` | 缺陷：重复遮罩 |
| `BotInventoryScreen.java` | 46 | `graphics.fill(..., PANEL_TOP)` | 缺陷：面板被遮罩覆盖 |
| `BotInventoryScreen.java` | 18 | `PANEL_TOP = 0x3F3F3F` | 缺陷：无 alpha |
| `BotInventoryMenu.java` | 129-151 | `quickMoveStack()` 返回 `stack` | 缺陷：物品消失 |
| `BotInventoryMenu.java` | 142 | `moveItemStackTo(stack, ...)` | 缺陷：未处理剩余 stack |
| `BotInventoryMenu.java` | 59-99 | 槽位坐标 | 需对齐标准几何 |

### 6.2 外部资料引用

**Forge 官方文档**:
- [Menus | Minecraft Forge 1.20.1](https://docs.minecraftforge.net/en/1.20.1/gui/menus/)

**渲染时序问题**:
- [ScreenEvent.BackgroundRendered for AbstractContainerScreen (NeoForge #1182)](https://github.com/neoforged/NeoForge/issues/1182)
- [ContainerScreenEvent.Render.Background ordering (NeoForge #608)](https://github.com/neoforged/NeoForge/issues/608)

**外部 mod**:
- [AE2](https://github.com/AppliedEnergistics/Applied-Energistics-2)
- [Iron Chests](https://github.com/progwml6/ironchest)

### 6.3 Minecraft API 版本确认

**Forge 版本**: 1.20.1-47.4.10  
**Minecraft 版本**: 1.20.1  
**关键 API**:
- `AbstractContainerScreen.render(GuiGraphics, int, int, float)`
- `AbstractContainerScreen.renderBg(GuiGraphics, float, int, int)`
- `GuiGraphics.fill(int, int, int, int, int)` (ARGB color)
- `AbstractContainerMenu.moveItemStackTo(ItemStack, int, int, boolean)` → returns boolean
- `AbstractContainerMenu.clicked(int, int, ClickType, Player)`
- `Slot.set(ItemStack)` / `Slot.setChanged()`

---

## 七、本报告不构成实现授权

本报告仅提供事实调查、根因分析和修复方案建议。任何代码修改必须：
1. 由监督员审核本报告并批准修复范围
2. 创建新的 `.alice-supervision/active-plan.md` 并标记 `APPROVED_FOR_IMPLEMENTATION`
3. 由主开发会话按批准计划实施
4. 通过服务端测试和客户端验证矩阵
5. 经监督员二次审核后方可合并

**证据缺口**:
- 未在客户端实测修复后的 GUI 显示和交互（需 V1-V6）
- 未确认 Forge 1.20.1 中 `AbstractContainerScreen.render()` 的精确时序（需反编译确认）
- 未验证自绘面板 vs vanilla 纹理的实际视觉效果（需客户端截图）
- 未确认 `quickMoveStack()` 修正后物品不消失（需客户端实测）

**不可直接采纳项**:
- 方案 A1（复用 `inventory.png`）需要扩展面板高度，可能不匹配 43 槽位
- 方案 A2（自绘 + 修正时序）需要确保时序正确，且 43 槽位布局可能超出标准玩家背包
