# Bot Inventory GUI 复测说明（第 3 轮）

- 关联提交：`25b1daa`（fix: render bot inventory panel and sync quick-move source slot）
- 计划 ID：`20260823-bot-inventory-gui-render-fix-v1`
- 测试人：`user`
- 状态：`NOT_STARTED`（第 3 轮复测）

## 本次修复（联网调研确认两个根因）

**问题 1：GUI 面板背景完全不显示**（上次截图：槽位格子悬浮半空）
- 根因：`BotInventoryScreen.render()` 自己调用 `renderBackground()`，但基类也调用 → 自绘面板被半透明遮罩覆盖
- 修复：移除冗余 `renderBackground()` 调用，只重写 `renderBg()` 绘制面板；fill 颜色改用 `0xFFRRGGBB`（明确 alpha）

**问题 2：拿取物品就消失**（上次：从 bot 拿取后物品不见了）
- 根因：`quickMoveStack()` 返回被修改的 stack 且未更新源槽 → 服务端/客户端槽位不一致
- 修复：重写 `quickMoveStack()` 参考原版 `ChestMenu`（保存 original 副本、moveItemStackTo 后同步源槽、返回 original）

## 复测重点（第 3 轮）

**先重启客户端**（IDEA 运行 `runClient` 加载 `25b1daa`）

### 核心修复验证
1. **面板背景**：`/alice bot-inventory tango` → GUI 应**有面板背景/边框**包裹槽位格子（不再是悬浮半空）
2. **拿取物品**：从 bot 拿取物品到玩家背包 → 物品**不再消失**，正确落位

### 回归确认
- G2（36 槽显示）、G3（装备槽）、G4（主手槽对齐）、G5（堆叠）
- G8（任务保护可读禁写）
- G10（无崩溃）
- G11（副手槽）

## 填写位置

复测后覆盖 `evidence/latest.log` + `evidence/debug.log`，更新 `evidence/evidence-report.md`（修改场景结果为复测结果）。完成后告诉我"GUI 第 3 轮复测完毕"。
