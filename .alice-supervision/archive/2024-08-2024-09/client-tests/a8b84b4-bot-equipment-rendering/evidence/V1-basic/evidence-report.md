# V1 基本功能测试报告

> 验证 Bot 搬运物品途中手持铁锭模型可见

## 场景说明

使用 transfer selector 或 transfer-test 命令转移 3 个铁锭，观察 Bot 从源箱拿到物品后、移动到目标箱途中，**Bot 手上是否显示铁锭模型**。

## 测试工具提示

- 准备：源箱放 3 个铁锭，目标箱为空
- 命令：`/alice transfer-selection submit minecraft:iron_ingot 3`（或 transfer-test）
- **关键观察时机**：Bot 到达源箱后、开始移动到目标箱途中（约 3-5 秒窗口）
- **观察角度**：从侧面或正面观察 Bot，能清楚看到手持位置
- 截图：Bot 手持铁锭模型的画面（模型可能是 3D 或扁平纹理）
- 核心观察：**修复前**（bd9f0c3）Bot 手上是空的，**修复后（预期）**Bot 手上显示铁锭模型

## 本场景引用
- 对应总表 request UUID：见 `../evidence-report.md` 的 V1 行
- 场景结果：`PASS` / `FAIL` / `UNCLEAR`

## 最少文件清单
- `evidence-report.md`
- `V1-bot-model.png`：Bot 手持铁锭模型截图
- 共用日志：`../latest.log`、`../debug.log`

## 场景观察（必填）
1. Bot 搬运途中，手上是否显示铁锭模型（不是空手）？`是 / 否`
2. 铁锭模型是否清晰可见（不是黑色方块或错误纹理）？`是 / 否`
3. 是否没有崩溃、卡死或模型显示错误？`是 / 否`
