# Bot Inventory GUI 复测说明（第 8 轮 - view index 修复）

- 关联提交：`f7dc5e9`（fix: align bot inventory screen with snapshot slot indices）
- 计划 ID：`20260824-bot-inventory-gui-view-index-fix-v1`
- 测试人：`user`
- 状态：`NOT_STARTED`（第 8 轮复测）

## 本次修复（直接命中 slot_empty 根因）

**根因**（审核第 7 轮日志确认）：
- 服务端快照 index：0-35 普通槽、36-39 armor、40 offhand
- 客户端 Screen 原来把 index 画成"armor(0-3)+offhand(4)+main(5-31)+hotbar(32-40)"——**与快照完全错位**
- 你点击视觉上有物品的格子 → 客户端发错 index → 服务端读空 → `slot_empty`

**修复**：重写 `slotX/slotY/hitTest`，客户端 index 与服务端快照一致：
- 0-8 hotbar、9-35 main（普通槽）
- 36-39 armor 列、40 offhand

## 复测重点（第 8 轮）

**先重启客户端**（IDEA 运行 `runClient` 加载 `f7dc5e9`）

### 核心验证（这次应该不再 slot_empty）
1. `/alice bot-inventory tango` 打开 GUI
2. **点击视觉上有物品的格子** → 应该正常拿取（不再 `slot_empty`）
3. 拿取到玩家背包 → **不消失**
4. 从玩家背包放物品到 bot → **不翻倍**
5. Shift 快速移动 → 正常
6. 装备穿戴（armor 36-39 / offhand 40）→ 正常

### 观察日志
- 服务端日志应显示 `bot_inv: action ... act=PICKUP slot=N code=accepted`（而非 slot_empty）
- 如仍 `slot_empty`，记录是哪个 slot 号，帮我定位

### 回归
G1（op 入口）、G2（36 槽显示）、G11（无崩溃）

## 填写位置

复测后覆盖 `evidence/latest.log` + `evidence/debug.log`，更新 `evidence/evidence-report.md`。完成后告诉我"第 8 轮复测完毕"，并附上 `bot_inv: action` 日志片段。
