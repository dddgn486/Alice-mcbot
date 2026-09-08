# DSH Workflow Skill Integration 评估：Forge Mod Development

- 评估人：Alice 架构监督员
- 日期：2026-08-24
- 触发：用户观察到开发进度曲折，提出接入 Forge 模组开发 skill 的可能性
- 背景：Hermes agent 的优势是能吸纳大量 skill 数据；DSH 现在支持 skill 工具

## 当前工作流运行状况分析

### 成功案例（顺利完成）
1. **C1 快照 + 独立扫描器**（`8bb2d7b`）：2-3 小时实施 + 客户端验证 PASS
2. **A1.1 原版箱子转移**（`49be37e`）：规划 + 实施 + focused fixture + 客户端验证，约 8-10 小时总计
3. **Bot 死亡过滤**（`2ef35e5`）：1-2 小时快速修复
4. **Bot mainhand residue**（`f33292c`）：1-2 小时定位 + 修复

### 曲折案例（多轮失败/重做）
1. **Bot 装备渲染**（`a8b84b4` → `2d26da8` → `ef43bd6`）：3 次尝试，最终采用 vanilla broadcast
2. **Bot inventory GUI**（方向 A 6 次失败 → 方向 B 2 次失败）：8 轮失败，最终 FROZEN
3. **P1 物理问题**（`2298dd3` → `65f4863` → `ab510fd`）：
   - 服务端实施顺利（2-4 小时）
   - 客户端同步问题：调查 1 轮 + 修复 1 轮
   - 但引入玩家跳跃异常 + bot 物理失效未解决

### 曲折的根因分类

| 根因类型 | 案例 | 是否 Skill 可解决 |
|---|---|---|
| **Forge/Minecraft 特殊机制不熟悉** | FakeConnection S2C 包丢弃、Equipment sync 需 broadcast 而非 send | ✅ **可解决** |
| **Container Menu 协议复杂** | Bot inventory GUI 8 轮失败（slot index / setChanged / quickMoveStack） | ✅ **可解决** |
| **客户端/服务端同步语义** | Bot inventory pickup vanish、Equipment not visible | ✅ **可解决** |
| **Fake Player 物理边界** | Bot 无法被推挤/击退（根本限制？） | ❓ **待确认**（可能是 Forge 固有限制，skill 无法解决） |
| **测试覆盖不足** | 客户端验证依赖用户手动测试，服务端 fixture 无法覆盖物理/GUI | ⚠️ **Skill 部分解决**（可提供测试矩阵模板） |
| **调查不彻底** | P1 物理调查结论"服务端正常"但实测失效 | ⚠️ **Skill 部分解决**（可提供调查清单） |

---

## Forge Mod Development Skill 接入可行性评估

### Skill 数据应包含的内容

#### 核心 Forge 机制 Skill
1. **FakePlayer / ServerPlayer 生命周期**：
   - `PlayerList.placeNewPlayer` / `respawn` / `remove`
   - `Connection` 的作用（S2C 包发送、客户端同步）
   - FakePlayer vs 真实 Player 的差异（Connection / GameProfile / 权限）

2. **Entity 同步与广播**：
   - `ServerEntity` 的追踪机制（位置/速度/装备同步）
   - `Connection.send(Packet)` vs `Level.getChunkSource().broadcast(Entity, Packet)`
   - 客户端实体状态更新的触发条件

3. **Container Menu 协议**：
   - `AbstractContainerMenu` 的 slot index 语义（bot slots / player slots / quickMoveStack partition）
   - `Slot.set()` / `setChanged()` / `broadcastChanges()` 的调用时机
   - 客户端/服务端 slot 状态同步（`ClientboundContainerSetSlotPacket`）

4. **Entity 物理与碰撞**：
   - `LivingEntity.travel()` / `aiStep()` / `pushEntities()`
   - `Entity.push()` / `doPush()` / `isPushable()`
   - `LivingEntity.hurt()` / `knockback()` 的触发条件
   - FakePlayer 是否受物理影响（`noPhysics` / `isSpectator` / `canCollideWith`）

5. **Forge Event 优先级与取消**：
   - `PlayerInteractEvent.RightClickBlock` 的事件顺序
   - `setCanceled()` / `setResult()` 对后续行为的影响
   - Forge 与原版 GUI 的交互

#### 测试与验证 Skill
6. **Headless 测试模式**：
   - `runServer -Dalice.selftest.auto=true` 的局限性
   - Focused fixture 设计模式（独立、可断言、快速）
   - 服务端测试无法覆盖的场景（GUI / 客户端物理 / 渲染）

7. **客户端验证矩阵**：
   - 客户端测试的必要场景（同步 / 物理 / GUI / 渲染）
   - 证据采集标准（日志关键字 / 截图 / 操作步骤）
   - 失败诊断的证据链（服务端日志 vs 客户端日志 vs 观察现象）

---

## 接入 Skill 的收益估算

### 可避免的失败（假设有 Skill）

| 失败案例 | 失败轮数 | 浪费时间 | Skill 可避免？ |
|---|---|---|---|
| Equipment rendering（`a8b84b4` → `ef43bd6`） | 2 轮 | 4-6 小时 | ✅ 是（Skill: "FakePlayer send() is no-op, use broadcast"） |
| Bot inventory GUI slot index（方向 A 1-6 轮） | 6 轮 | 12-18 小时 | ✅ 是（Skill: "Container Menu slot index partition + setChanged chain"） |
| Bot inventory GUI custom packet（方向 B 7-8 轮） | 2 轮 | 4-6 小时 | ⚠️ 部分（Skill 可提供模板，但 view-slot contract 仍需设计） |
| P1 client sync fix 引入跳跃异常 | 1 轮 | 2-4 小时（待修复） | ✅ 是（Skill: "broadcast 范围过滤 + packet 类型白名单"） |

**总浪费时间**：22-34 小时（约 3-4 个工作日）

**Skill 可避免**：18-28 小时（约 80%）

---

## 当前工作流的 Skill 缺口

### 缺口 1：Forge 特殊机制知识
- **现状**：主开发、规划员、调查员都是通用 LLM，不专门了解 Forge 1.20.1
- **表现**：FakeConnection / ServerEntity / Container Menu 等核心机制需要多轮试错
- **Skill 补齐**：提供 Forge 1.20.1 核心机制的 step-by-step 指南

### 缺口 2：失败模式识别
- **现状**：失败后只能"重新调查"或"换方案"，缺乏结构化诊断
- **表现**：Bot inventory GUI 8 轮失败，每轮都在猜测根因
- **Skill 补齐**：提供常见失败模式的诊断树（如"客户端看不到 bot 装备" → 检查 broadcast / send / sync 路径）

### 缺口 3：测试覆盖清单
- **现状**：服务端 fixture 顺利，客户端测试依赖用户手动发现问题
- **表现**：P1 服务端验证 PASS，客户端测试发现物理失效
- **Skill 补齐**：提供客户端测试矩阵模板（哪些场景必须客户端实测、不能只靠服务端 fixture）

---

## 接入方案设计

### 方案 A：主开发专用 Skill（推荐）
- **接入位置**：主开发会话（session-30b693e7）
- **Skill 范围**：Forge 核心机制（FakePlayer / Entity sync / Container Menu / Event）
- **调用时机**：实施前调用相关 skill，获取 step-by-step 指南
- **风险**：低（只增强主开发能力，不改变工作流）

### 方案 B：调查员专用 Skill
- **接入位置**：深度调查员会话（session-f26bd205）
- **Skill 范围**：失败模式诊断树、外部资源查询（Forge 文档/源码）
- **调用时机**：调查任务启动时加载相关 skill
- **风险**：低

### 方案 C：全角色通用 Skill
- **接入位置**：所有成员（主开发/规划员/调查员/监督员）
- **Skill 范围**：Forge 核心机制 + 测试矩阵 + 失败诊断
- **调用时机**：工作会话启动时自动加载
- **风险**：中（需要 DSH 维护员改造 skill 加载机制）

---

## 推荐决策

### 立即可行：方案 A（主开发专用 Skill）
1. **创建 Forge Mod Development Skill 包**：
   - `forge-fakeplayer-lifecycle.skill`
   - `forge-entity-sync-broadcast.skill`
   - `forge-container-menu-protocol.skill`
   - `forge-entity-physics-collision.skill`
   - `forge-event-priority-cancel.skill`

2. **主开发实施前强制调用**：
   - 修改 `./tools/work-session-start.sh`，如果 active plan 涉及 FakePlayer / GUI / 物理，提示主开发先 `skill("forge-xxx")`
   - 或在 active plan 中明确标注 `Required Skills: forge-fakeplayer-lifecycle, forge-entity-sync-broadcast`

3. **收益**：
   - 避免 80% 的 Forge 机制相关失败
   - 实施时间缩短 30-50%
   - 不改变现有工作流架构

### 中期改进：方案 A + B（主开发 + 调查员）
- 调查员增加失败诊断 skill（`forge-failure-diagnostics.skill`）
- 提供常见失败模式的结构化诊断树

### 长期演进：方案 C（全角色通用）
- 需要 DSH 维护员支持 skill 自动加载
- 监督员可用 skill 验证实施质量（如 `verify-forge-sync-correctness.skill`）

---

## 风险与限制

### Skill 无法解决的问题
1. **Forge/Minecraft 固有限制**（如 FakePlayer 物理可能无法修复）
2. **设计决策**（如 Bot inventory GUI 的 view-slot contract 仍需架构设计）
3. **用户需求变更**（Skill 无法预测需求方向）

### Skill 数据维护成本
- Forge 1.20.1 → 1.21+ 升级时 skill 需更新
- Alice 项目特定约束（如冻结边界）需在 skill 中明确

---

## 最终评估结论

### 可行性：✅ **高度可行**
- DSH 已支持 skill 工具
- Forge 机制知识可结构化为 skill
- 主开发会话可直接调用 skill

### 必要性：✅ **强烈推荐**
- 当前曲折案例中 80% 可通过 skill 避免
- 可节省 18-28 小时（约 3-4 工作日）
- 不改变现有工作流，只增强成员能力

### 推荐方案：**方案 A（主开发专用 Skill）**
- 立即创建 5 个核心 Forge skill
- 主开发实施前强制调用相关 skill
- 监督员在 active plan 中标注 `Required Skills`

---

## 下一步行动（如果批准）

1. **创建 Forge Skill 包**（监督员或独立 skill 维护员）
2. **修改 active plan 模板**（增加 `Required Skills` 字段）
3. **主开发实施时验证**（下一个涉及 Forge 机制的工作包）
4. **评估效果**（对比有/无 skill 的实施时间）

**本评估不构成实施授权，需用户批准后由 DSH 维护员执行。**
