# 实体测试物品选择容器 —— 规划采纳审核

- 日期：2026-08-22
- 基线：`49be37efb57375f4e4c82ee6d6f4fb18dfb98d85`
- 规划报告：`.alice-supervision/research/entity-selector-test-item-plan-20260822.md`（规划员任务 `0f62a75f`，已 settle success）
- 结论：**采纳为候选路线（CONDITIONAL）**；6 项设计决策待用户批准后才写入新 active plan。

## 独立核实（本审核人复核代码）

1. `ChestEndpointRef.validate(ServerLevel)` 返回 `Validation(code/handler/blockId)`，`accepted()` 判空 —— 物品 `useOn` 可直接复用做只读端点校验（`ChestEndpointRef.java:23-55`）。
2. `BotManager.assignTransfer(BotPlayer, TransferRequest)` 是唯一 approved 转移任务入口，统一 admission（`BotManager.java:247-261`）。
3. `TransferCodes.UNAUTHORIZED_ACTOR` 等稳定码已存在（`TransferCodes.java:5-32`）。
4. `AliceItems.ITEMS` DeferredRegister 模式可扩展新物品（`AliceItems.java:17-38`）。

## 采纳的设计（与用户申请一致）

- 新独立测试物品 `alice:transfer_endpoint_selector`：右键=destination、Shift+右键=source；点击先做只读 `validate`，失败返回稳定码不记录；返回 `InteractionResult.PASS`，原版箱子 GUI 照常打开，绝不 `setCanceled`。
- 选择只写服务端内存草稿（按玩家 UUID，dimension+pos+selectedTick），非持久、不入 ledger、不入物品 NBT，重启即失效。
- 提交仍走显式命令 `/alice transfer-selection submit <item> <count>`，复用 `BotManager.assignTransfer` 同一 admission；item/count 由命令提供，拒绝 NBT 载体。
- 双层权限 gate：`useOn` 服务端分支 `hasPermissions(2)` + 提交命令挂在 `requires(hasPermission(2))` 下。
- 不接入普通任务链、不改 transfer 语义/ledger/primitive/Task、不复用/改动 C1 扫描或金斧/钻石斧/钻石锄语义。

## 采纳的理由

- 与 A1.1「explicit command only」原则兼容：点击只做定位，写入动作仍由显式命令发起，服务端权威不变。
- 草稿非持久与 A1.1「无跨重启续接」决策一致；选择仅是测试输入便利层，不构成预留或权威事实。
- 世界漂移由既有机制兜底：提交时 `TransferRequest` 构造校验 + primitive 每次写入前重验 + fresh pre。
- 与 49be37e 已修复的 abort/替换/挂起保护无冲突（ledger 只记录已 admission 的 request）。

## 待用户批准（6 项）

1. 物品命名与贴图（建议 `alice:transfer_endpoint_selector`，贴图引用原版 compass/stick 纹理）；
2. 右键映射：右键=destination、Shift+右键=source；
3. 提交命令形态：`/alice transfer-selection submit <item> <count>`；
4. item/count 由命令提供（拒绝物品 NBT 携带）；
5. 草稿超时 2,400 ticks + `status`/`clear` 子命令进入本包；
6. 草稿非持久（重启失效）符合预期。

## 状态

- 本包：`NEEDS_USER_DECISION`，未获批准前不派发开发、不写 active plan。
- A1.1：保持 `CLIENT_TEST_PENDING`，本包独立验收（E1-E10 矩阵），不影响 A1-A9。
