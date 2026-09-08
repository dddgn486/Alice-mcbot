# E3 右键选择目标容器测试报告

## 本场景引用
- 总表：`../evidence-report.md`
- 场景结果：`PASS / FAIL / UNCLEAR`

## 场景说明
验证普通右键原版单箱只记录 destination 草稿，同时原版箱子 GUI 正常打开。

## 测试工具提示
- 保留 E2 source，准备另一个不同原版单箱。
- 普通右键目标箱，在 GUI 打开时截图，再执行 `/alice transfer-selection status`。
- 核对 source 未变、destination 正确；不提交转移。

## 核心观察
1. 箱子 GUI 正常打开？`是 / 否`
2. 日志出现 `role=destination code=accepted`？`是 / 否`
3. status 同时显示正确 source/destination？`是 / 否`
4. 两箱物品未变化？`是 / 否`

## 最少文件清单
- `evidence-report.md`
- `E3-destination-gui.png`
- `E3-complete-status.png`
- 共用 `../latest.log`、`../debug.log`

## 异常与停止
- 是否出现停止条件？`是 / 否`
- 说明：[填写]

## 监督员使用区
- 审核状态：待审核
- 审核备注：[留空]
