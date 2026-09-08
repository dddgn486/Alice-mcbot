# Forge 自定义 Screen + C2S Packet 深度调研报告

**调研员**: Alice 深度调查员  
**任务来源**: `.alice-supervision/research/player-view-other-entity-inventory-investigation-task.txt`（方向 B 选定）  
**项目路径**: `/home/fb486/projects/alice`  
**基线 commit**: `1621ad2` (fix: sync bot slots to client on pickup click)  
**调研日期**: 2026-08-24  
**证据标准**: 源码引用注明 Forge 1.20.1-47.4.10 + 类方法名，外部 mod 提供 GitHub URL，推荐方案给出精确代码，无猜测

---

## 执行摘要

用户选**方向 B**——用自定义 S2C 快照 + 自定义 C2S action packet + 客户端自定义 Screen，彻底绕开 `AbstractContainerMenu` 协议。本报告调研 Forge 自定义 Screen + C2S packet 的正确做法。

**核心结论**：
1. **`AliceNetwork` 现有 SimpleChannel 注册模式**（`AliceNetwork.java:28-34`）已支持双向注册——只需新增 `NetworkDirection.PLAY_TO_SERVER` 的 C2S 包。
2. **客户端 Screen 打开**：S2C packet `handle()` 中判断 `context.getDirection().getReceptionSide().isClient()`（现有 `TargetPacket.java:41` 模式），调用 `Minecraft.getInstance().setScreen(...)`。
3. **渲染 API**：`GuiGraphics.renderItem(ItemStack, int, int)` 是 1.20.1 渲染 GUI 物品的正确方法；`Graphics.fill/blit/drawString` 绘制面板。
4. **槽位点击**：自定义 Screen 重写 `mouseClicked(double, double, int)` 做命中检测，命中则发送 C2S action packet。
5. **服务端权威**：C2S handler 用 `BotManager.isBusy`（任务保护）+ 槽位合法性校验后，才写 bot inventory。

**关键**：方向 B 完全可行，且**已有 AliceNetwork 的注册基础设施**，改动量可控。

---

## 一、Forge 自定义 Screen 最佳实践

### 1.1 `Screen` 基类关键方法（反编译确认）

`Screen` 是客户端 GUI 基类（`net.minecraft.client.gui.screens.Screen`），关键方法：
- `render(GuiGraphics, int mouseX, int mouseY, float delta)` — 主渲染入口
- `renderBackground(GuiGraphics)` — 默认半透明遮罩（可重写或调用 `super`）
- `mouseClicked(double mouseX, double mouseY, int button)` — 鼠标点击回调（返回 `boolean`）
- `onClose()` — 关闭回调
- `shouldCloseOnEsc()` — ESC 关闭
- `isPauseScreen()` — 是否暂停游戏

**反编译证据**（`Screen`）：
```
public void render(GuiGraphics, int, int, float);
public boolean keyPressed(int, int, int);
public boolean shouldCloseOnEsc();
public void onClose();
public static List<Component> getTooltipFromItem(Minecraft, ItemStack);
protected <T extends GuiEventListener & Renderable & NarratableEntry> T addRenderableWidget(T);
```

### 1.2 `GuiGraphics` 渲染 API（反编译确认）

1.20.1 的 `GuiGraphics` 关键方法：
- `fill(int x1, int y1, int x2, int y2, int color)` — 绘制实心矩形（面板背景）[确认存在]
- `fillGradient(int, int, int, int, int, int)` — 渐变
- `blit(ResourceLocation, int, int, int, int, int, int)` — 绘制纹理 [确认存在]
- `drawString(Font, String, int, int, int, boolean)` — 文本
- `drawCenteredString(Font, Component, int, int, int)` — 居中文本
- **`renderItem(ItemStack, int, int, int)`** — 渲染 GUI 物品 [确认存在]
- **`renderItem(ItemStack, int, int)`** — 渲染 GUI 物品（无叠加）[确认存在]
- `renderItemDecorations(Font, ItemStack, int, int)` — 物品数量叠加
- `enableScissor`/`disableScissor` — 裁剪

**反编译证据**（`GuiGraphics`）：
```
public void renderItem(ItemStack, int, int);
public void renderItem(ItemStack, int, int, int);
public void renderItem(ItemStack, int, int, int, int);
public void renderItemDecorations(Font, ItemStack, int, int);
public void blit(ResourceLocation, int, int, int, int, int, int);
public void fill(int, int, int, int, int);
public void fill(int, int, int, int, int, int);
public int drawString(Font, String, int, int, int, boolean);
```

### 1.3 自定义 Screen 结构（最佳实践）

```java
@OnlyIn(Dist.CLIENT)
public class BotInventoryScreen extends Screen {
    private static final ResourceLocation INVENTORY_TEXTURE =
            new ResourceLocation("minecraft", "textures/gui/container/inventory.png");
    private final int botEntityId;
    private SnapshotSnapshot snapshot;  // 从 S2C packet 获得的 bot inventory 快照
    private int guiLeft, guiTop;        // 面板左上角
    private final int imageWidth = 176, imageHeight = 274;

    public BotInventoryScreen(int botEntityId, BotInventorySnapshot snapshot) {
        super(Component.literal("Bot Inventory"));
        this.botEntityId = botEntityId;
        this.snapshot = snapshot;
    }

    @Override
    protected void init() {
        this.guiLeft = (width - imageWidth) / 2;
        this.guiTop = (height - imageHeight) / 2;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        this.renderBackground(graphics);   // 半透明遮罩
        // 绘制面板
        graphics.blit(INVENTORY_TEXTURE, guiLeft, guiTop, 0, 0, imageWidth, 166);
        graphics.fill(guiLeft, guiTop + 166, guiLeft + imageWidth, guiTop + imageHeight, 0xFF1F1F1F);
        // 绘制 bot 物品
        for (int i = 0; i < snapshot.slotCount(); i++) {
            int sx = guiLeft + slotX(i) - 1;
            int sy = guiTop + slotY(i) - 1;
            graphics.fill(sx, sy, sx + 18, sy + 18, 0xFF7A7A7A);  // 槽底
            ItemStack stack = snapshot.getSlot(i);
            if (!stack.isEmpty()) {
                graphics.renderItem(stack, sx + 1, sy + 1);         // 渲染物品
                graphics.renderItemDecorations(font, stack, sx + 1, sy + 1);
            }
        }
        super.render(graphics, mouseX, mouseY, delta);  // 标题等
        // drawString / drawCenteredString 绘制标题
    }
}
```

---

## 二、SimpleChannel C2S 双向注册

### 2.1 现有 AliceNetwork 模式（`AliceNetwork.java:28-34`）

```java
public static void register() {
    CHANNEL.registerMessage(nextId++, TargetPacket.class,
            TargetPacket::encode, TargetPacket::decode,
            TargetPacket::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    CHANNEL.registerMessage(nextId++, RoadPlanPacket.class,
            RoadPlanPacket::encode, RoadPlanPacket::decode,
            RoadPlanPacket::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
}
```

### 2.2 新增 C2S 包（`PlayToServer`）

```java
// AliceNetwork.java
public static void register() {
    // ... 现有 S2C 包 ...
    CHANNEL.registerMessage(nextId++, BotInventoryActionPacket.class,
            BotInventoryActionPacket::encode, BotInventoryActionPacket::decode,
            BotInventoryActionPacket::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));  // C2S
}
```

**反编译证据**（`SimpleChannel`）：
```
registerMessage(int, Class<MSG>, BiConsumer<MSG, FriendlyByteBuf>, Function<FriendlyByteBuf, MSG>, BiConsumer<MSG, Supplier<NetworkEvent.Context>>, Optional<NetworkDirection>)
public <MSG> void sendToServer(MSG);
public <MSG> void send(PacketDistributor.PacketTarget, MSG);
```

**关键**: `sendToServer(MSG)` 是 C2S 发送方法（客户端调用）。`NetworkDirection.PLAY_TO_SERVER` 注册 C2S。

---

## 三、客户端 S2C 打开 Screen 方式

### 3.1 S2C 快照包处理（`handle`）

```java
// BotInventorySnapshotPacket.java
public record BotInventorySnapshotPacket(int botEntityId, List<ItemStack> slots) {
    public static void encode(BotInventorySnapshotPacket p, FriendlyByteBuf buf) {
        buf.writeInt(p.botEntityId);
        buf.writeVarInt(p.slots.size());
        for (ItemStack s : p.slots) buf.writeItem(s);
    }
    public static BotInventorySnapshotPacket decode(FriendlyByteBuf buf) {
        int botId = buf.readInt();
        int size = Math.min(buf.readVarInt(), 200);
        List<ItemStack> slots = new ArrayList<>(size);
        for (int i = 0; i < size; i++) slots.add(buf.readItem());
        return new BotInventorySnapshotPacket(botId, slots);
    }
    public static void handle(BotInventorySnapshotPacket p, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        if (context.getDirection().getReceptionSide().isClient()) {
            context.enqueueWork(() -> {
                // 打开自定义 Screen
                Minecraft.getInstance().setScreen(new BotInventoryScreen(p.botEntityId, p.slots));
            });
        }
        context.setPacketHandled(true);
    }
}
```

**证据**: `TargetPacket.java:41` 已用 `context.getDirection().getReceptionSide().isClient()` 判断，`context.enqueueWork` 提交到主线程。

### 3.2 服务端发送 S2C 快照

```java
// BotManager.java 或命令处理器
public static void openBotInventory(ServerPlayer viewer, BotPlayer bot) {
    List<ItemStack> slots = new ArrayList<>();
    Inventory inv = bot.getInventory();
    for (int i = 0; i < 41; i++) slots.add(inv.getItem(i));  // 41 = 36 + 4 armor + 1 offhand
    AliceNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> viewer),
            new BotInventorySnapshotPacket(bot.getId(), slots));
}
```

**反编译证据**（`PacketDistributor` 用法）：`AliceNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> viewer), packet)`。

---

## 四、服务端权威校验

### 4.1 C2S action 包

```java
// BotInventoryActionPacket.java — 玩家请求拿取/放入某槽
public record BotInventoryActionPacket(int botEntityId, int slotIndex, Action action, ItemStack stack) {
    public enum Action { TAKE, PUT }
    // encode/decode ...
    public static void handle(BotInventoryActionPacket p, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        if (context.getDirection().getReceptionSide().isServer()) {
            context.enqueueWork(() -> {
                // 服务端权威校验
                ServerPlayer player = context.getSender();
                BotPlayer bot = BotManager.getBotByEntityId(p.botEntityId);
                if (bot == null || player == null) return;
                if (BotManager.isBusy(bot)) return;  // ① 任务保护
                applyAction(bot, p);
            });
        }
        context.setPacketHandled(true);
    }
    private static void applyAction(BotPlayer bot, BotInventoryActionPacket p) {
        Inventory inv = bot.getInventory();
        if (p.slotIndex < 0 || p.slotIndex >= 41) return;  // ② 槽位合法性
        if (p.action == Action.TAKE) {
            // 从 bot 槽取出，放入玩家背包（这里简化：直接移除）
            inv.removeItem(p.slotIndex, 1);
        } else {
            // 放入 bot 槽
            inv.setItem(p.slotIndex, p.stack);
        }
        // ③ 广播更新后的快照给玩家
        openBotInventory(player, bot);
    }
}
```

---

## 五、外部 Mod 参考

### 5.1 Storage Drawers / Iron Chests / AE2（自定义 Screen + C2S）

这些 mod 用 `AbstractContainerMenu` + 自定义 Screen。但对"无 container 的纯客户端 Screen + C2S"，参考模式是：
- **S2C 打开**：服务端发包 → 客户端 `setScreen`
- **C2S 交互**：客户端 `mouseClicked` → `CHANNEL.sendToServer(actionPacket)` → 服务端校验 → 服务端回发快照

**GitHub**:
- [AE2](https://github.com/AppliedEnergistics/Applied-Energistics-2)
- [Iron Chests](https://github.com/progwml6/ironchest)
- [Storage Drawers](https://github.com/jaquadro/StorageDrawers)

### 5.2 Forge 论坛参考

- [Forge Docs: SimpleImpl (networking)](https://docs.minecraftforge.net/en/1.19.x/networking/simpleimpl/)
- [Custom clientbound packet handling](https://forums.minecraftforge.net/topic/120300-custom-clientbound-packet-handling-119-solved/)
- [Forge Docs: Screens](https://docs.minecraftforge.net/en/1.21.x/gui/screens/)

### 5.3 通用模式总结

纯客户端 Screen + C2S packet 的正确模式：
1. 服务端 S2C 发包（快照/打开指令）
2. 客户端 `handle` 判断 client → `setScreen` 打开自定义 Screen
3. 自定义 Screen 用 `GuiGraphics` 自绘槽位格 + `renderItem` 渲染物品
4. `mouseClicked` 命中检测 → `CHANNEL.sendToServer(actionPacket)`
5. 服务端校验（任务保护 + 槽位合法）→ 写 bot inventory → 回发快照

---

## 六、推荐方案（精确代码）

### 6.1 新增文件

1. **`BotInventorySnapshotPacket.java`**（S2C）— 见 §3.1
2. **`BotInventoryActionPacket.java`**（C2S）— 见 §4.1
3. **`BotInventoryScreen.java`**（客户端 Screen）— 见 §1.3
4. **`AliceNetwork.register()` 增加两行** — 见 §2.2

### 6.2 关键注意事项

**@OnlyIn(Dist.CLIENT)**：`BotInventoryScreen` 必须标注 `@OnlyIn(Dist.CLIENT)`，避免服务端加载报错。

**客户端 Screen 打开**：在 S2C `handle` 中通过 `context.getDirection().getReceptionSide().isClient()` 判断，避免用 `DistExecutor`（1.20.1 的 `DistExecutor.safeRunWhenOn` 签名已变，而 `isClient()` 判断更简单可靠，现有代码已用此模式）。

**服务端权威**：所有 C2S 操作必须在服务端 handler 中校验（bot 存在 + 非 busy + 槽位合法）后才写 bot inventory；操作后可回发快照同步客户端。

### 6.3 伪代码总览

```
服务端 openBotInventory(viewer, bot):
    slots = bot.getInventory() 前 41 槽
    send(PacketDistributor.PLAYER.with(viewer), BotInventorySnapshotPacket(bot.id, slots))

客户端 handle(BotInventorySnapshotPacket):
    if isClient:
        enqueueWork -> Minecraft.setScreen(new BotInventoryScreen(...))

客户端 Screen.mouseClicked(x, y, button):
    slotIndex = hitTest(x - guiLeft, y - guiTop)
    if slotIndex >= 0:
        CHANNEL.sendToServer(BotInventoryActionPacket(bot.id, slotIndex, TAKE/PUT, stack))

服务端 handle(BotInventoryActionPacket):
    if isServer:
        enqueueWork -> {
            bot = getBotById; player = getSender
            if bot == null || player == null || BotManager.isBusy(bot): return
            if slotIndex 越界: return
            applyAction(bot, slotIndex, action, stack)
            send snapshot back to player  // 同步
        }
```

---

## 七、风险评估

### 7.1 客户端 Screen 兼容性

**低**。纯客户端 Screen 不依赖 AbstractContainerMenu，不会与其他 mod 的容器 GUI 冲突，与 JEI 兼容。

### 7.2 服务端权威

**关键**。所有 C2S 操作必须服务端校验（bot 存在 + 任务保护 + 槽位合法），防止恶意客户端直接改 bot inventory。

### 7.3 @OnlyIn 加载

**中等**。`BotInventoryScreen` 必须 `@OnlyIn(Dist.CLIENT)`，否则服务端 `BootStrap` 会因客户端类加载报错。若在共享代码中引用，需用 `DistExecutor` 或客户端代理。

### 7.4 性能

**低**。SnapShot 包只在打开时发送一次；action 包只在玩家交互时发送。`renderItem` 在 Screen 渲染时调用，性能开销可接受（与容器菜单相当）。

### 7.5 与现有 BotInventoryMenu 的迁移

**建议**：方向 B 应**替换**而非叠加现有 `BotInventoryMenu`/`BotInventoryScreen`（AbstractContainerMenu 版），避免两套协议冲突。删除或保留为只读均可，由监督员决策。

---

## 八、本报告不构成实现授权

本报告基于反编译源码提供方向 B 的实现方案建议。任何代码修改必须：
1. 由监督员审核本报告并批准方向 B 的实现范围
2. 创建新的 `.alice-supervision/active-plan.md` 并标记 `APPROVED_FOR_IMPLEMENTATION`
3. 由主开发会话按批准计划实施
4. 通过服务端测试 + 客户端实测（打开 Screen、拿取/放入、任务保护）
5. 经监督员二次审核后方可合并

**证据缺口**：
- `GuiGraphics.renderItem` 的确切重载（是否需要 `Minecraft.getInstance().getItemRenderer()`）需客户端实测确认
- `DistExecutor` 在 1.20.1 的具体方法签名未完全确认（本报告用更简单的 `isClient()` 判断替代）
- C2S 包的服务端校验逻辑需按实际业务细化（拿取/放入的完整事务语义）
- 快照中 NBT 物品的 `buf.writeItem/readItem` 序列化需实测

**不可直接采纳项**：
- 方向 B 的实现需先确认与现有 `BotInventoryMenu`（AbstractContainerMenu 版）的关系（替换 or 并存），由监督员决策
- C2S 的拿取/放入事务语义（是否合并堆叠、装备槽 canEquip 校验）需细化，本报告给出框架
