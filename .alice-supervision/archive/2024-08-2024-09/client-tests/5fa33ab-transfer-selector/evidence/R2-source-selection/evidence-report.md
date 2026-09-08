# R2 Shift+右键选择源容器测试报告

## 本场景引用
- 总表：`../evidence-report.md`
- 场景结果：`PASS / FAIL / UNCLEAR`

## 场景说明
验证 Shift+右键原版单箱只记录 source 草稿。按用户确认，Shift 为原版潜行交互，GUI 打开**不作为**通过条件。

## 测试工具提示
- OP 手持选择器，准备一个已加载原版单箱。
- Shift+右键源箱，随后执行 `/alice transfer-selection status`。
- 记录日志 `role=source code=accepted` 与 status 坐标；点击前后核对箱内数量。

## 核心观察
1. 日志出现 `role=source code=accepted`？`是 / 否`
2. status 显示正确 source 坐标？`是 / 否`
3. 点击未改变箱内物品？`是 / 否`
4. 未写入任何 ledger/request？`是 / 否`

## 最少文件清单
- `evidence-report.md`
- `R2-source-status.png`
- 共用 `../latest.log`、`../debug.log`

## 异常与停止
- 是否出现停止条件？`是 / 否`
- 说明：[填写]

## 监督员使用区
- 审核状态：待审核
- 审核备注：[留空]
