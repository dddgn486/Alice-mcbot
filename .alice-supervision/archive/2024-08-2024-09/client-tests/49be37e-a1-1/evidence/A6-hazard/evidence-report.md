# A6 生存危险中断测试报告

> 在转移移动阶段触发一次已知危险；不尝试自行恢复。

## 场景说明

验证生存危险会把未完成请求切换为需人工接管的 `SUSPENDED`，而非继续执行库存写入。

## 测试工具提示

- 仅使用当前已批准且可控的危险方式；先确认不会波及其他测试箱子或普通任务。
- 在 Bot 处于移动阶段时触发一次危险，随后立刻运行 `/alice transfer-status <request-uuid>`。
- 记录触发方式、status 的 code/location 和是否停止移动/写入。
- 禁止：不要使用未批准的世界编辑、传送或强制任务替换来制造中断。

## 本场景引用
- 危险类型：[填写]
- 对应总表 request UUID：见 `../evidence-report.md` 的 A6 行
- 场景结果：`PASS` / `FAIL` / `UNCLEAR`

## 最少文件清单
- `evidence-report.md`
- 共用日志：`../latest.log`、`../debug.log`

## 场景
1. 任务是否进入 `SUSPENDED`？`是 / 否`
2. `code` 是否为稳定 survival/hazard code？`是 / 否`
3. location 是否与已证明物品位置一致？`是 / 否`
4. 是否没有自动 retry/resume/finish-insert？`是 / 否`
5. 是否没有额外库存写入？`是 / 否`

## 异常与停止
- 是否出现无法解释物品 delta、任务替换、崩溃或卡死？`是 / 否`
- 说明：[填写]

## 监督员使用区
- 审核状态：待审核
- 审核备注：[留空]
