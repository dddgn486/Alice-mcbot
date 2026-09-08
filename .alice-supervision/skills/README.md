# Alice 项目 Skills 索引

本目录包含从实际开发中提炼的技术 skills，每个 skill 都是从真实失败案例中总结的经验。

---

## 📚 Skills 列表

### **🔧 Forge 技术类**（直接解决具体技术问题）

| Skill | 用途 | 何时使用 |
|-------|------|---------|
| **forge-blockpos-mutability** | BlockPos 可变对象陷阱 | 对象的值"神奇地"变了、存储的坐标都变成最后一个值 |
| **forge-fakeplayer-lifecycle** | FakePlayer 生命周期管理 | FakePlayer 相关的任何问题（创建、清理、权限） |
| **forge-entity-sync-broadcast** | 实体同步与广播 | 实体在客户端看不到、位置不同步 |
| **forge-container-menu-protocol** | GUI/容器协议 | GUI 相关问题（打不开、数据不同步、槽位错误） |
| **forge-entity-physics-collision** | 实体物理与碰撞 | 实体移动、碰撞、摩擦力、重力问题 |
| **forge-event-priority-cancel** | 事件优先级与取消 | 事件监听器不生效、顺序错误、取消逻辑问题 |
| **minecraft-client-server-sync** | 客户端-服务端架构 | 搞不清哪些代码在客户端/服务端运行、单人正常多人出错 |

---

### **🐛 调试类**（帮助定位问题根因）

| Skill | 用途 | 何时使用 |
|-------|------|---------|
| **debugging-root-cause-analysis** | 根因分析（5 Whys） | 同一问题失败 2+ 次、盲目试错浪费时间 |
| **failure-pattern-recognition** | 失败模式识别 | 多次失败症状不同、需要提取共性根因 |

---

### **🧪 测试类**（把真人测试压缩到最少操作）

| Skill | 用途 | 何时使用 |
|-------|------|---------|
| **alice-scene-based-testing** | 一键场景 + 一键自检电池 + 证据规范 | 新增/修改可观察行为需要真人验证、用户抱怨测试繁琐、设计测试入口/物品/数据包场景时 |

---

## 🎯 使用指南

### **开发流程中的 Skills 使用**

#### **1. 开发新功能前**
- 涉及 Forge API → 查阅对应的 `forge-*.skill.md`
- 客户端-服务端交互 → `minecraft-client-server-sync.skill.md`
- 要设计测试入口/场景 → `alice-scene-based-testing.skill.md`（零参数 + 一键场景 + 一键自检）

#### **2. 遇到问题时**
- 同一问题失败 2+ 次 → `debugging-root-cause-analysis.skill.md`（用 5 Whys 分析）
- 多次失败症状不同 → `failure-pattern-recognition.skill.md`（提取共性）
- 测试太繁琐/入口要输坐标 → `alice-scene-based-testing.skill.md`
- 具体技术问题 → 查阅对应的 Forge skill

#### **3. 常见症状速查**

| 症状 | 查阅 Skill |
|------|-----------|
| 实体在客户端看不到 | `forge-entity-sync-broadcast` |
| GUI 打不开/数据不对 | `forge-container-menu-protocol` |
| 实体移动有问题 | `forge-entity-physics-collision` |
| 事件监听器不生效 | `forge-event-priority-cancel` |
| FakePlayer 相关问题 | `forge-fakeplayer-lifecycle` |
| 对象的值"神奇地"变了 | `forge-blockpos-mutability` |
| 单人正常多人出错 | `minecraft-client-server-sync` |
| 不知道为什么失败 | `debugging-root-cause-analysis` |

---

## 📖 Skill 结构

每个 Forge 技术类 skill 包含：

1. **问题描述** - 什么问题，为什么会发生
2. **典型症状** - 如何识别这个问题
3. **根本原因** - 底层机制解释
4. **解决方法** - 具体的修复代码
5. **实战案例** - Alice 项目中的真实案例
6. **调试技巧** - 如何快速定位问题
7. **相关陷阱** - 类似的问题

---

## 🔄 Skills 更新原则

### **添加新 Skill 的条件**
- ✅ 问题很隐蔽，调试时间 > 1 小时
- ✅ 问题具有普遍性，可能再次遇到
- ✅ 有明确的技术机制解释
- ✅ 有具体的解决方法和代码示例

### **删除 Skill 的条件**
- ❌ 过于严苛的工作流要求
- ❌ 适合大型项目，不适合日常开发
- ❌ 内容已过时或不再适用

---

## 💡 设计理念

**Skills 不是**：
- ❌ 严格的工作流规范
- ❌ 繁琐的文档模板
- ❌ 形式化的流程要求

**Skills 是**：
- ✅ 实用的技术知识库
- ✅ 快速查阅的问题手册
- ✅ 从失败中提炼的经验

**核心原则**：
> 如果一个 skill 让你觉得"太麻烦"，那它就不该存在。  
> Skills 应该帮助你更快解决问题，而不是增加负担。

---

## 📊 统计

- **总 Skills 数量**：18 个
- **Forge 技术类**：8 个（含 capability/adapter 边界）
- **调试类**：2 个
- **测试类**：1 个（`alice-scene-based-testing`）
- **Alice 协作/业务契约类**：7 个
- **累计避免浪费时间**：~60-90 小时（基于历史失败案例估算）

---

**最后更新**：2026-09-08  
**维护者**：Alice 项目团队
