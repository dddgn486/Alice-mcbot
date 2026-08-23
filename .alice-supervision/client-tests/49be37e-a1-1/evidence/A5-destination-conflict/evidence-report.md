# A5 目标端外部变更/冲突测试报告

> 在目标端 freshness/写入窗口改变目标箱；仅在可控世界执行。

## 场景说明

验证 destination pre/freshness 后发生外部变化时，任务保守停止，绝不自动重试、再次 source extraction 或盲目完成插入。

## 测试工具提示

- 准备：源箱与目标箱距离足够远，目标箱预留一个可在 Bot 到达前改动的格位。
- 命令：执行 transfer；在 Bot 从源箱出发后、接近目标箱前，由测试者向目标箱放入或取走一个无关物品。
- 观察：使用 `/alice transfer-status <request-uuid>`，截图目标箱和 Bot 最终物品位置。
- 禁止：冲突出现后不要再次执行相同 request 或手动补齐目标箱；保留原状。

## 本场景引用
- 目标端变更动作：[填写]
- 对应总表 request UUID：见 `../evidence-report.md` 的 A5 行
- 场景结果：`PASS` / `FAIL` / `UNCLEAR`

## 最少文件清单
- `evidence-report.md`
- `A5-result.png`：目标箱和/或 Bot 最终物品位置
- 共用日志：`../latest.log`、`../debug.log`

## 场景
1. 是否记录 `simulation_conflict`、mismatch 或 `UNKNOWN_DISCREPANCY`？`是 / 否`
2. 是否没有自动 retry/resume/finish-insert？`是 / 否`
3. 剩余物品位置是否可由 status/log 解释？`是 / 否`
4. 是否没有再次从 source 取物？`是 / 否`

## 异常与停止
- 是否出现无法解释物品 delta、崩溃或卡死？`是 / 否`
- 说明：[填写]

## 监督员使用区
- 审核状态：待审核
- 审核备注：[留空]
