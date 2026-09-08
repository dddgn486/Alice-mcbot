# E7 显式参数与世界漂移测试报告

## 本场景引用
- 总表：`../evidence-report.md`
- 场景结果：`PASS / FAIL / UNCLEAR`

## 场景说明
验证显式 item/count 优先；选择后端点失效时按既有校验失败，不隐式重选。

## 测试工具提示
- 准备两轮独立草稿：一轮显式数量，一轮提交前移除/替换目标箱。
- 第一轮执行 `/alice transfer-selection submit minecraft:<item> <count>`；另试一个非 `minecraft:` ID。
- 第二轮端点失效后 submit，立即截图失败和 status；不要对已变异 request 重试。

## 核心观察
1. 显式 item/count 按指定数量转移？`是 / 否`
2. 非 `minecraft:` item 返回 `invalid_item_id`？`是 / 否`
3. 失效端点没有被隐式替换？`是 / 否`
4. 失败提交保留草稿？`是 / 否`

## 最少文件清单
- `evidence-report.md`
- `E7-explicit-result.png`
- `E7-drift-failure.png`
- 共用 `../latest.log`、`../debug.log`

## 异常与停止
- 是否出现停止条件？`是 / 否`
- 说明：[填写]

## 监督员使用区
- 审核状态：待审核
- 审核备注：[留空]
