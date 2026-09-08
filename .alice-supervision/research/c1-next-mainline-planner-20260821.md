# C1 后续主线候选路线比较

- 日期：2026-08-21
- 角色：Alice 实现规划员（只读比较）
- Git 基线：`3644429d2d88e1bb6fd1eb24654d828d1451d7f8`
- 当前计划：`20260821-interface-c1-maintenance-f1-f4-v1 / COMPLETED`
- 输入证据：`docs/PRODUCT_ARCHITECTURE_ROADMAP.md:249-304`、`docs/AI_PLAYER_DESIGN.md`、`docs/EXECUTION_FRAMEWORK.md`、`docs/PATHING_REFACTOR.md`、`docs/HANDOVER.md`、`docs/SUPERVISION_PROTOCOL.md`、`.alice-supervision/active-plan.md`、`.alice-supervision/reviews/20260821-interface-c1-maintenance-f1-f4-review.md`

> **本规划不构成实现授权。** 本报告仅比较下一步候选线路；不得据此修改 active plan、派发开发/调查任务、开始 C2+、攻击或诊断实现，或替用户作客户端验收结论。

## 1. 已确认的起点事实

1. C1 的独立扫描器 S1-S4 已由用户 `USER_ACCEPTED`，F1-F4 在业务提交 `10f4cd7`、治理回填 `3644429` 后由监督员判定 PASS；当前 active plan 已 `COMPLETED`。其接受范围仅为独立扫描器身份、只读箱子、无 block entity 和原版钻石铲隔离，不包含 C2/C3/C4、端点语义、转移或机器写（`.alice-supervision/active-plan.md:95-100`、`.alice-supervision/reviews/20260821-interface-c1-maintenance-f1-f4-review.md:14-38`）。
2. 当前没有新的 `APPROVED_FOR_IMPLEMENTATION` 计划；`docs/HANDOVER.md:52` 明确 A、B、LLM 和全部新行为均须新用户批准计划。
3. 实体目标的最小接缝已经存在：`TaskTarget` 有 `ENTITY`/entity id（`src/main/java/com/dddgn/alice/task/TaskTarget.java:16-33`）；目标高亮包已支持实体（`BotManager.broadcastTarget`，`BotManager.java:256-264`）；`TaskExecutionRecord` 已记录任务类别、终态、稳定结果码和 bot 终点（`TaskExecutionRecord.java:6-35`）。但 `BotSession.assign` 对 ENTITY 只返回 `failed:entity_task_unimplemented`（`BotManager.java:448-468`），`TargetSelector.interactLivingEntity` 也仅显示未实现提示（`TargetSelector.java:61-69`）。
4. 现有路径执行器固定 `HARD_PATH`（`PathExecutor.java:18-25`），`MineTask`/`DropCollectionTask` 的固定边界不能被攻击包改动。`SOFT_SURFACE`、FollowTask、流体和逃生都不应作为攻击移动前提（`docs/PATHING_REFACTOR.md:16-44`，`docs/SUPERVISION_PROTOCOL.md:11-18`）。
5. C1 已有 capability 只读快照，但没有物品 insert/extract、模拟、端点 descriptor、库存预留、in-transit 状态或事务恢复实现。源码中的 `IItemHandler` 只用于 `InterfaceScanner` 读取；本地搜索没有 `insertItem`/`extractItem` 调用。路线图把这些列为阶段 C 的独立产出（`docs/PRODUCT_ARCHITECTURE_ROADMAP.md:261-264`）。
6. 旧存档卡死仍只有一段最后日志和后续一次“未复现”的正常进入；没有完整日志、线程转储或新旧世界对照，不能归因给 C1、假人恢复、注册表或其他模块（`docs/HANDOVER.md:48-52`、`docs/SUPERVISOR_HANDOFF.md:15-44`）。

## 2. 推荐顺序：基础到复杂

### 推荐主顺序

1. **A0：`inventory-transfer-transaction-design-v1` 的只读浅调研/设计包**
2. **B0：`AttackTask` 最小闭环的独立功能包**，但只能在用户明确选定目标/误伤/保护规则并批准最小客户端矩阵后进入实现。
3. **A1：受限物品转移的实现包**，只能在 A0 明确端点模型、权限和用户运输模式后另行计划；它属于 C2，不能由本报告或 C1 接受直接开启。
4. **C0：旧存档卡死的证据采集计划**不纳入上述功能序列。仅当用户选择恢复诊断，或问题复发阻塞 Windows 验收时，将其提升为独立优先级工作，不与 A/B 混包。

### 理由

A0 是最小、可回滚、没有写入行为的下一基础工作：它把 C1 只读事实与未来 C2 写入之间的安全缺口显式化，并要求用户先决定“真实背包运输”还是“端点直转”。路线图本身建议 C1 后先进行 `inventory-transfer-transaction-design-v1` 浅调研（`PRODUCT_ARCHITECTURE_ROADMAP.md:283-292`），且协议要求共享库存事务、权限和结果契约稳定前不能让新任务掩盖底座缺口（`SUPERVISION_PROTOCOL.md:77-83`）。

B0 是可作为第一项**功能实现**的较小通用助手闭环：它复用既有 `Task`、`TaskTarget.ENTITY`、实体高亮、终态审计、SurvivalSystem tick 和稳定 `HARD_PATH`，不依赖 C2 或机器语义。它的风险主要是目标授权、攻击原版语义、客户端可见移动/挥击/伤害和误伤，而非接口事务。设计文档也明确列出 “AttackTask 最小闭环” 可与 A 的准备并行，但必须 HARD_PATH 与明确实体目标为边界（`PRODUCT_ARCHITECTURE_ROADMAP.md:289-291`）。

A1 明显比 B0 复杂：它会同时处理两端世界状态、能力模拟、物品守恒、权限、失败定位与恢复，且一旦真实写入即跨 C1→C2 边界；不应把 A0 设计结论直接变成实现授权。C0 不是功能递进，而是证据缺口处理；若用户不选择恢复诊断，它不应阻塞 A0/B0 的独立规划。

## 3. A：库存转移事务设计

### A0 最小可验证包（只读浅调研/设计，不进入 C2）

**目标**：产生固定版本、固定端点类型、固定权限与失败模型的设计证据，回答“第一个 C2 transfer 应当是什么”，而不调用 `IItemHandler.insertItem/extractItem`、不改变库存、不新增命令/GUI/Task。

**文件/对象范围（规划级建议，非实现授权）**：只读检查 `InterfaceSnapshot`/`InterfaceScanner`、bot inventory 与 `DropCollectionTask` 的现有入包事实、Forge 1.20.1 的 `IItemHandler` simulate 契约、`TaskExecutionRecord` 与命令权限现状；产出研究报告或设计文档。不得先创建 C2 descriptor、endpoint registry、transfer handler 或持久化 schema。

**最低必答项**：

- 第一个被允许的端点组合是否只能是原版 chest <-> bot inventory，还是 chest <-> 明确的单一标准 handler；不得把“任意 capability”称为机器语义。
- 将 `simulate=true` 与实际写入明确区分：每一步的 pre-snapshot、模拟结果、实际 delta、post-snapshot 和不匹配处理。
- 事务状态至少如何表示 `planned -> reserved -> extracted -> in_transit -> inserted -> verified`，以及每个中断时物品的可定位归属；该状态模型来自 `AI_PLAYER_DESIGN.md:238-245` 与路线图 `:206-218`，当前尚无代码实现。
- 端点版本/位置/side、权限主体、物品匹配键（含 tag/组件边界）、最大数量、容量、超时、外部玩家干预、重复请求与恢复/人工接管策略。
- 用户选择真实背包物理运输还是服务端端点直转后，各自的可见性、掉落/断线、容量、回滚和客户端测试差异。
- 首个真实 write 的最小端点、允许方向与明确拒绝条件；机器、Mek、AE、side traversal、GUI 自动化和批处理为何不应进入首包。

**依赖**：C1 snapshot 作为只读事实源已稳定；但不等于 endpoint role 或 C2 权限。A0 需要用户先指定首个目标端点/世界场景或授权由监督员提出保守原版 chest-only 方案。A1 还依赖用户对运输模式、权限以及可恢复失败策略的选择。

**复杂度与风险**：中高。难点不是 capability API 调用本身，而是避免吞物、复制、重复提交、跨端点失配和无法定位 `in_transit` 物品。`AI_PLAYER_DESIGN.md:35, 143-145, 214-225` 与路线图 `:39-41, 206-218` 都明确：C1 read 不授予写能力，教程/LLM 也不能补齐 handler、权限或后置验收。

**停止条件**：

- 目标 Forge/Minecraft/Mek/端点 API 版本或 simulate 语义不确定；
- 用户未选择运输模式、首端点、权限主体或失败归属策略；
- 设计要求 side traversal、机器 role 推断、GUI 点击、Adapter、持久化/网络协议或 LLM 才能定义首包；
- 无法提出一次失败后物品位置可证明的保守状态机；
- 任意方案试图将 C1 的 raw slot index 当作输入/输出语义。

**深调研判断**：A0 本身可由监督员先做浅调研，但 A1 前**条件性需要深调研**。原因是 Forge `IItemHandler` simulate/实际调用语义、并发/失效、目标端点实现差异和物品组件比较是版本敏感的 C2 写入前提，符合协议的外部版本语义和跨层高风险触发条件（`SUPERVISION_PROTOCOL.md:43-49`）。若 A0 把第一写入严格限制为当前固定 Forge 版本的原版 chest/bot inventory 且能以本地源码、固定 Forge source 和 headless fixture完整证明，监督员可记录“不需要深调研”的理由；否则不得假定可跳过。

**验证与客户端矩阵**：

- A0：文档/源码/Git 证据审查，无客户端矩阵；这是设计证据，不是 C2 客户端验收。
- A1（未来）：必须 `compileJava`、针对模拟/实际 delta、容量、拒绝、重复、外部变化和失败恢复的 headless fixture；还需 Windows 客户端矩阵。真实背包模式至少观察 bot 背包、移动/中断、物品最终归属与不可复制；端点直转模式至少观察两端 GUI 库存、消息/日志和不触发未经授权的机器行为。用户必须亲自确认客户端可见库存结果；headless PASS 不能取代它。

## 4. B：AttackTask 最小闭环

### B0 最小可验证功能包

**目标**：仅让一个已明确、受用户授权的 `LivingEntity` 目标在稳定 HARD_PATH 可达范围内被 bot 以原版服务器攻击路径处理，并由现有任务终态审计记录完成/拒绝/失败。它不是“保护”功能，不做自动目标搜索、跟随、连战、掉落收集、护航、宠物/玩家识别策略、LLM 或软移动。

**建议最小范围（仅供未来计划采用）**：

1. 增加独立 `AttackTask` 状态机，接收固定 entity id/UUID 与创建时的目标约束；每 tick 验证目标仍存在、同维度、存活、未变为不允许类别，及 bot 的可回收/维生状态。
2. 仅复用 `SurfacePathfinder` + `PathExecutor.MODE == HARD_PATH` 去到预先定义的攻击站位；不得修改 `PathExecutor`、MineTask、DropCollectionTask 或 SOFT_SURFACE。
3. 到位后执行固定的服务端原版攻击 API/冷却语义，使用有限超时/无进展预算；成功条件必须是服务端可验证的目标死亡或计划明确的单次命中后置条件，不能以挥击动画为成功。
4. 经 `BotSession` 统一收尾记录 `TaskExecutionRecord`，保留稳定 code、目标描述、终点位置、取消/危险中断；当前框架已支持这些共同能力（`BotManager.java:470-527`）。
5. 单独的管理员/诊断入口必须仅选择明确实体并输出目标 id/UUID、类型、起始位置、路径/攻击阶段、最终目标状态、失败码与 bot 终点。不得复用 FollowTask、金斧、挖矿选择器默认链。是否改造 `TargetSelector` 的实体右键仅能在未来计划中明确授权；更保守的首包是新短命令或专用测试物品。

**依赖**：

- 已有实体目标、客户端高亮和审计接缝足以支撑最小设计，但目前没有 attack API 调用、实体目标有效性策略或攻击测试 fixture。
- 用户必须先定义允许目标集合（建议首包仅由明确测试入口选择的、非玩家、非宠物、非村民且可攻击的单一原版敌对/测试实体），误伤/保护区规则、是否只完成一次击杀、是否拾取掉落物。
- 需要核对 `SafeZoneData` 仅现有方块保护（`BlockBreakSafety.java` 相关引用）并不能自动代表实体攻击授权；不得假设已有保护区策略覆盖攻击。

**复杂度与风险**：中等，高于 A0、低于 A1。核心代码范围可以小，但客户端风险高：HARD_PATH 是手动位置步进并 `setOnGround` 的稳定兼容模式（`PathExecutor.java:9-17`），实体会移动、攻击距离/视线、攻击冷却、无敌帧、仇恨、反击与掉落都可能改变客户端感知。必须防止从“指定攻击”滑向“自动保护”。

**停止条件**：

- 需要修改 HARD_PATH 或接入 NATIVE_TRAVEL/SOFT_SURFACE/FollowTask 才能追击；
- 需要自动搜索或重选目标、攻击玩家/宠物/中立实体、保护区策略、群体目标或 LLM 才能完成；
- 原版 server attack API、冷却/伤害归因在当前 Forge 版本无法由固定源码证据确认；
- 目标移动导致最小路径/攻击站位模型不足，需要更复杂追逐或导航原语；
- 成功无法用服务端目标状态和 TaskExecutionRecord 验证；
- 测试会改变无关世界状态、触及 MineTask/DropCollectionTask 或将失败转为隐式传送/硬路径外回退。

**深调研判断**：实施前建议做**窄深调研或至少固定版本源码核查**。理由：攻击涉及 Forge 1.20.1 的 `ServerPlayer`/game mode 攻击路径、cooldown、damage attribution、entity removal/loot/事件以及 fake player 行为；这些是版本敏感的原版语义，且功能跨执行、实体物理、用户体验和客户端验收，符合协议深调研触发项。调查必须只回答“最小明确实体攻击如何走原版服务端路径、哪些前后置与事件可验”，不得泛搜战斗 AI 或扩展为保护系统。

**验证与客户端矩阵**：

- headless：compile；确定性 fixture 覆盖目标有效性拒绝、不可达/超时、一次成功后的目标状态、终态记录、维生中断时 bot 回收。若攻击/战利品随机或时序不稳定，必须如实标记缺口，不能伪造 PASS。
- Windows：**必需**。独立诊断入口在一次性隔离世界中测试：明确目标选择/高亮；HARD_PATH 到攻击站位；一次攻击/击杀的可见动作与真实伤害；目标移动或不可达时停止；玩家/宠物/中立实体拒绝；bot 最终位置、`/alice status` latest record 和完整日志。用户以观察证据作结论；当前运行时无识图能力不改变该规则，截图只由用户观察/文件记录支撑，规划员不标记 `USER_ACCEPTED`。

## 5. C：旧存档卡死诊断

### C0 最小可验证诊断计划

**目标**：仅在用户明确选择恢复诊断时，收集足以缩小生命周期责任边界的证据；不修代码、不重试掩盖现象、不把结果归因到 C1 或假人恢复。

**最小步骤**：

1. 用户确认可使用的旧存档副本与停止阈值，避免在唯一/有价值世界反复尝试。
2. 对同一固定构建建立新空白世界与旧存档的进入对照，记录是否获得控制及卡死阶段。
3. 每次复现保留完整 Windows `latest.log`、`debug.log`；发生卡死时优先保存客户端和 integrated server 线程转储。
4. 以日志时间线核对 registry remap、SavedData 恢复、`PlayerList.placeNewPlayer`、首个 server tick 和客户端同步的最后推进点；结论仅为“已排除/待调查/证据不足”。
5. 在证据能区分责任模块前停止，任何修复另开窄工作包。

这些步骤直接来自 `docs/SUPERVISOR_HANDOFF.md:34-44` 与 `docs/HANDOVER.md:48-52`。

**依赖和风险**：依赖用户同意恢复诊断、可安全复制的旧世界和能采集的 Windows 日志/线程证据。风险是反复启动破坏证据、把最后一条日志误认为根因，或将 C1 scanner、registry、BotSavedData、fake connection 等无证据地归因。

**停止条件**：用户未授权；无安全世界副本；无完整日志/线程证据；观察不再可重现；诊断要求改业务代码或混入 A/B。任何一个成立都返回监督员，不实施修复。

**深调研判断**：C0 不先做外部深调研。它缺的是 Alice/Windows 运行证据，而不是理论资料；先取证才知道是否需要对特定 Forge lifecycle、fake-player 或其他模组冲突做有界深调研。

**客户端矩阵**：必需，但它是诊断矩阵而不是功能验收矩阵。记录用户观察的加载阶段、完整日志、线程转储和新旧世界对照；不因“正常进入一次”而宣布修复，不产生 `USER_ACCEPTED`。

## 6. 用户必须决定的事项

1. 是否先批准 A0 的只读事务设计/浅调研作为下一个基础包；是否指定第一个端点（推荐保守的原版 chest-only 研究范围），以及是否允许后续研究使用固定 Forge 源码证据。
2. 对未来 A1：真实背包运输还是端点直转。该选择决定事务状态、风险、可见性、恢复和客户端矩阵，不能由开发员或 LLM 推断。
3. 是否将 B0 作为 A0 后（或与 A0 完全隔离地并行）的第一功能实现；若是，首包允许攻击哪些实体、是否限定一次性测试目标、是否禁止战利品拾取、对玩家/宠物/村民/中立实体的默认拒绝，以及保护区对实体攻击是否应先保持“禁止/未支持”。
4. 是否恢复 C0 诊断；若是，提供可复制旧存档与允许的证据采集方式。若否，C 保持冻结且不得以“顺手修复”混进 A/B。
5. 首个目标整合包及优先模组/版本。它不阻塞 B0 或 A0，但决定 A1 是否可继续以原版 container 为首端点，还是需要目标模组的专用适配研究。

## 7. 不可扩大边界

- C1 已稳定，但它只提供 raw unsided readonly facts；本比较不会、也不能把 C1 解释为 C2 描述符、端点角色、side semantics、simulate/write、transfer、Adapter 或机器自动化授权。
- A0 必须保持只读。A1 即使将来获准，也必须是单独 C2 计划和用户决定，不能由 A0 文档、compile 或 headless 结果自动升级。
- B0 是独立通用助手行为线，不改变 C1 语义；它必须保持 HARD_PATH、明确单实体、独立测试入口，不接入 FollowTask、SOFT_SURFACE、保护、矿链、拾取链、道路、隧道、流体、逃生或 LLM。
- C0 只收集证据，不修复，不将旧存档问题归因给 C1 或任何模块。
- `MineTask`、`DropCollectionTask` 与普通挖矿保持 HARD_PATH；`SEARCH_LIMIT != UNREACHABLE`，不得触发隧道授权。

## 8. 监督员可采用的最小下一步

建议监督员先向用户呈报两条明确、不可混合的选择：

- **默认基础路线：A0**，只读事务设计/浅调研，输出首个 C2 的端点、权限、事务状态和用户模式决定清单，不写入库存。
- **独立行为路线：B0**，仅在用户先选择攻击目标和误伤规则后，先做固定版本攻击语义深调研，再以独立入口进行 HARD_PATH 单实体闭环。

C0 仅在用户选择恢复诊断时单列。无论用户选择何项，监督员仍须据此创建新的 `NEEDS_USER_DECISION` 计划、记录研究采纳与客户端矩阵，待用户批准后才可能转 `APPROVED_FOR_IMPLEMENTATION`。

## 9. 交付状态

- 报告路径：`.alice-supervision/research/c1-next-mainline-planner-20260821.md`
- 本任务未修改业务代码、active plan、HANDOVER、审核记录或客户端记录，未派发任务。
- **本规划不构成实现授权。**
