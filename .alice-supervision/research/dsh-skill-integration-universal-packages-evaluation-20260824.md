# DSH Workflow 通用 Skill 包评估

- 评估人：Alice 架构监督员
- 日期：2026-08-24
- 问题：如果不限制 Forge，还有哪些通用 Skill 包可以接入？

## 当前工作流的通用失败模式

### 类型 1：调查不彻底
- **P1 物理调查**：结论"服务端正常"但实测失效
- **Bot inventory GUI**：8 轮失败，每轮只修一个表面问题
- **根因**：缺乏结构化调查清单（应该检查什么、如何验证）

### 类型 2：测试覆盖不足
- **服务端 fixture 通过，客户端实测失败**（P1 物理/Bot inventory GUI）
- **根因**：不知道哪些场景必须客户端实测、不能只靠服务端

### 类型 3：Git 工作流混乱
- **治理提交混入功能提交**（HANDOVER 多次提到"勿覆盖或混入功能提交"）
- **回退测试失败**（Windows Git `unable to read tree`）
- **根因**：缺乏 Git 分支/提交管理的最佳实践

### 类型 4：架构决策不明确
- **Bot inventory GUI 方向 A/B 选择**（6 轮失败后才换方向）
- **SOFT_SURFACE 扩展边界**（多次提到"需用户批准"但边界模糊）
- **根因**：缺乏架构决策模板（何时停止、何时换方向）

### 类型 5：日志/证据采集不规范
- **客户端测试依赖用户描述**（"bot 穿过玩家"但缺日志证据）
- **调查报告结论与实测不符**（"服务端正常"vs"bot 无法推挤"）
- **根因**：缺乏证据采集标准（应该采集什么日志、如何验证）

---

## 通用 Skill 包设计

### 分类 A：调查与诊断 Skill

#### 1. `debugging-root-cause-analysis.skill`
**内容**：
- 5 Whys 分析法（连续问 5 次"为什么"找根因）
- 对比验证法（有/无问题的最小差异）
- 二分查找法（回退测试找引入点）
- 假设-验证循环（提出假设 → 设计实验 → 验证）

**适用场景**：
- P1 物理调查（为什么 bot 不被推挤？）
- Bot inventory GUI 失败（为什么 pickup vanish？）
- 任何"多轮试错"场景

**收益**：
- 减少盲目试错（从"改一个试一个"变成"先诊断再修复"）
- 提高根因定位准确率（避免"表面修复"）

---

#### 2. `failure-pattern-recognition.skill`
**内容**：
- 常见失败模式库（客户端看不到 X / 服务端日志正常但客户端失败 / GUI 交互无响应）
- 每种模式的诊断树（检查 A → 如果 A 正常检查 B → ...）
- 证据链要求（确认 X 失败需要采集哪些日志/截图）

**适用场景**：
- 客户端/服务端同步失败（Equipment / Bot inventory / P1 物理）
- GUI 交互失败（pickup vanish / place double）

**收益**：
- 快速识别失败类型（从"不知道为什么"变成"这是 Y 类问题"）
- 结构化诊断流程（不遗漏检查点）

---

### 分类 B：测试与验证 Skill

#### 3. `test-coverage-matrix.skill`
**内容**：
- 服务端 fixture 的局限性（无法覆盖：GUI/客户端物理/渲染/网络延迟）
- 客户端测试必要场景清单（同步/物理/GUI/多人交互）
- 测试金字塔（单元/集成/端到端 的比例和边界）

**适用场景**：
- 任何新功能实施后的验证
- 服务端 fixture 通过但需确认客户端行为

**收益**：
- 避免"服务端 PASS 但客户端 FAIL"（提前规划客户端测试）
- 减少返工（一次验证覆盖所有必要场景）

---

#### 4. `evidence-collection-standard.skill`
**内容**：
- 日志采集标准（哪些关键字、哪些行号、哪些时间段）
- 截图/视频要求（哪些场景必须截图、如何标注）
- 对比验证（修复前/后 的证据对比）

**适用场景**：
- 客户端测试（P1 物理/Bot inventory GUI）
- 调查报告（需要证据支撑结论）

**收益**：
- 提高证据可信度（避免"用户说 X 但无日志"）
- 减少监督员追问（一次提交完整证据）

---

### 分类 C：Git 与协作 Skill

#### 5. `git-workflow-best-practices.skill`
**内容**：
- 功能分支策略（feature branch / hotfix branch）
- 提交粒度（一个提交只做一件事）
- 回退测试流程（如何安全回退、如何对比）
- 治理提交与功能提交隔离

**适用场景**：
- 回退测试（当前 Windows Git 失败）
- 提交管理（HANDOVER 多次提到"勿混入"）

**收益**：
- 提高 Git 操作可靠性（避免 `unable to read tree`）
- 减少提交混乱（治理/功能分离）

---

#### 6. `code-review-checklist.skill`
**内容**：
- 功能正确性（是否实现 active plan 的 Allowed Scope）
- 边界守护（是否违反 Forbidden 条款）
- 测试覆盖（是否有 fixture / 是否需客户端测试）
- 日志可观测性（是否有关键日志输出）

**适用场景**：
- 监督员验收（settle_task 前的检查清单）
- 主开发自查（report_task 前的自检）

**收益**：
- 提高验收通过率（减少"验收失败 → 重做"）
- 减少遗漏（结构化检查清单）

---

### 分类 D：架构决策 Skill

#### 7. `architecture-decision-framework.skill`
**内容**：
- 何时停止当前方向（失败 N 轮 / 复杂度超预期 / 违反边界）
- 何时换方向（方向 A 失败 → 何时切换方向 B）
- 方向选择决策树（性能/复杂度/维护成本 的权衡）
- 冻结 vs 回退 vs 降级（何时选择哪个）

**适用场景**：
- Bot inventory GUI（6 轮失败后何时换方向）
- P1 物理问题（何时放弃修复、回退 P0）

**收益**：
- 减少无效重试（明确停止条件）
- 提高决策质量（结构化权衡）

---

#### 8. `minimal-implementation-planning.skill`
**内容**：
- 最小可验证单元（MVP）的划分
- 依赖分析（A 依赖 B → B 先实施）
- 风险评估（高风险部分先验证）
- 迭代策略（第一轮做什么、第二轮做什么）

**适用场景**：
- 规划员输出实施路线
- 大功能拆分（如 Bot inventory GUI → 先只读 → 再交互）

**收益**：
- 减少"一次做太多导致失败"（分阶段验证）
- 提高规划质量（明确依赖和风险）

---

### 分类 E：Minecraft/Mod 通用 Skill

#### 9. `minecraft-client-server-sync.skill`
**内容**：
- Minecraft 的客户端/服务端架构（哪些逻辑在哪一端）
- 常见同步机制（Packet / DataWatcher / Level.sendBlockUpdated）
- 同步失败的常见原因（网络延迟 / 包丢失 / 状态不一致）

**适用场景**：
- 任何客户端/服务端同步问题
- 不限于 Forge（Fabric / Vanilla 也适用）

**收益**：
- 理解 Minecraft 基础架构（减少"不知道为什么"）
- 跨 mod 平台适用

---

#### 10. `minecraft-entity-lifecycle.skill`
**内容**：
- Entity 的生命周期（spawn / tick / remove）
- Entity 的同步（ServerEntity / ClientEntity）
- Entity 的物理（travel / push / collision）

**适用场景**：
- P1 物理问题（bot 推挤/击退）
- 任何涉及 Entity 的功能

**收益**：
- 理解 Entity 基础机制
- 跨 mod 平台适用

---

## 通用 Skill 包优先级排序

| Skill | 适用范围 | 收益（避免浪费时间） | 实施难度 | 优先级 |
|---|---|---|---|---|
| `debugging-root-cause-analysis` | **所有调查** | **高**（18-28 小时） | 低 | ⭐⭐⭐⭐⭐ |
| `test-coverage-matrix` | **所有实施** | **高**（12-18 小时） | 低 | ⭐⭐⭐⭐⭐ |
| `failure-pattern-recognition` | **客户端/服务端同步** | 中（8-12 小时） | 中 | ⭐⭐⭐⭐ |
| `evidence-collection-standard` | **所有验收** | 中（6-10 小时） | 低 | ⭐⭐⭐⭐ |
| `architecture-decision-framework` | **大功能/多轮失败** | 高（10-16 小时） | 中 | ⭐⭐⭐⭐ |
| `minimal-implementation-planning` | **规划员** | 中（8-12 小时） | 中 | ⭐⭐⭐ |
| `git-workflow-best-practices` | **回退测试/提交管理** | 低（2-4 小时） | 低 | ⭐⭐⭐ |
| `code-review-checklist` | **监督员验收** | 低（2-4 小时） | 低 | ⭐⭐⭐ |
| `minecraft-client-server-sync` | **Minecraft 通用** | 中（6-10 小时） | 低 | ⭐⭐⭐ |
| `minecraft-entity-lifecycle` | **Entity 相关** | 中（4-6 小时） | 低 | ⭐⭐ |

---

## 推荐 Skill 包组合

### 最小推荐包（立即接入）
1. **`debugging-root-cause-analysis`**（调查员 + 主开发）
2. **`test-coverage-matrix`**（主开发 + 规划员）
3. **`evidence-collection-standard`**（所有成员）

**收益**：避免 36-56 小时浪费（约 4-7 工作日）

---

### 完整推荐包（中期接入）
- 最小包（3 个）
- **`failure-pattern-recognition`**（调查员）
- **`architecture-decision-framework`**（监督员 + 规划员）
- **`minecraft-client-server-sync`**（主开发 + 调查员）

**收益**：避免 62-98 小时浪费（约 8-12 工作日）

---

### Forge 专用包（可选，针对性强）
- **`forge-fakeplayer-lifecycle`**
- **`forge-entity-sync-broadcast`**
- **`forge-container-menu-protocol`**
- **`forge-entity-physics-collision`**
- **`forge-event-priority-cancel`**

**收益**：避免 18-28 小时 Forge 特定失败（约 2-4 工作日）

---

## 最终推荐

### 方案 1：通用包优先（推荐）
- **立即接入**：最小推荐包（3 个通用 skill）
- **中期接入**：完整推荐包（6 个通用 skill）
- **可选接入**：Forge 专用包（5 个）

**理由**：
- 通用 skill 适用范围更广（不限 Forge/Fabric/Vanilla）
- 收益更高（36-56 小时 vs 18-28 小时）
- 实施难度更低（结构化方法论 vs 技术细节）

---

### 方案 2：Forge 包优先
- **立即接入**：Forge 专用包（5 个）
- **中期接入**：通用包（3-6 个）

**理由**：
- Alice 项目当前主要是 Forge 开发
- 立即见效（下一个 Forge 功能就能用）

---

### 方案 3：混合包（最全面）
- **立即接入**：最小通用包（3 个）+ Forge 专用包（5 个）
- **中期接入**：完整通用包（6 个）

**理由**：
- 覆盖最全（调查方法论 + Forge 技术细节）
- 收益最高（54-84 小时）
- 实施成本最高（需创建 11 个 skill）

---

## 你的决策

**问题 1**：选择哪个方案？
- 方案 1：通用包优先（推荐）
- 方案 2：Forge 包优先
- 方案 3：混合包（最全面）

**问题 2**：优先级？
- 立即接入（暂停当前 P1 问题，先接入 skill）
- 下个工作包接入（先解决 P1，下个功能开始时接入）

**本评估不构成实施授权，需用户批准后由 DSH 维护员执行。**
