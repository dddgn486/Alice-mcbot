# A7 重启与人工接管测试报告

> 在 active 或在途请求期间停止并重启服务端；不要尝试继续该请求。

## 场景说明

验证服务器在活跃/在途请求中重启后只保留人工接管事实，不恢复、重试或补写转移。

## 测试工具提示

- 准备：源箱与目标箱相距足够远，先通过 status 确认活跃或 `IN_TRANSIT_BOT` 状态。
- 操作：正常停止并重启服务端；保持客户端会话，无需重启客户端。
- 重启后：使用 `/alice transfer-status <request-uuid>`，记录 state/code/location 和 Bot/箱子物品位置。
- 禁止：不要执行 resume、再次 transfer 或手动插入来完成原请求。

## 本场景引用
- 重启前状态/物品位置：[填写]
- 对应总表 request UUID：见 `../evidence-report.md` 的 A7 行
- 场景结果：`PASS` / `FAIL` / `UNCLEAR`

## 最少文件清单
- `evidence-report.md`
- 共用日志：`../latest.log`、`../debug.log`

> 本场景仅重启服务端，不要求重启客户端；同一轮完整日志覆盖重启前后。

## 场景
1. 重启后请求是否为 `SUSPENDED`？`是 / 否`
2. 是否带有 `manual_takeover_required`？`是 / 否`
3. location 是否仍是已证明的位置？`是 / 否`
4. 是否没有自动 resume/retry/finish-insert？`是 / 否`
5. 是否没有重启后额外库存写入？`是 / 否`

## 异常与停止
- 是否出现无法解释物品 delta、崩溃或卡死？`是 / 否`
- 说明：[填写]

## 监督员使用区
- 审核状态：待审核
- 审核备注：[留空]
