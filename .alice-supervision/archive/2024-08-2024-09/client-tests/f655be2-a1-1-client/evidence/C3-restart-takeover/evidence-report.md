# C3 重启与人工接管测试报告

> 在 active 或在途请求期间停止并重启服务端；不要尝试继续该请求。

## 场景说明

验证服务器在活跃/在途请求中重启后只保留人工接管事实，不恢复、重试或补写转移。

## 测试工具提示

- 准备：源箱与目标箱相距足够远。
- 命令：`/alice transfer-test <源x> <源y> <源z> <目标x> <目标y> <目标z> minecraft:iron_ingot 3`
- 操作：先通过 `/alice transfer-status <request-uuid>` 确认活跃或 `IN_TRANSIT_BOT` 状态；正常停止并重启服务端；保持客户端会话，无需重启客户端。
- 重启后：使用 `/alice transfer-status <request-uuid>`，记录 state/code/location 和 Bot/箱子物品位置。
- 禁止：不要执行 resume、再次 transfer 或手动插入来完成原请求。

## 本场景引用
- 重启前状态/物品位置：[填写]
- 对应总表 request UUID：见 `../evidence-report.md` 的 C3 行
- 场景结果：`PASS` / `FAIL` / `UNCLEAR`

## 最少文件清单
- `evidence-report.md`
- 共用日志：`../latest.log`、`../debug.log`

> 本场景仅重启服务端，不要求重启客户端；同一轮完整日志覆盖重启前后。

## 场景
1. 重启后请求是否为 `SUSPENDED`？`是 / 否`
2. 是否带有 `manual_takeover_required`？`是 / 否`
3. location 是否仍是已证明的位置？`是 / 否`
4. 是否没有自动恢复/重试/补写？`是 / 否`
