# R10 重启失效测试报告

## 本场景引用
- 总表：`../evidence-report.md`
- 场景结果：`PASS / FAIL / UNCLEAR`

## 场景说明
验证选择草稿不持久化，服务器/世界重启后为空且不会自动提交或恢复。

## 测试工具提示
- 选择完整 source/destination 但不要 submit，记录重启前 status。
- 正常退出并重新进入世界/重启服务端；客户端无需关闭，再执行 status。
- 保存重启前后截图和完整日志；不要在重启前后移动箱内物品。

## 核心观察
1. 重启前 status 有完整草稿？`是 / 否`
2. 重启后返回 `selection_missing`？`是 / 否`
3. 重启过程没有自动创建 request？`是 / 否`
4. 源/Bot/目标库存均未变化？`是 / 否`

## 最少文件清单
- `evidence-report.md`
- `R10-before-restart.png`
- `R10-after-restart.png`
- 共用 `../latest.log`、`../debug.log`

## 异常与停止
- 是否出现停止条件？`是 / 否`
- 说明：[填写]

## 监督员使用区
- 审核状态：待审核
- 审核备注：[留空]
