# R4 非容器端点拒绝测试报告

## 本场景引用
- 总表：`../evidence-report.md`
- 场景结果：`PASS / FAIL / UNCLEAR`

## 场景说明
验证点击泥土等非原版单箱目标时返回稳定错误且不覆盖合法草稿。

## 测试工具提示
- 先建立合法 source/destination，旁边放置泥土。
- 用选择器点击泥土（普通或 Shift），再执行 status；在错误反馈和 status 出现时截图。

## 核心观察
1. 返回 `endpoint_not_single_chest`？`是 / 否`
2. 错误目标未被记录？`是 / 否`
3. 原合法草稿未被覆盖？`是 / 否`
4. 无库存或世界写入？`是 / 否`

## 最少文件清单
- `evidence-report.md`
- `R4-invalid-endpoint.png`
- 共用 `../latest.log`、`../debug.log`

## 异常与停止
- 是否出现停止条件？`是 / 否`
- 说明：[填写]

## 监督员使用区
- 审核状态：待审核
- 审核备注：[留空]
