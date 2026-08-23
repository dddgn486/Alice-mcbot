# V1 基本功能测试报告

> 验证 Bot 拿到物品后立即在客户端可见（不需重启）

## 场景说明

使用 `/alice transfer-test` 命令转移少量物品（如 3 个铁锭），观察 Bot 拿到物品后是否**立即在客户端可见**。

## 测试工具提示

- 准备：源箱与目标箱距离适中，源箱放 3 个铁锭
- 命令：`/alice transfer-test <源x> <源y> <源z> <目标x> <目标y> <目标z> minecraft:iron_ingot 3`
- 观察：Bot 从源箱拿到物品后，**不要重启客户端**，直接查看 Bot 的物品栏（按 E 键，点击 Bot 名字）
- 截图：Bot 物品栏显示铁锭的画面
- 核心观察：物品是否**立即可见**（修复前需要重启，修复后应该立即可见）

## 本场景引用
- 对应总表 request UUID：见 `../evidence-report.md` 的 V1 行
- 场景结果：`PASS` / `FAIL` / `UNCLEAR`

## 最少文件清单
- `evidence-report.md`
- `V1-bot-inventory.png`：Bot 物品栏截图
- 共用日志：`../latest.log`、`../debug.log`

## 场景观察（必填）
1. Bot 拿到物品后，是否**不需重启**就能在客户端看到？`是 / 否`
2. Bot 物品栏中物品数量是否正确（3 个铁锭）？`是 / 否`
3. 是否没有崩溃、卡死或物品消失？`是 / 否`
