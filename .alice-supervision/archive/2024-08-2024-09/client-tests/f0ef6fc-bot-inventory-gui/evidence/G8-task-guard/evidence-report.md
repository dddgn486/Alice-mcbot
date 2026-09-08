# G8 任务保护测试报告（核心）

> 验证 Bot 执行任务时 GUI 只读可看、禁写

## 场景说明

Bot 正在执行任务（如 mine/transfer）时，打开 bot-inventory GUI。预期：
- **可以打开查看**（能看物品）
- **点击 bot 槽位不生效**（物品不移动，inventory 不变）
- 关闭 GUI 后任务正常完成（未被破坏）

## 测试工具提示

1. 让 Bot 执行任务：`/alice mine 10 stone`（或 transfer）
2. 任务进行中，打开 GUI：`/alice bot-inventory tango`
3. 观察：GUI 是否能打开并显示物品
4. 尝试点击 bot 的某槽位（拿取/放入）
5. 观察：物品是否移动？
6. 关闭 GUI，确认任务正常完成
7. **核心观察**：任务保护是否生效（可读 + 禁写）

## 本场景引用
- 场景结果：`PASS` / `FAIL` / `UNCLEAR`
- 任务类型：mine / transfer / 其他

## 最少文件清单
- `evidence-report.md`
- 共用日志：`../latest.log`、`../debug.log`
- 截图（可选）：GUI 打开画面

## 场景观察（必填）
1. 任务执行中，GUI 是否能打开并显示物品？`是 / 否`
2. 点击 bot 槽位时，物品是否不移动（禁写生效）？`是 / 否`
3. 关闭 GUI 后，任务是否正常完成（未被破坏）？`是 / 否`
4. 是否出现 inventory 异常变更或任务失败？`是 / 否`
