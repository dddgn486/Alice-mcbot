# E2 Shift+右键选择源容器测试报告

## 本场景引用
- 总表：`../evidence-report.md`
- 场景结果：`PASS / FAIL / UNCLEAR`

## 场景说明
验证 Shift+右键原版单箱只记录 source 草稿，同时原版箱子 GUI 正常打开；不提交转移。

## 测试工具提示
- OP 手持选择器，准备一个已加载原版单箱。
- Shift+右键源箱，在 GUI 打开时截图，再执行 `/alice transfer-selection status`。
- 不执行 submit；点击前后核对箱内数量。异常时停止，不连续重试。

## 核心观察
1. 箱子 GUI 正常打开？`是 / 否`
2. 日志出现 `role=source code=accepted`？`是 / 否`
3. status 显示正确 source？`是 / 否`
4. 点击未改变箱内物品？`是 / 否`

## 最少文件清单
- `evidence-report.md`
- `E2-source-gui.png`
- `E2-source-status.png`
- 共用 `../latest.log`、`../debug.log`

## 异常与停止
- 是否出现停止条件？`是 / 否`
- 说明：[填写]

## 监督员使用区
- 审核状态：待审核
- 审核备注：[留空]
