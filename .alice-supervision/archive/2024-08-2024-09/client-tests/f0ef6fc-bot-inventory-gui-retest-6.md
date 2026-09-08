# Bot Inventory GUI 复测说明（第 6 轮）

- 关联提交：`1621ad2`（fix: sync bot slots to client on pickup click）
- 计划 ID：`20260824-bot-inventory-gui-pickup-sync-fix-v1`
- 测试人：`user`
- 状态：`NOT_STARTED`（第 6 轮复测）

## 本次修复（关键：定位到 PICKUP 同步问题）

**为什么前 5 轮没修好**：都聚焦在 `quickMoveStack`（Shift 快速移动路径），但你的实际操作是**普通左键点击（PICKUP）**，走的是另一个路径（`clicked()`）！

**真正根因**（源码诊断确认）：
- PICKUP 拿取时服务端其实**正确**（物品到了你手持）
- 但 `Inventory.setItem/removeItem` 不触发 `setChanged()`，加上 bot 的 `FakeConnection` 是 no-op
- → 客户端 GUI 的 bot 槽位**快照没刷新**，视觉上"物品消失了"（其实在你手里）

**本次修复**：PICKUP 触摸 bot 槽后，强制遍历 bot 槽 `setChanged()` 触发重新同步 → 客户端应该正确显示（物品不再"消失"）

## 复测步骤（第 6 轮）

**先重启客户端**（IDEA 运行 `runClient` 加载 `1621ad2`）

### 核心验证（普通左键点击拿取）
1. `/alice bot-inventory tango` 打开 GUI
2. **普通左键点击**从 bot 拿取物品到玩家背包
3. **观察**：物品是否**不再消失**？（应该在玩家背包正确显示）
4. 关闭 GUI，再重新打开，确认物品在正确位置

### 放入验证
1. 普通左键点击从玩家背包拿物品（放到手持）
2. 点击 bot 槽位放入
3. **观察**：是否**不再两倍**？

### 回归
G2（36 槽）、G3（装备槽）、G4（主手槽）、G8（任务保护）、G10（无崩溃）、G11（副手槽）

## 填写位置

复测后覆盖 `evidence/latest.log` + `evidence/debug.log`，更新 `evidence/evidence-report.md`。如果仍出现"消失/两倍"，请记录 `[GUI_DEBUG]` 日志和具体操作。完成后告诉我"Bot GUI 第 6 轮复测完毕"。
