# E9 状态、清理与失败保留测试报告

## 本场景引用
- 总表：`../evidence-report.md`
- 场景结果：`PASS / FAIL / UNCLEAR`

## 场景说明
验证 status 反映真实草稿、clear 清空草稿、提交失败保留草稿。

## 测试工具提示
- 准备合法草稿；让 Bot 不可用或目标容量不足制造确定失败。
- 依次执行 status、clear、status；重选后执行失败 submit，再执行 status。
- 在 cleared、selection_missing 和失败保留状态出现时截图；不得用异常世界改动制造失败。

## 核心观察
1. status 显示真实 source/destination？`是 / 否`
2. clear 后返回 `selection_missing`？`是 / 否`
3. 日志出现 `transfer_selection: cleared`？`是 / 否`
4. 提交失败后草稿仍存在？`是 / 否`
5. 失败没有自动重试？`是 / 否`

## 最少文件清单
- `evidence-report.md`
- `E9-status-clear.png`
- `E9-failure-retains.png`
- 共用 `../latest.log`、`../debug.log`

## 异常与停止
- 是否出现停止条件？`是 / 否`
- 说明：[填写]

## 监督员使用区
- 审核状态：待审核
- 审核备注：[留空]
