# Bot 主手残留修复最终验收

- 审核人：Alice 架构监督员
- 审核日期：2026-08-24
- 提交：`f33292c`（fix: sync bot inventory after destination transfer leg）
- 计划 ID：`20260824-bot-mainhand-residue-fix-v1`
- 测试结果：**PASS / USER_ACCEPTED**

## 结论

Bot 主手残留 bug（transfer 任务完成后主手物品已入箱但外部模型仍显示、背包不可见）已修复并验收通过。

## 依据

### 服务端验证
- `./gradlew compileJava` PASS
- `git diff --check` PASS
- 既有 suites 均 PASS（TRANSFER_FIXTURE/BOT_INVENTORY/SELECTION/SELECTOR_EVENT/COMMAND_PARSE，14:31:33）

### 客户端实测（用户，2026-08-24 15:32）
- `run/logs/latest.log`：
  - 15:32:50 `transfer request=3cd502d9 state=PLANNED code=accepted`
  - 15:32:52 `task_execution_terminal terminal=COMPLETED code=done recovery=idle_after_cleanup`（47 tick 正常完成）
- 用户实测：任务完成后 Bot 外部模型主手**不再残留物品**，背包数据正确刷新
- 无 mismatch / UNKNOWN_DISCREPANCY / 异常

## 根因与修复（回顾）

- **根因**：`botToDestinationChest`（第二段 leg bot→目标箱）的 `removeBot` 移除物品（含主手 selected 槽）后未同步客户端；只有第一段 leg（源箱→bot）调用了 `syncBotInventorySlots`
- **修复**：第二段 leg 移除成功后补 `syncBotInventorySlots(context.botInventory)`，与第一段对称
- **范围**：只改 `ChestBotTransferPrimitive.java` 一行 + HANDOVER；未改 `syncBotInventorySlots` 本身、未改 TransferTask/BotManager/ledger、未触碰冻结中的 GUI 交互

## 状态

- 主手残留 bug：**已关闭（USER_ACCEPTED）**
- Bot inventory GUI 交互：**保持冻结**（不放弃，重启需满足 prerequisites：view 表 + revision + ActionResult）
- 方案 A（AbstractContainerMenu）：保留但停用（兼容路线）
