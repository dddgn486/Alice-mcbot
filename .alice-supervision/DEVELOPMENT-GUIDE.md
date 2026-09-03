# Alice 开发指南

这是给人类开发者的完整开发指南，记录了 Alice 项目中的核心概念、开发流程和常见问题。

---

## 🎯 目录

1. [开发流程](#开发流程)
2. [核心概念](#核心概念)
3. [常用 API](#常用-api)
4. [调试技巧](#调试技巧)
5. [常见问题](#常见问题)
6. [资源链接](#资源链接)

---

## 开发流程

### 标准开发流程

```
1. 理解需求
   ├── 用户想要什么功能？
   ├── 这个功能的边界是什么？
   └── 有什么限制和约束？

2. 查阅 Skills（项目经验）
   ├── .alice-supervision/skills/ 目录
   ├── 看有没有类似问题的解决方案
   └── 学习之前踩过的坑

3. 研究原版/Forge 实现
   ├── 反编译代码（通过 IDE）
   ├── 看 Forge 文档
   └── 参考其他模组

4. 设计方案
   ├── 画出架构图
   ├── 列出需要修改的文件
   └── 考虑客户端-服务端同步

5. 实现代码
   ├── 先写服务端逻辑
   ├── 再写客户端渲染
   └── 最后处理同步

6. 测试验证
   ├── 编译通过
   ├── 游戏内测试
   └── 记录问题

7. 记录 Skill
   ├── 写下遇到的问题
   ├── 记录解决方案
   └── 更新到 .alice-supervision/
```

---

## 核心概念

### 1. 客户端-服务端架构

这是 Minecraft 最核心的概念！

```java
// 判断是否在客户端
if (level.isClientSide()) {
    // 客户端代码：只负责渲染和显示
    // 不要修改游戏状态！
} else {
    // 服务端代码：处理逻辑、修改数据
    // 这是权威的真实数据
}
```

**黄金规则**：
- ✅ **服务端**：所有逻辑判断、数据修改
- ✅ **客户端**：只负责渲染、接收输入
- ❌ **客户端永远不要直接修改游戏状态**
- ❌ **服务端不要尝试渲染东西**

**为什么这样设计？**
- 防止作弊（客户端可以被修改）
- 支持多人游戏（一个服务端，多个客户端）
- 数据一致性（服务端是唯一的真相来源）

---

### 2. 实体同步

**问题**：服务端的实体不会立即出现在客户端！

**原因**：
1. 服务端创建实体
2. 发送 `AddEntityPacket` 到客户端
3. 客户端接收并创建实体副本
4. **有延迟！**（网络延迟 + 处理时间）

**常见错误**：
```java
// ❌ 错误：立即在客户端查找刚创建的实体
ServerLevel level = (ServerLevel) player.level();
BotPlayer bot = spawnBot(level, pos);  // 服务端创建
Entity clientBot = level.getEntity(bot.getId());  // null！客户端还没同步
```

**解决方案**：
- **方案 1**：通过网络包传递必要数据（不依赖实体引用）
- **方案 2**：等待客户端同步完成（不推荐，延迟不确定）
- **方案 3**：不依赖实体引用，使用固定值或 ID

---

### 3. 容器 GUI 系统

容器 GUI（比如箱子、背包）由三部分组成：

#### AbstractContainerMenu（服务端 + 客户端）

```java
public class MyMenu extends AbstractContainerMenu {
    public MyMenu(int id, Inventory playerInv) {
        super(MyMenuType.get(), id);
        
        // 添加槽位
        addSlot(new Slot(container, 0, 8, 18));
    }
    
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        // 处理 Shift+点击
    }
    
    @Override
    public boolean stillValid(Player player) {
        // 检查玩家是否还能使用这个容器
    }
}
```

**职责**：
- 定义槽位（Slot）
- 处理点击逻辑
- 同步数据到客户端

#### AbstractContainerScreen（仅客户端）

```java
@OnlyIn(Dist.CLIENT)
public class MyScreen extends AbstractContainerScreen<MyMenu> {
    public MyScreen(MyMenu menu, Inventory playerInv, Component title) {
        super(menu, playerInv, title);
    }
    
    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        // 渲染背景
    }
    
    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        // 渲染额外内容
    }
}
```

**职责**：
- 渲染 GUI 背景
- 渲染槽位
- 处理鼠标输入

#### MenuType（注册）

```java
public static final RegistryObject<MenuType<MyMenu>> MY_MENU =
    MENUS.register("my_menu",
        () -> IForgeMenuType.create((id, inv, buf) ->
            new MyMenu(id, inv)));
```

**职责**：
- 告诉 Forge 这是个自定义 Menu
- 提供工厂方法创建 Menu

#### 打开 GUI 流程

```
1. 服务端调用 player.openMenu(MenuProvider)
   ├── MenuProvider 提供标题和创建方法
   └── 可以通过 NetworkHooks.openScreen() 传递额外数据

2. Forge 发送 OpenScreenPacket 到客户端
   ├── 包含 containerId
   ├── 包含 MenuType
   └── 包含额外数据（如果使用 NetworkHooks）

3. 客户端收到包
   ├── 调用 MenuType 工厂方法
   ├── 创建客户端 Menu 副本
   └── 查找注册的 Screen 类

4. 创建并显示 Screen
   └── 玩家看到 GUI
```

---

### 4. 网络包系统

Forge 提供了简单的网络包系统。

#### 定义包

```java
public record MyPacket(int data, String message) {
    // 序列化：写入到网络缓冲区
    public static void encode(MyPacket msg, FriendlyByteBuf buf) {
        buf.writeInt(msg.data);
        buf.writeUtf(msg.message);
    }
    
    // 反序列化：从网络缓冲区读取
    public static MyPacket decode(FriendlyByteBuf buf) {
        return new MyPacket(buf.readInt(), buf.readUtf());
    }
    
    // 处理：收到包时执行
    public static void handle(MyPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            // 在主线程执行
            System.out.println("Received: " + msg.message);
        });
        ctx.get().setPacketHandled(true);
    }
}
```

#### 注册包

```java
public class MyNetwork {
    public static final String PROTOCOL_VERSION = "1";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
        new ResourceLocation("alice", "main"),
        () -> PROTOCOL_VERSION,
        PROTOCOL_VERSION::equals,
        PROTOCOL_VERSION::equals
    );
    
    public static void register() {
        int id = 0;
        CHANNEL.registerMessage(id++, MyPacket.class,
            MyPacket::encode,
            MyPacket::decode,
            MyPacket::handle);
    }
}
```

#### 发送包

```java
// 发送到特定玩家
CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new MyPacket(123, "hello"));

// 发送到所有玩家
CHANNEL.send(PacketDistributor.ALL.noArg(), new MyPacket(456, "broadcast"));

// 发送到服务端
CHANNEL.sendToServer(new MyPacket(789, "to server"));
```

---

### 5. Forge 事件系统

Forge 提供了强大的事件系统，可以监听游戏中的各种事件。

#### 注册事件监听器

```java
@Mod.EventBusSubscriber(modid = "alice", bus = Mod.EventBusSubscriber.Bus.FORGE)
public class MyEventHandler {
    
    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        // 每个玩家每 tick 触发
        if (event.phase == TickEvent.Phase.START) {
            // tick 开始时
        }
    }
    
    @SubscribeEvent
    public static void onPlayerInteract(PlayerInteractEvent.EntityInteract event) {
        // 玩家右键实体时触发
        Player player = event.getEntity();
        Entity target = event.getTarget();
    }
    
    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        // 方块被破坏时触发
        // event.setCanceled(true) 可以取消事件
    }
}
```

#### 常用事件

| 事件 | 触发时机 |
|-----|---------|
| `TickEvent.PlayerTickEvent` | 每个玩家每 tick |
| `TickEvent.ServerTickEvent` | 服务端每 tick |
| `PlayerInteractEvent.EntityInteract` | 右键实体 |
| `PlayerInteractEvent.RightClickBlock` | 右键方块 |
| `BlockEvent.BreakEvent` | 破坏方块 |
| `LivingHurtEvent` | 生物受伤 |
| `LivingDeathEvent` | 生物死亡 |
| `EntityJoinLevelEvent` | 实体进入世界 |

---

## 常用 API

### 玩家相关

```java
// 获取玩家背包
Inventory inv = player.getInventory();

// 获取玩家位置
BlockPos pos = player.blockPosition();
Vec3 exactPos = player.position();

// 发送消息给玩家
player.sendSystemMessage(Component.literal("Hello!"));

// 给玩家物品
player.getInventory().add(new ItemStack(Items.DIAMOND, 1));

// 检查权限
if (player.hasPermissions(2)) {
    // 玩家有管理员权限
}
```

### 世界/方块相关

```java
// 获取方块状态
BlockState state = level.getBlockState(pos);

// 破坏方块
level.destroyBlock(pos, true);  // true = 掉落物品

// 设置方块
level.setBlock(pos, Blocks.STONE.defaultBlockState(), 3);

// 获取方块实体
BlockEntity blockEntity = level.getBlockEntity(pos);
```

### 实体相关

```java
// 生成实体
Entity entity = new Zombie(level);
entity.setPos(x, y, z);
level.addFreshEntity(entity);

// 查找附近实体
List<Player> players = level.getEntitiesOfClass(
    Player.class,
    AABB.ofSize(pos.getCenter(), 10, 10, 10)
);

// 传送实体
entity.teleportTo(x, y, z);

// 移除实体
entity.discard();
```

### 物品相关

```java
// 创建物品堆
ItemStack stack = new ItemStack(Items.DIAMOND_SWORD, 1);

// 设置附魔
stack.enchant(Enchantments.SHARPNESS, 5);

// 设置 NBT
CompoundTag tag = stack.getOrCreateTag();
tag.putString("CustomName", "Special Sword");

// 比较物品
if (ItemStack.isSameItemSameTags(stack1, stack2)) {
    // 相同物品和 NBT
}
```

---

## 调试技巧

### 1. 使用日志

```java
import com.dddgn.alice.log.BotLog;

// 信息日志
BotLog.info("Player {} opened GUI", player.getName().getString());

// 警告日志
BotLog.warn("Bot {} is busy", bot.getName().getString());

// 错误日志
BotLog.error("Failed to open GUI: {}", exception.getMessage());

// 调试日志（带标签）
BotLog.info("[GUI_DEBUG] Menu created with {} slots", menu.slots.size());
```

### 2. 使用 IDE 断点

1. 在 IntelliJ IDEA 中点击行号左侧设置断点
2. 启动调试模式：`./gradlew runClient --debug-jvm`
3. 在 IDE 中 Attach Debugger（端口 5005）
4. 游戏运行到断点时会暂停

### 3. 检查客户端/服务端

```java
// 始终检查代码运行在哪一端
if (level.isClientSide()) {
    BotLog.info("[CLIENT] This runs on client");
} else {
    BotLog.info("[SERVER] This runs on server");
}
```

### 4. 热重载

修改代码后不需要重启游戏：
1. 修改代码
2. `./gradlew build`
3. 在游戏中按 `F3 + T` 重新加载资源

**注意**：只对资源文件（材质、模型、语言）有效，代码修改需要重启。

---

## 常见问题

### Q1: 客户端看不到实体/方块/物品变化？

**原因**：服务端修改了数据，但没有同步到客户端。

**解决**：
- 实体：使用 Forge 的自动同步，或发送网络包
- 方块：使用 `level.setBlock(pos, state, 3)`（3 = 通知客户端）
- 物品：使用 `player.containerMenu.broadcastChanges()`

### Q2: NullPointerException 在 tick() 中？

**原因**：FakePlayer 或 Bot 的某些字段未初始化。

**解决**：
- 检查 `connection` 字段是否为 null
- 确保 Bot 通过 `PlayerList.placeNewPlayer()` 注册
- 参考 `forge-fakeplayer-lifecycle.skill.md`

### Q3: GUI 打开后立即关闭？

**原因**：`stillValid()` 返回 false。

**解决**：
```java
@Override
public boolean stillValid(Player player) {
    // 确保这个方法返回 true
    return true;
}
```

### Q4: Shift+点击不工作？

**原因**：`quickMoveStack()` 没有正确实现。

**解决**：
```java
@Override
public ItemStack quickMoveStack(Player player, int index) {
    Slot slot = slots.get(index);
    if (!slot.hasItem()) {
        return ItemStack.EMPTY;
    }
    
    ItemStack stack = slot.getItem();
    ItemStack copy = stack.copy();
    
    // 实现槽位间的移动逻辑
    if (!moveItemStackTo(stack, targetStart, targetEnd, false)) {
        return ItemStack.EMPTY;
    }
    
    slot.setChanged();
    return copy;
}
```

### Q5: 编译错误："找不到符号"？

**原因**：导入了错误的包，或者使用了不存在的类/方法。

**解决**：
1. 检查 import 语句
2. 按 Ctrl+Space 查看可用的类/方法
3. 查看 Minecraft/Forge 源码确认 API

---

## 资源链接

### 官方文档
- [Forge 文档](https://docs.minecraftforge.net/)
- [Minecraft Wiki](https://minecraft.wiki/)

### 开发工具
- [MCP Bot](https://mcpbot.bspk.rs/) - 查看混淆映射
- [Forge Javadoc](https://nekoyue.github.io/ForgeJavaDocs-NG/) - Forge API 文档

### 参考项目
- [Baritone](https://github.com/cabaletta/baritone) - 自动寻路
- [Carpet Mod](https://github.com/gnembon/fabric-carpet) - 假人玩家

### Alice 项目文档
- `.alice-supervision/skills/` - 项目 Skill 文档
- `.alice-supervision/ARCHITECTURE.md` - 架构设计
- `.alice-supervision/QUICKSTART.md` - 快速入门

---

## 贡献指南

发现新的问题或解决方案？请更新这个文档！

1. 编辑 `DEVELOPMENT-GUIDE.md`
2. 添加到相应章节
3. 提交 commit：`docs: add solution for XXX problem`

让这个文档越来越完善！🎉
