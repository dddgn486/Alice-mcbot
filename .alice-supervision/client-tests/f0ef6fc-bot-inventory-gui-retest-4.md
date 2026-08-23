# Bot Inventory GUI 复测说明（第 4 轮）

- 关联提交：`2b995ba`（fix: reuse vanilla inventory menu geometry and slot types）
- 计划 ID：`20260823-bot-inventory-gui-vanilla-reuse-v1`
- 测试人：`user`
- 状态：`NOT_STARTED`（第 4 轮复测）

## 本次修复（源码级重写，复用原版 InventoryMenu 结构）

**前 3 轮失败原因**：自创方案（普通 Slot 绑 armor/offhand、自绘面板、非标准 imageHeight）不符合原版玩家背包结构。

**本次重写**（基于反编译源码）：
1. **armor/offhand 用自定义 Slot 子类**（getMaxStackSize=1、canEquip 检查、绑定诅咒检查）——原版 InventoryMenu$1/$2 的做法
2. **quickMoveStack 按原版分区语义**（保存 original 副本、装备优先移对应 armor/offhand 槽、移动后用 setByPlayer 同步、返回 original）
3. **Screen 背景**：panel 改为 0xFF404040（标题 0xFFFFFF/标签 0x404040 标准对比色，不再同色）
4. **任务保护**：taskActive 时 bot 槽只读

## 复测重点（第 4 轮）

**先重启客户端**（IDEA 运行 `runClient` 加载 `2b995ba`）

### 核心修复验证
1. **拿取物品**：从 bot 拿取物品到玩家背包 → **物品不再消失**，正确落位
2. **放入物品**：从玩家背包放入物品到 bot → **不再显示两倍**
3. **布局**：armor 列 + offhand + main + hotbar 标准对齐（不再稀疏错位）
4. **文字可读**：标题 "Bot Inventory: tango" 完整显示、"物品"标签可见（不再同色）

### 回归确认
G2（36 槽）、G3（装备槽）、G4（主手槽）、G5（堆叠）、G8（任务保护）、G10（无崩溃）、G11（副手槽）

## 关键：请记录槽位状态（确认根因）

源码调研报告标注"拿取消失/放入两倍"的确切根因需**客户端实证**。复测时请：
1. 拿取后，记录 bot 背包和玩家背包的物品数量
2. 放入后，记录两边的物品数量
3. 如果仍出现"消失/两倍"，请在证据报告里说明具体操作和前后数量，方便定位

## 填写位置

复测后覆盖 `evidence/latest.log` + `evidence/debug.log`，更新 `evidence/evidence-report.md`（修改场景结果为复测结果）。完成后告诉我"Bot GUI 第 4 轮复测完毕"。
