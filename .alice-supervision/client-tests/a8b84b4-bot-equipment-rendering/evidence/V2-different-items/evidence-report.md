# V2 不同物品类型渲染测试报告

> 验证不同物品类型（diamond/stone/pickaxe）的手持模型都能正确渲染

## 场景说明

分别测试不同物品类型的 transfer，观察 Bot 手持模型是否都能正确显示。

## 测试工具提示

- 准备：分别在源箱放入以下物品（每种测试一次）：
  - `minecraft:diamond`（小物品）
  - `minecraft:stone`（方块）
  - `minecraft:diamond_pickaxe`（工具）
- 命令：`/alice transfer-selection submit <item> 1`
- 观察：Bot 搬运途中手持模型
- 截图：至少拍摄一种物品的 Bot 手持模型（推荐 pickaxe，最明显）
- 核心观察：**所有物品类型**都能正确渲染

## 本场景引用
- 对应总表 request UUID：见 `../evidence-report.md` 的 V2 行
- 场景结果：`PASS` / `FAIL` / `UNCLEAR`

## 最少文件清单
- `evidence-report.md`
- `V2-bot-model.png`：Bot 手持不同物品模型截图（至少一种）
- 共用日志：`../latest.log`、`../debug.log`

## 场景观察（必填）
1. diamond（小物品）手持模型是否正确显示？`是 / 否 / 未测试`
2. stone（方块）手持模型是否正确显示？`是 / 否 / 未测试`
3. diamond_pickaxe（工具）手持模型是否正确显示？`是 / 否 / 未测试`
4. 是否所有测试的物品类型都正确渲染？`是 / 否`
