# E8 与坐标命令一致性测试报告

## 本场景引用
- 总表：`../evidence-report.md`
- 场景结果：`PASS / FAIL / UNCLEAR`

## 场景说明
验证选择器 submit 与 `/alice transfer-test` 进入相同 admission 和转移结果语义。

## 测试工具提示
- 为两条路径准备等价但独立的源/目标箱和相同 item/count；每轮 Bot 均空闲。
- 分别执行 selection submit 与坐标 transfer-test，记录 request UUID 和最终状态。
- 不复用已发生变异的箱子作为第二轮初始状态。

## 核心观察
1. 两条路径都输出 request UUID 与 `accepted`？`是 / 否`
2. 最终状态码一致？`是 / 否`
3. 源/Bot/目标 delta 语义一致？`是 / 否`
4. selector 没有绕过 ledger/TransferTask？`是 / 否`

## 最少文件清单
- `evidence-report.md`
- `E8-selection-result.png`
- `E8-coordinate-result.png`
- 共用 `../latest.log`、`../debug.log`

## 异常与停止
- 是否出现停止条件？`是 / 否`
- 说明：[填写]

## 监督员使用区
- 审核状态：待审核
- 审核备注：[留空]
