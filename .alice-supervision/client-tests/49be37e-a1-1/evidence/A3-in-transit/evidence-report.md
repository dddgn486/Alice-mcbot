# A3 在途库存可见性测试报告

> 在 source leg 已完成、destination leg 未完成时记录一次。

## 场景说明

验证 source leg 已证明后物品位置被记录为 Bot 库存，并且客户端可观察到在途状态，而非直接跳到目标箱。

## 测试工具提示

- 准备：让源箱和目标箱相距足够远，便于在 Bot 离开源箱后、抵达目标箱前观察。
- 命令：执行 transfer 后，重复使用 `/alice transfer-status <request-uuid>`；在状态为 `IN_TRANSIT_BOT` 时观察 Bot 库存。
- 截图：同一画面优先包含 status 输出和 Bot 物品栏；若无法同画面，记录 status 时间。
- 禁止：不要通过 abort、危险或改箱子人为结束此 request；这些分别在 A5-A8 测试。

## 本场景引用
- 对应总表 request UUID：见 `../evidence-report.md` 的 A3 行
- 场景结果：`PASS` / `FAIL` / `UNCLEAR`

## 最少文件清单
- `evidence-report.md`
- `A3-bot-in-transit.png`：Bot 库存和/或 status 的在途画面
- 共用日志：`../latest.log`、`../debug.log`

## 场景
1. status 是否为 `IN_TRANSIT_BOT`？`是 / 否`
2. location 是否为 `BOT_INVENTORY`？`是 / 否`
3. Bot 是否可见地持有准确 N 个物品？`是 / 否`
4. source 是否已减少 N 且 destination 尚未增加 N？`是 / 否`
5. 是否没有 GUI 拦截或自动完成？`是 / 否`

## 异常与停止
- 是否出现物品位置无法解释、自动恢复、崩溃或卡死？`是 / 否`
- 说明：[填写]

## 监督员使用区
- 审核状态：待审核
- 审核备注：[留空]
