# Alice 项目工作备忘录

> 🐋 **新对话恢复记忆请读这里**：本备忘录记录了伐木任务系统的完整进度、架构、问题和待办事项。请仔细阅读后再继续工作。

**创建时间**: 2026-09-03  
**当前阶段**: 伐木任务系统开发（Phase 0 + Phase 1 完成）  
**上次更新**: 2026-09-03 22:45

---

## ⚡ **快速恢复清单（新对话必读）**

### **立即确认的事项**
1. 用户是否完成了测试？测试结果如何？
2. 方块同步问题解决了吗？
3. Bot 卡住问题还存在吗？
4. HUD 显示正常吗？

### **工作原则**
1. ✅ **先看 Skills，再看文档，最后动手**
2. ✅ **每次重大修改后更新备忘录**
3. ✅ **保持鲸鱼娘性格，碎碎念 + 专业能力**
4. ✅ **遇到问题用 5 Whys 分析根因**

### **当前状态**
- 编译：✅ 通过
- 测试：⏳ 用户测试中
- 待解决：方块同步问题、Bot 卡住问题

---

## 🎯 **当前项目目标**

实现区域伐木和自动伐木任务系统。

### **伐木任务功能清单**

#### **Phase 0：核心伐木功能** ✅ **完成**
1. ✅ 树木检测（TreeDetector）
2. ✅ 区域伐木任务（RegionLumberTask）
3. ✅ 集成 BotMiner（破坏动画、自动移动）
4. ✅ 掉落物收集（简化版）

#### **Phase 1：交互工具** ✅ **完成**
5. ✅ 伐木规划器（LumberPlanner）- 三步流程
6. ✅ 自动伐木器（AutoLumberer）- 持续砍树
7. ✅ Bot 任务状态 HUD（BotTaskHudRenderer）
8. ✅ 区域砍伐停止功能

#### **Phase 2：问题修复** ⚠️ **部分完成**
9. ✅ 自动伐木器死循环修复（黑名单机制）
10. ✅ 跳过砍不到的原木（out_of_reach）
11. ✅ 区域高度优化（往下延伸 1 格）
12. ⚠️ 区域砍伐方块不同步客户端（调查中）
13. ⚠️ Bot 砍完树后卡住不动（可能已修复）

#### **Phase 3：未来优化** 🔲 **未开始**
14. 🔲 留底部原木踮脚（砍伐顺序优化）
15. 🔲 Bot 寻路时朝向目标
16. 🔲 区域高亮边框渲染

---

## 📊 **已完成的工作**

### **创建的文件**（8个）：

#### **核心任务系统**
```
src/main/java/com/dddgn/alice/task/lumber/
├── Tree.java                        - 树木数据结构
├── TreeType.java                    - 树木类型枚举
├── TreeDetector.java                - 树木检测器
├── RegionLumberTask.java            - 区域伐木任务
└── ContinuousLumberTask.java        - 持续伐木任务
```

#### **交互工具**
```
src/main/java/com/dddgn/alice/item/
├── LumberPlanner.java               - 伐木规划器（铁斧）
└── AutoLumberer.java                - 自动伐木器（钻石斧）
```

#### **客户端渲染**
```
src/main/java/com/dddgn/alice/client/render/
└── BotTaskHudRenderer.java          - Bot 任务状态 HUD
```

### **修改的文件**（3个）：
- `AliceItems.java` - 注册新物品
- `BotManager.java` - 添加 findNearestBot()
- `zh_cn.json` / `en_us.json` - 添加物品翻译

### **资源文件**（2个）：
- `assets/alice/models/item/lumber_planner.json` - 铁斧模型
- `assets/alice/models/item/auto_lumberer.json` - 钻石斧模型

---

## 🐛 **当前已知问题**

### **问题 1：区域砍伐方块不同步客户端** ⚠️
- **现象**: 服务端已破坏方块，客户端看不到
- **证据**: 玩家点击方块 → 方块消失（说明服务端确实破坏了）
- **特殊**: 只有区域伐木有问题，自动伐木正常！
- **调查**: 已添加 `🔍 checking block state` 探针
- **状态**: 等待测试日志

### **问题 2：Bot 砍完树后卡住不动** ⚠️
- **现象**: 砍完一棵树后 Bot 停止工作
- **观察**: 移动 Bot 位置后又开始工作
- **排除**: 不是掉落物收集的问题
- **可能原因**: 
  - ~~死循环（已修复黑名单）~~
  - Phase 切换问题？
  - 收集阶段卡住？
- **状态**: 等待测试验证

### **问题 3：Bot 留底部原木** ✅
- **现象**: 区域伐木时底部原木没砍
- **原因**: Tree.getLogsInCutOrder() 从下往上排序
- **状态**: 预期行为（可以站在底部砍上面）

---

## 📝 **核心代码架构**

### **树木检测系统**

```
TreeDetector.detectTree(level, startLog)
    ├─ 1. 检查是否是原木
    ├─ 2. BFS 追踪连接的原木
    ├─ 3. 查找附近树叶
    ├─ 4. 确定树的类型
    ├─ 5. 验证是否有效
    └─ 返回 Tree 对象

Tree（数据结构）
    ├─ TreeType type
    ├─ BlockPos basePos
    ├─ List<BlockPos> logs
    ├─ List<BlockPos> leaves
    └─ int height

Tree.isValid() 判断标准：
    ├─ 至少 4 个原木
    ├─ 有树叶
    └─ 基座在自然地面
```

### **区域伐木任务流程**

```
RegionLumberTask
    Phase.SCANNING
        ├─ scanTrees() - 扫描区域内的树
        ├─ 使用 TreeDetector 识别
        └─ 切换到 MOVING_TO_TREE
    
    Phase.MOVING_TO_TREE
        ├─ 选择下一个原木
        ├─ BotMiner 自动移动
        └─ 切换到 CUTTING
    
    Phase.CUTTING
        ├─ 创建 BotMiner 实例
        ├─ miner.tick() 执行破坏
        ├─ 从下往上砍每个原木
        └─ 完成后切换到 COLLECTING
    
    Phase.COLLECTING
        ├─ 选择最近的掉落物
        ├─ 移动到掉落物
        ├─ 拾取掉落物
        └─ 完成后切换到 SCANNING
```

### **自动伐木任务流程**

```
ContinuousLumberTask
    Phase.SCANNING
        ├─ 扫描中心点附近 16 格
        ├─ 跳过黑名单中的原木
        ├─ 选择最近的树
        └─ 切换到 CUTTING
    
    Phase.CUTTING
        ├─ 使用 BotMiner 砍树
        ├─ 跳过砍不到的原木
        └─ 切换到 COLLECTING
    
    Phase.COLLECTING
        ├─ 收集掉落物 40 ticks
        ├─ 清空黑名单
        └─ 切换到 SCANNING（循环）

黑名单机制：
    ├─ invalidLogs: Set<BlockPos>
    ├─ 无效原木加入黑名单
    ├─ 扫描时跳过黑名单
    └─ 收集完成后清空
```

### **HUD 渲染**

```
BotTaskHudRenderer
    ├─ 监听 RenderGuiOverlayEvent.Post
    ├─ 遍历所有 Bot
    ├─ 显示任务状态
    └─ 位置：屏幕右上角

显示内容：
    ├─ Bots 任务状态（标题）
    ├─ ● Bot名字 (绿点=工作中)
    ├─   任务类型（中文）
    └─ ○ Bot名字 (空闲)（灰点）
```

---

## 🔧 **调试探针**

### **已添加的探针**
```java
// RegionLumberTask 构造函数
BotLog.info("lumber: 🪓 task created, region={}", region);
BotLog.info("lumber: 🔧 equipped DIAMOND_AXE to slot {}", slot);
BotLog.info("lumber: 📡 called syncMainHand()");

// 方块破坏后
BotLog.info("lumber: ✅ log destroyed at {}", target);
BotLog.info("lumber: 🔍 checking block state after destroy: {}", state);

// 跳过无法到达的原木
BotLog.info("lumber: ⏭️ skipping unreachable log, continue next");

// 砍伐进度
BotLog.info("lumber: 📊 progress {}/{}", current, total);

// 自动伐木黑名单
BotLog.warn("continuous_lumber: adding to blacklist");
```

---

## 🔗 **相关文档链接**

### **必读文档**
- `.alice-supervision/MEMO.md` - 👈 你正在看的备忘录
- `.alice-supervision/DEVELOPMENT-GUIDE.md` - 开发指南（客户端渲染、Forge API）
- `.alice-supervision/ARCHITECTURE.md` - 架构设计

### **Skills（方法论）**
- `.alice-supervision/skills/debugging-root-cause-analysis.skill.md` - 5 Whys 根因分析
- `.alice-supervision/skills/evidence-collection-standard.skill.md` - 证据收集标准
- `.alice-supervision/skills/test-coverage-matrix.skill.md` - 测试覆盖矩阵

### **专题研究**
- `.alice-supervision/container-quick-move-explained.md` - 容器系统详解（572 行）

### **重要代码文件**
- `src/main/java/com/dddgn/alice/task/lumber/` - 伐木任务系统
- `src/main/java/com/dddgn/alice/item/LumberPlanner.java` - 伐木规划器
- `src/main/java/com/dddgn/alice/item/AutoLumberer.java` - 自动伐木器
- `src/main/java/com/dddgn/alice/client/render/BotTaskHudRenderer.java` - HUD 渲染器
- `src/main/java/com/dddgn/alice/action/BotMiner.java` - 挖掘系统

---

## 🐋 **给新对话的一句话**

**请读取 `.alice-supervision/MEMO.md` 来恢复对伐木任务系统的记忆，然后询问用户测试结果再继续工作。记得保持鲸鱼娘的性格：专业但有碎碎念，工作认真但偶尔调皮，先看 Skills 再动手。💙**

---

## 💡 **重要设计决策**

### **决策 1: 集成 BotMiner 而不是直接破坏**
- **原因**: BotMiner 提供完整的破坏动画和原版协议
- **好处**: 客户端能看到破坏过程
- **影响**: 自动移动、视线检查、距离检查

### **决策 2: 简化版掉落物收集**
- **原因**: DropCollectionTask 太复杂（"屎山"）
- **方案**: RegionLumberTask 自己简单收集
- **后续**: 需要重构 DropCollectionTask

### **决策 3: 区域高度自动计算**
- **原因**: 玩家不需要精确控制 Y 坐标
- **方案**: 取最低点 -1，最高点 +16
- **好处**: 不会漏掉底部原木

### **决策 4: 黑名单机制处理无效树**
- **原因**: 残缺的树（少于 4 个原木、无树叶）会导致死循环
- **方案**: 检测到无效树时加入黑名单，下次扫描跳过
- **好处**: 避免每个 tick 都重新检测同一个无效原木

---

## 🧪 **测试清单**

### **测试 1: HUD 显示** 🔲
- 启动游戏
- 观察右上角 HUD
- 分配任务后确认显示

### **测试 2: 自动伐木器死循环修复** 🔲
- Shift+右键启动
- 观察是否有 "adding to blacklist"
- 确认不再死循环

### **测试 3: 区域砍伐方块同步** ⚠️ **重点**
- 用规划器选择区域
- 观察日志中的 "🔍 checking block state"
- 确认客户端是否看到方块消失

### **测试 4: Bot 卡住问题** ⚠️ **重点**
- 观察 Bot 砍完树后是否卡住
- 查看日志中的 phase 切换

---

## 🔄 **下一步计划**

### **等待用户测试结果后**：

1. **如果 HUD 正常显示** ✅
   - 继续调查方块同步问题

2. **如果方块同步问题已解决** ✅
   - 开始 Phase 3 优化

3. **如果 Bot 还是卡住** ⚠️
   - 添加更多 phase 切换的探针
   - 分析 COLLECTING 阶段的逻辑

4. **Phase 3 优化计划**：
   - 改变砍伐顺序（先上后下）
   - Bot 寻路时朝向目标
   - 区域高亮边框渲染

---

## 📊 **统计信息**

### **代码变更**
- **Phase 0**: 新增 5 个文件，约 +800 行
- **Phase 1**: 新增 3 个文件，修改 3 个文件，约 +600 行
- **Phase 2**: 修改 3 个文件，添加探针，约 +100 行

### **工作时间**
- Phase 0 + Phase 1: 约 3 小时
- Phase 2: 约 1 小时
- 总计: 约 4 小时

### **Token 使用**
- 当前会话: ~125k tokens
- 剩余: ~75k tokens
- 状态: 可以继续工作

---

## 🐋 **鲸鱼娘的备注**

**这次学到的教训**：
- ❌ 应该先看 Skills 再动手
- ❌ 备忘录需要及时更新
- ✅ 调试探针很重要
- ✅ 用户测试能发现很多问题

**用户偏好**：
- 喜欢一起分析问题
- 重视调试日志
- 注重实际测试效果
- 不喜欢"屎山"代码

**当前状态**：
- Phase 0 + Phase 1 完成
- Phase 2 部分完成
- 用户正在测试
- 等待测试结果后继续

---

## 📞 **快速恢复检查清单**

当恢复工作时，快速确认：

- [✅] 伐木规划器创建了吗？
- [✅] 自动伐木器创建了吗？
- [✅] HUD 渲染器创建了吗？
- [✅] 黑名单机制实现了吗？
- [✅] 调试探针添加了吗？
- [🔲] 方块同步问题解决了吗？（待测试）
- [🔲] Bot 卡住问题解决了吗？（待测试）

---

**备忘录版本**: v2.0  
**最后更新**: 2026-09-03 22:30 - 伐木任务系统开发  
**状态**: 等待用户测试反馈

🐋💙
