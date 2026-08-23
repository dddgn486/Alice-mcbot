# Bot Inventory GUI 复测说明（第 5 轮）

- 关联提交：`55e3d32`（fix: use inventory.png background and add GUI debug logs）
- 计划 ID：`20260824-bot-inventory-gui-final-fix-v1`
- 测试人：`user`
- 状态：`NOT_STARTED`（第 5 轮复测）

## 本次改动（关键：加了诊断日志来定位根因）

1. **背景改用 inventory.png**：bot 背包段用 vanilla 玩家背包背景（不再自绘错位）
2. **添加 `[GUI_DEBUG]` 诊断日志**：每次拿取/放入操作，服务端日志会记录槽位前后状态——这是为了**实证定位"拿取消失/放入两倍"的确切根因**
3. **索引核对**：armor 槽（0-3）、offhand（4）、BOT_SLOT_CAP（41）确认一致

## ⚠️ 本次复测最重要的事：采集 [GUI_DEBUG] 日志

源码调研多次强调"拿取消失/放入两倍"的根因**必须靠客户端实证**。所以本次复测请：

1. **先重启客户端**（IDEA 运行 `runClient` 加载 `55e3d32`）
2. `/alice bot-inventory tango` 打开 GUI
3. **执行拿取操作**（从 bot 拿物品到玩家背包）——观察物品是否消失
4. **执行放入操作**（从玩家背包放物品到 bot）——观察是否变两倍
5. **把 `run/logs/latest.log` 中的 `[GUI_DEBUG]` 日志截图或复制给我**

## 核心观察

- **面板背景**：bot 背包段应为标准 vanilla 背包背景（不再松散错位）
- **标题/标签可读**："Bot Inventory: tango" 完整、"物品"标签可见
- **拿取/放入**：记录实际结果（是否消失/两倍）

## 填写位置

复测后覆盖 `evidence/latest.log` + `evidence/debug.log`，更新 `evidence/evidence-report.md`。**特别请把 `[GUI_DEBUG]` 日志内容反馈给我**（这是定位根因的关键证据）。完成后告诉我"Bot GUI 第 5 轮复测完毕"。
