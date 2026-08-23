# G9 空闲交互测试报告

> 验证 Bot 空闲时 GUI 完整交互（拿取/放入/Shift 移动）

## 场景说明

Bot 空闲（无任务）时，打开 bot-inventory GUI。预期：可以完整交互——拿取 bot 物品到玩家背包、放入物品到 bot、Shift+点击快速移动。

## 测试工具提示

1. 确认 Bot 空闲（无任务）：`/alice status`
2. 打开 GUI：`/alice bot-inventory tango`
3. 尝试：
   - 从 bot 槽位拿取物品到玩家背包
   - 从玩家背包放入物品到 bot
   - Shift+点击快速移动
4. **核心观察**：空闲时是否完整交互？

## 本场景引用
- 场景结果：`PASS` / `FAIL` / `UNCLEAR`

## 最少文件清单
- `evidence-report.md`
- 共用日志：`../latest.log`、`../debug.log`
- 截图（可选）：拿取/放入操作画面

## 场景观察（必填）
1. 能从 bot 槽位拿取物品到玩家背包？`是 / 否`
2. 能从玩家背包放入物品到 bot？`是 / 否`
3. Shift+点击快速移动是否正常？`是 / 否`
4. 操作后 bot inventory 是否正确变更？`是 / 否`
