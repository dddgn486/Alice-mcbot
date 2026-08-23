# A8 Abort 与在途物保护测试报告

> 分别测试未抽取请求和 Bot 持有/在途请求；记录两个 request UUID。

## 场景说明

验证 abort 只终止未抽取请求；在途物品不能被 abort 隐藏或清除，必须保持 `SUSPENDED/BOT_INVENTORY/manual_takeover_required`。

## 测试工具提示

- 准备两个独立 request：一个在 source extraction 前，一个已确认 `IN_TRANSIT_BOT`。
- 命令：分别执行 `/alice transfer-abort <request-uuid>`，每次后运行 `/alice transfer-status <request-uuid>`。
- 截图：在途 abort 后捕获 Bot/状态，优先包含 `BOT_INVENTORY` 和 `manual_takeover_required`。
- 禁止：abort 后不得开始 FollowTask、挖矿或其他任务去验证替换；仅记录 status，不对库存做恢复写入。

## 本场景引用
- 未抽取/在途 request UUID：见 `../evidence-report.md` 的 A8 行
- 场景结果：`PASS` / `FAIL` / `UNCLEAR`

## 最少文件清单
- `evidence-report.md`
- `A8-in-transit-after-abort.png`：在途 abort 后 Bot/状态画面
- 共用日志：`../latest.log`、`../debug.log`

## 场景
1. 未抽取请求 abort 后是否为 `ABORTED`？`是 / 否`
2. 在途请求 abort 后是否为 `SUSPENDED`？`是 / 否`
3. 在途 location 是否保持 `BOT_INVENTORY`？`是 / 否`
4. 是否带有 `manual_takeover_required` 且仍阻止任务替换？`是 / 否`
5. abort 是否造成零库存写入？`是 / 否`

## 异常与停止
- 是否出现丢失/重复物品、自动恢复、任务替换、崩溃或卡死？`是 / 否`
- 说明：[填写]

## 监督员使用区
- 审核状态：待审核
- 审核备注：[留空]
