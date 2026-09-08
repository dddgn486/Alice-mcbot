# 批 2 审查报告：gui 模块（FROZEN 冻结线 + 方向 B 死代码核查）

- **任务**：`2eb5d004-086d-441b-8e75-517f389599e3`（批 2 / gui 模块只读审查）
- **审查基线**：`6c2b461`（HEAD）
- **日期**：2026-08-25
- **依据**：`full-review-plan-20260825.md`（§1.2 批 2/§2/§4/§5）、`review-batch2-transfer-20260825.md`（transfer 已验收：零 GUI 引用）、`docs/HANDOVER.md`（方向 B 9534a5d、FROZEN 19af345 决策记录）、git log（f0ef6fc..9534a5d 方向 B 实施链）。
- **性质**：只读审查。未修改任何文件；未运行测试；未连接任何工具；未触碰 3081/3082/3083。
- **证据方式**：源码行号（gui/ 5 文件 + client/gui/ 2 文件 + client/ClientBotInventoryState + network/ 2 包 + AliceMod + BotCommand）、git show 19af345/9534a5d、grep 全工程引用。

---

## 2.1 定位与文件清单

**模块定位**（类注释原意）：gui = "方向 B 自定义 packet+Screen 的服务端权威入口：client 永不修改本地状态，每个意图经 BotInventoryActionPacket 发送并在服务端校验/写入，随后推送权威快照"（BotInventoryService.java:22-31）。

| 文件 | 角色 | 职责一句话 |
|---|---|---|
| `gui/BotInventoryMenu.java` | 菜单（方向 A 遗留） | `AbstractContainerMenu` 复用 vanilla InventoryMenu 几何的 bot 库存菜单——**未注册死代码（方向 A 保留供回滚）** |
| `gui/ModMenuTypes.java` | 注册（方向 A 遗留） | DeferredRegister MENU 类型——**未注册死代码（AliceMod 不调用 MENUS.register）** |
| `gui/BotInventoryService.java` | 服务 | 服务端权威查询/写入口：snapshot + applyAction（唯一写路径）+ pushSnapshot |
| `gui/BotInventorySnapshot.java` | 快照 | 不可变 record（botId/name/41 slots），服务端权威 |
| `gui/BotInventoryFixture.java` | 夹具 | snapshot 41 槽/PICKUP task_active 守卫/PLACE 服务端权威断言 |
| `client/gui/BotInventoryScreen.java` | 屏幕（方向 B） | 纯客户端 Screen，从 ClientBotInventoryState 渲染，点击发 C2S 意图，不改本地状态 |
| `client/gui/ClientMenuScreens.java` | 注册（方向 A 遗留） | no-op 保留（不再 MenuScreens.register） |
| `client/ClientBotInventoryState.java` | 状态 | 客户端只读快照存储（volatile + openScreen） |

**注册方式**：AliceMod 注册物品/网络/Forge 事件（AliceMod.java:28-37），**无 ModMenuTypes.MENUS.register**（关键：方向 B 后 MENU 注册已移除）；network 包经 AliceNetwork.register（AliceMod.java:30）注册 BotInventoryPacket/BotInventoryActionPacket。

---

## 2.2 边界归属

- **归属线**：gui = **FROZEN 冻结线**（19af345 用户决策，非放弃：8 轮失败后统一根因 = view-model slot index 语义从未是服务端-客户端显式契约；方向 A/B 代码均保留未注册供回滚；重启前提 = view-slot contract + snapshot revision + ActionResult）。
- **不可翻案边界（最重要）**：本模块属冻结线——审查**只核对引用/耦合事实，不触碰、不建议任何 GUI 修改方案**（任务书禁止项 + 规划 §4.2）。
- **相邻边界**：
  - transfer（已验收零 GUI 引用）：gui 侧反向核对——gui 引用 transfer 吗？（见 2.3 T6）；
  - network：BotInventoryPacket（S2C）/BotInventoryActionPacket（C2S）经 viewer 真实连接，不经 bot.connection.send（FakeConnection no-op 教训正确应用）；
  - client：BotInventoryScreen/ClientBotInventoryState 仅客户端（@OnlyIn Dist.CLIENT）。

---

## 2.3 功能正确性核查点

| # | 宣称行为 | 代码实现 | 证据类型 | 差异/疑点 |
|---|---|---|---|---|
| G1 | 方向 B 完全接管（9534a5d 后） | `BotCommand.bot-inventory` 调 `BotInventoryService.open`（BotCommand.java:184）推送快照；`AliceMod` 无 MENUS 注册（AliceMod.java:28-37）；`ClientMenuScreens` no-op（:22-25）；Screen 纯渲染（BotInventoryScreen.java:19-30） | 静态核对 + git diff | **一致**（方向 B 完全接管，见特别核查②） |
| G2 | snapshot 服务端权威只读 | `snapshot(level, botId)` 纯查询（BotInventoryService.java:38-44）；buildSnapshot 只读 getItem（:205-220） | 静态核对 | **一致**（无副作用） |
| G3 | applyAction 唯一写路径 | 校验 actor/bot 存在/isBusy 只读/slot 0-40/canEquip/maxStack=1/binding curse（:62-91,136-156）→ 写后 pushSnapshot（:89） | 静态核对 + 服务端可证 | **一致**（服务端权威；Fixture serverAuthority 断言 :52-57） |
| G4 | BotInventorySnapshot 不可变 | record + `slots = List.copyOf(slots)`（BotInventorySnapshot.java:16-23）；slot() 越界返回 EMPTY（:25-27）；"never holds a bot reference and never mutates inventory"（:14） | 静态核对 | **一致**（但 ItemStack 元素本身可变——见 D1） |
| G5 | C2S 仅意图不改本地 | BotInventoryActionPacket.handle → 服务端 applyAction（BotInventoryActionPacket.java:38-57）；客户端 Screen 从不 setItem（BotInventoryScreen 注释 :22-26） | 静态核对 | **一致**（服务端权威） |
| G6 | gui↔transfer 接缝 | gui 引用 transfer 吗？grep：BotInventoryService 无 transfer 导入；transfer 无 gui 导入（transfer 审查已确认） | 静态核对 | **一致**（双向零引用） |
| G7 | FROZEN 决策一致 | 方向 A 代码（Menu/ModMenuTypes）保留未注册；方向 B 代码（Service/Snapshot/Screen）保留；均未 rollback（19af345 记录） | git show + 静态核对 | **一致**（冻结线未被动摇） |
| G8 | 包类型安全（decode 上限） | BotInventoryPacket.decode `Math.min(buf.readVarInt(), 256)`（:33）；ActionPacket decode 无上限（playerSlot/slotIndex 为 varInt——服务端 applyAction 校验 slotIndex 0-40、playerSlot 范围（:78,117），**越界由服务端拒绝**） | 静态核对 | **一致**（服务端校验兜底） |

---

## 2.4 已知坑与 R# 对照

| 决策/R# | 内容 | 当前代码是否遵守 | 核查点证据 |
|---|---|---|---|
| FROZEN（19af345） | GUI 交互冻结：方向 A/B 代码均保留未注册供回滚；重启需 view-slot contract + revision + ActionResult | ✅ **遵守**：BotInventoryMenu/ModMenuTypes 保留未注册（AliceMod 无 MENUS.register）；ClientMenuScreens no-op；方向 B 代码保留 | 19af345 HANDOVER 记录 + AliceMod.java:28-37 |
| 方向 B（9534a5d） | 自定义 packet+Screen 绕过容器协议（根因 = index partition/setChanged 链） | ✅ **遵守**：BotInventoryService/Snapshot/Packet/Screen 全套在 | 9534a5d diff + 19af345 记录 |
| 8 轮失败根因 | view-model slot index 语义从未是显式契约 | ⚠️ **部分遵守**：9534a5d 后仍用裸 `slotIndex`（0-40）作跨端契约（BotInventoryActionPacket 直接传 int）——**无 viewIndex→region→underlying slot 显式表、无 snapshot revision、无 ActionResult 结构**（19af345 重启前提未实施）——**这是 FROZEN 决策的现状，非缺陷**（重启前提待新计划） | 19af345 记录 + ActionPacket 字段 |
| R# 其他 | transfer 相关 R# 与 gui 无交集（gui 不触碰 ledger/容量） | ✅ 不适用 | grep 无交叉 |

---

## 2.5 Dual-View：服务端可证 vs 需客户端实测

**服务端可证（fixture/日志可断言）**：
- snapshot 41 槽一致性（BotInventoryFixture snapshotPass :28-31）；
- applyAction 守卫（task_active/invalid_slot/invalid_player_slot/armor_mismatch/cursed_binding——Fixture serverAuthority :52-57 部分覆盖）；
- 服务端权威写路径（PICKUP 清源槽/PLACE 校验——Fixture :41-57）。

**需客户端实测（Windows）**：
- Screen 打开/渲染/交互（36+4+1 显示、点击、Shift）——**FROZEN 前 G1-G13 待测；19af345 后 FROZEN，未测**；
- view-index 映射（服务端 0-35 普通/36-39 甲/40 副手 vs 客户端渲染布局）——f7dc5e9 修复后仍待测；
- 包往返时序（snapshot push → Screen 渲染 → C2S → 刷新）。

> **必须写明**：服务端 PASS ≠ 客户端正确。gui 线是 ab510fd 教训的**最大受害线**：8 轮 GUI 失败全是"服务端 fixture PASS + 客户端 FAIL"（view-index 猜错）。FROZEN 决策正因此——**当前任何"服务端快照正确"的 fixture PASS 都不构成客户端验收**；解冻需新计划满足重启前提。

---

## 2.6 证据等级核查

| 项 | 现有证据 | 等级 | 缺口 |
|---|---|---|---|
| 方向 B 服务端实现（snapshot/applyAction/fixture） | BOT_INVENTORY_FIXTURE_SUITE PASS（9534a5d/19af345 记录 slotCount=41） | 服务端 PASS | 无客户端 |
| 方向 A 代码（Menu/ModMenuTypes） | 未注册、保留供回滚（19af345 记录） | 静态核对（死代码） | — |
| GUI 交互客户端验证 | **FROZEN（无 G1-G13 记录）** | CLIENT_TEST_PENDING → **FROZEN** | 全部客户端场景 |
| view-index 修复（f7dc5e9） | 服务端 PASS（13:48:13 记录） | 服务端 PASS | 客户端待测（冻结） |

---

## 2.7 风险等级判定

| 模块/问题 | 等级 | 触发理由 |
|---|---|---|
| BotInventoryService / Snapshot（方向 B 服务端层） | **D 维持** | 服务端权威正确、fixture PASS、冻结线一致；客户端未验收属 FROZEN 现状非缺陷 |
| BotInventoryMenu / ModMenuTypes / ClientMenuScreens（方向 A 遗留） | **A 补测试？→ 实际为"死代码待监督员定"** | **不评修复等级**：死代码保留是 FROZEN 决策的明确部分（19af345："kept unregistered for rollback"）；清理与否由监督员+用户定（见特别核查①，只列证据与选项） |
| BotInventoryScreen / ClientBotInventoryState（方向 B 客户端层） | **D 维持（冻结内）** | 冻结线内不建议任何变更；解冻需新计划 |
| 停止条件 | 未触发 | 未发现崩溃/死锁；FROZEN 边界一致；无替换结论 |

---

## 2.8 替换候选评估判据（只列方向不选型，标注不可翻案项）

**方向：GUI 交互（自研容器 vs vanilla AbstractContainerMenu 复用 vs 方向 B 自定义 packet+Screen）**——规划 §4.2 已列：

| 判据 | 已知证据 | 缺证据 |
|---|---|---|
| M1 维护成熟度 | 方向 A（vanilla 复用）8 轮失败（f0ef6fc..1621ad2）；方向 B（自定义）已实施（9534a5d..f7dc5e9） | 方向 B 客户端验证（冻结中） |
| M2 Forge 1.20.1 兼容 | 方向 A/B 均 Forge 1.20.1 实现；TLM 借鉴调查已做（tlm-maid-inventory-gui-research） | — |
| M3 集成成本 | 方向 B 已接管（BotCommand.open→Service→Packet→Screen）；方向 A 代码保留未注册 | 若切换回方向 A 的整合成本 |
| M4 客户端风险 | 8 轮客户端 FAIL 历史（view-index 猜错根因）；方向 B 协议层消除 vanish/double（server-authoritative） | 方向 B 客户端矩阵（冻结） |
| M5 过渡成本 | 方向 A/B 双代码保留（rollback 成本低） | 重启前提（view-slot contract/revision/ActionResult）实施成本 |

**不可翻案项标注**：GUI 交互 **FROZEN（19af345）**——任何方向切换（含回滚方向 A、继续方向 B、第三方方案）都需**用户重新批准并解冻**；本审查**不形成替换提案**（规划 §4.3 证据清单 1-5 未齐：无客户端矩阵、无重启前提实施）。

---

## 特别核查项结论

### ① 死代码清单（BotInventoryMenu / ModMenuTypes / ClientMenuScreens）

**引用方 grep 结果（已确认）**：

| 类 | 全工程引用方 | 注册可达性 | 结论 |
|---|---|---|---|
| `BotInventoryMenu` | 仅 `ModMenuTypes.java:21,24`（自身 MenuType 工厂引用） | **不可达**：AliceMod 无 `MENUS.register(modEventBus)`（AliceMod.java:28-37 无该行）→ MenuType 从未注册 → 玩家无法打开 | **死代码（未注册保留）** |
| `ModMenuTypes` | 仅 `BotInventoryMenu.java:49`（`super(BOT_INVENTORY_MENU.get(), ...)`——但该构造从未被调用） | **不可达**：DeferredRegister 未 register 到 mod 总线 → RegistryObject 无意义 | **死代码（未注册保留）** |
| `ClientMenuScreens` | **零引用**（grep 无命中；@SubscribeEvent no-op :22-25） | 可达（MOD 总线订阅）但 no-op | **死代码（no-op 保留）** |

**证据（19af345 记录原文）**："The retired `BotInventoryMenu`/`ModMenuTypes`/`ClientMenuScreens` are kept (unregistered) for rollback."——**保留是 FROZEN 决策的明确部分，非遗漏**。

**选项（只列证据与选项，不提交清理方案，清理与否由监督员+用户定）**：
- **选项 1（保留现状）**：维持 19af345 决策——三文件保留未注册供回滚；成本 = 编译期死代码存在（无运行成本，未注册不可达）。
- **选项 2（清理方向 A 三文件）**：删除 BotInventoryMenu/ModMenuTypes/ClientMenuScreens + BotInventoryMenu 内 BotArmorSlot/BotOffhandSlot——需同时确认 BotInventoryService 的 canPlace 已镜像其语义（:136-156 已含 armor/offhand/binding-curse 规则，BotInventoryService 类注释明示"mirroring the retired BotArmorSlot/BotOffhandSlot rules"）；**代价 = 失去方向 A 回滚点**（19af345 明确保留目的），需用户批准且属"修改 GUI 决策"→ 冻结线外动作。
- **选项 3（仅清理 ClientMenuScreens）**：三文件中唯一零引用且无回滚价值的 no-op（方向 B 后 MenuScreens 已不用）——但保留成本也极低。
- **建议倾向（供监督员参考，非结论）**：按 19af345 原意保留至"方向 B 解冻并客户端验收"后再评估；任何清理都需用户批准（冻结线触碰）。

### ② 方向 B 接管完整性（9534a5d 后 menu 路径是否还有玩家可达入口）

**结论：方向 B 完全接管，方向 A menu 路径无玩家可达入口**：
1. **打开路径**：`/alice bot-inventory <name>` → `BotInventoryService.open`（BotCommand.java:184）→ 推送 `BotInventoryPacket`（S2C，`PacketDistributor.PLAYER.with(viewer)`，BotInventoryService.java:52-53）→ 客户端 `ClientBotInventoryState.openScreen`（BotInventoryPacket.handle :41-44）→ `BotInventoryScreen`（纯 Screen）。
2. **交互路径**：Screen 点击 → `BotInventoryActionPacket`（C2S，`CHANNEL.sendToServer`）→ 服务端 `BotInventoryService.applyAction`（唯一写路径）→ 校验/写入 → pushSnapshot。
3. **方向 A 不可达**：`ModMenuTypes.BOT_INVENTORY_MENU` 未注册（AliceMod 无 MENUS.register）→ `player.openMenu` 无工厂可用 → **玩家无法通过任何路径打开方向 A menu**；`BotInventoryMenu` 构造仅被 `fromServer`（ModMenuTypes 工厂）引用，而工厂未注册。
4. **ClientMenuScreens no-op**：不再 `MenuScreens.register`（ClientMenuScreens.java:22-25 注释明示）。

**证据链**：git show 9534a5d（12 文件：Service/Snapshot/Packet×2/ClientBotInventoryState/Screen 新增，AliceMod/BotCommand 改）→ git show 19af345（FROZEN 决策 + "no longer registers ModMenuTypes"）。

### ③ snapshot/service 纯度（BotInventorySnapshot 不可变？BotInventoryService 只读？）

**BotInventorySnapshot：基本不可变，一处文档级注意**：
- record + `List.copyOf(slots)`（:22）——**列表不可变**（增删元素被拒）；
- slot() 越界返回 EMPTY（:25-27）——只读访问；
- "never holds a bot reference and never mutates inventory"（:14）；
- **D1 注意**：`slots` 是 `List<ItemStack>`，ItemStack **元素本身可变**（getCount/shrink/grow）——快照的"不可变"是**容器级**（列表不可增删），元素级依赖调用方不修改 ItemStack 对象（当前 buildSnapshot 传入 inv.getItem(i) 的**引用**，未 copy()——若调用方改 ItemStack 会污染快照）。**实际风险低**（服务端权威单写路径 applyAction 后重建快照），但严格"值语义不可变"未达（ItemStack 未 copy）。

**BotInventoryService：只读查询 + 唯一写路径（服务端权威）**：
- `snapshot()` 纯查询（:38-44，findBot + buildSnapshot 只 getItem）；
- `open()` 只读（:47-56）；
- `applyAction()` 是**唯一写路径**（:62-91），前置校验（actor/bot 存在、isBusy→task_active 只读、slot 0-40、canPlace armor/offhand/binding-curse），写后 pushSnapshot；
- **结论**：服务端权威成立（客户端永不写本地）；写路径收敛于 applyAction 单一入口——符合"共享状态归属"根因分析维度 1 的规范形态（唯一 owner）。

---

## 与 active plan 冲突声明

- gui 模块属 **FROZEN 冻结线**（19af345）：本审查只核对引用/耦合事实，**未触碰、未建议任何 GUI 修改**；死代码清理仅列证据与选项（特别核查①），未提交方案。
- 与 p1-client-sync-fix-v1 **无交叉**（gui 的 S2C 走 PacketDistributor.PLAYER.with(viewer) 真实连接，与 FakeConnection 广播线无关；但共享"服务端→客户端可见性"教训——若监督员需引用，写入 active plan Research Decision）。

---

## 证据分级汇总

- **已确认事实**：gui/ 5 文件 + client/gui/ 2 文件职责与行号；AliceMod 无 MENUS.register（方向 A 不可达）；ClientMenuScreens no-op 零引用；方向 B 完全接管（打开/交互/不可达三层证据）；snapshot 容器级不可变 + service 唯一写路径；gui↔transfer 双向零引用；FROZEN 决策（19af345）与方向 B（9534a5d）遵守。
- **架构推论**：D1（ItemStack 元素未 copy，快照为容器级不可变非值语义不可变——实际风险低）；8 轮失败根因（view-index 非显式契约）为 FROZEN 现状（非缺陷）。
- **待调查**：方向 B 客户端验证（冻结中，解冻需新计划满足重启前提：view-slot contract + revision + ActionResult）；死代码清理决策（监督员+用户定）。

---

**本报告不构成实施授权**；审查未修改任何文件（业务代码/HANDOVER/active plan/skill/客户端记录均未动），未运行测试，未连接任何工具或 endpoint。GUI 任何变更（含死代码清理）需解冻 + 用户批准；替换提案需规划 §4.3 证据清单齐全。

---

## 监督员验收记录（2026-08-25，Architecture Supervisor）

**验收结论：✅ 通过（批 2 第二个模块）**

独立核查：
1. FROZEN 边界严格遵守：报告只核对引用/耦合事实，未触碰、未建议任何 GUI 修改；死代码清理只列证据与选项（保留/清理方向A/仅清ClientMenuScreens），不提交方案 ✅
2. 死代码清单属实：AliceMod 无 MENUS.register（grep 核实 AliceMod.java:28-37）→ BotInventoryMenu/ModMenuTypes 不可达；ClientMenuScreens no-op 零引用（grep 核实）→ 三文件均为"未注册保留"，是 19af345 决策的明确部分（"kept unregistered for rollback"），非遗漏 ✅
3. 方向 B 完全接管属实：/alice bot-inventory → BotInventoryService.open（BotCommand.java:103/184 核实）→ S2C Packet → ClientBotInventoryState.openScreen → BotInventoryScreen；玩家无任何路径可达方向 A menu ✅
4. snapshot 纯度：D1 发现有价值——BotInventorySnapshot 是容器级不可变（List.copyOf）但 ItemStack 元素未 copy（值语义未达，实际风险低因唯一写路径 applyAction）——记录待监督员判定 ✅
5. 8 轮失败根因（view-index 非显式契约）正确标注为 FROZEN 现状（非缺陷），解冻前提 = view-slot contract + revision + ActionResult（19af345 记录）✅
6. 只读性：src 零改动、HEAD 仍 6c2b461 ✅

采纳为批 2 证据。下一步派发 client 模块审查。
