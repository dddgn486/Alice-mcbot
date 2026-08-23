# E1 物品身份与权限测试报告

## 本场景引用
- 总表：`../evidence-report.md`
- 场景结果：`PASS / FAIL / UNCLEAR`

## 场景说明
验证选择器是独立木棍外观 Alice 物品，OP 可获得，非 OP 点击和命令均被拒绝；不验证实际转移。

## 测试工具提示
- 准备一名 OP 与一名非 OP；OP 执行 `/give @s alice:transfer_endpoint_selector`。
- 对比选择器和指南针软路径工具；非 OP 点击原版单箱并尝试 `/alice transfer-selection status`。
- 截图物品身份与拒绝反馈。异常时停止，不重复操作覆盖首次结果。

## 核心观察
1. 名称为“转移端点选择器”且木棍外观可区分？`是 / 否`
2. OP 可获得物品？`是 / 否`
3. 非 OP 点击返回 `unauthorized_actor` 且无草稿？`是 / 否`
4. 非 OP 无法执行命令？`是 / 否`

## 最少文件清单
- `evidence-report.md`
- `E1-item-identity.png`
- `E1-unauthorized.png`
- 共用 `../latest.log`、`../debug.log`

## 异常与停止
- 是否出现停止条件？`是 / 否`
- 说明：[填写]

## 监督员使用区
- 审核状态：待审核
- 审核备注：[留空]
