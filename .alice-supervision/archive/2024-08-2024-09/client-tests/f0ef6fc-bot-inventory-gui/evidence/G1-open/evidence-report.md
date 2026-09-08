# G1 入口与授权测试报告

> 验证 op 打开 GUI / 非 op 拒绝

## 场景说明

`/alice bot-inventory <botName>` 需要权限等级 2（op）。op 打开正常，非 op 被拒绝。

## 测试工具提示

1. 确认自己是 op（权限等级 2）
2. `/alice bot-inventory tango` → **预期**：GUI 打开
3. 找非 op 玩家（或用非 op 账号）测试 → **预期**：被拒绝

## 本场景引用
- 场景结果：`PASS` / `FAIL` / `UNCLEAR`

## 最少文件清单
- `evidence-report.md`
- 共用日志：`../latest.log`、`../debug.log`

## 场景观察（必填）
1. op 执行 `/alice bot-inventory tango` 是否打开 GUI？`是 / 否`
2. 非 op 执行是否被拒绝（无权限提示）？`是 / 否`（若已测试）
