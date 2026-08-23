# V3 Selector 路径测试报告

> 验证 selector 提交路径的同步行为与 transfer-test 一致

## 场景说明

使用 transfer selector（右键箱子选择 source/destination + 提交）转移物品，观察同步行为是否与 V1/V2 的 transfer-test 一致。

## 测试工具提示

- 准备：源箱放 3 个铁锭，右键箱子持有的 selector 物品（如钻石铲）
- 操作：
  1. 右键源箱 → 选择 source
  2. 右键目标箱 → 选择 destination
  3. `/alice transfer-selection submit minecraft:iron_ingot 3`
- 观察：Bot 拿到物品后，立即查看 Bot 的物品栏
- 截图：Bot 物品栏显示铁锭的画面
- 核心观察：selector 路径的同步行为是否与 transfer-test **一致**

## 本场景引用
- 对应总表 request UUID：见 `../evidence-report.md` 的 V3 行
- 场景结果：`PASS` / `FAIL` / `UNCLEAR`

## 最少文件清单
- `evidence-report.md`
- `V3-bot-inventory.png`：Bot 物品栏截图
- 共用日志：`../latest.log`、`../debug.log`

## 场景观察（必填）
1. Selector 提交后，Bot 拿到物品是否**立即可见**？`是 / 否`
2. 同步行为是否与 V1/V2 的 transfer-test **一致**？`是 / 否`
3. 是否没有 selector 路径特有的同步问题？`是 / 否`
