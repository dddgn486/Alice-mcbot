# V2 多槽位同步测试报告

> 验证转移多个物品时所有槽位实时同步

## 场景说明

使用 `/alice transfer-test` 命令转移较多物品（如 10 个铁锭），观察 Bot 拿到物品后是否**所有槽位都实时同步**。

## 测试工具提示

- 准备：源箱放 10 个铁锭（会占用 Bot 多个槽位）
- 命令：`/alice transfer-test <源x> <源y> <源z> <目标x> <目标y> <目标z> minecraft:iron_ingot 10`
- 观察：Bot 从源箱拿到物品后，立即查看 Bot 的物品栏
- 截图：Bot 物品栏显示多个槽位有铁锭的画面
- 核心观察：是否**所有槽位**都实时可见（不只是第一个槽位）

## 本场景引用
- 对应总表 request UUID：见 `../evidence-report.md` 的 V2 行
- 场景结果：`PASS` / `FAIL` / `UNCLEAR`

## 最少文件清单
- `evidence-report.md`
- `V2-bot-inventory.png`：Bot 物品栏截图（多个槽位）
- 共用日志：`../latest.log`、`../debug.log`

## 场景观察（必填）
1. Bot 拿到多个物品后，是否**所有槽位**都实时可见？`是 / 否`
2. Bot 物品栏中物品总数是否正确（10 个铁锭）？`是 / 否`
3. 是否没有部分槽位不可见的情况？`是 / 否`
