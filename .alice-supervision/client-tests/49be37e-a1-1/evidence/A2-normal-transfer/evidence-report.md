# A2 正常单批转移测试报告

> 直接填写 `[填写]`；用同目录实际文件覆盖同名日志/图片模板。

## 场景说明

验证唯一允许的单批路径：原版源箱 -> Bot 普通 36 格库存 -> 原版目标箱；不是端点直连写入。

## 测试工具提示

- 准备：同维度且已加载的两个原版普通单箱；源箱仅放 N 个无 NBT 的指定物品，目标箱预留完整容量。
- 命令：`/alice transfer-test <source> <destination> <namespace:item> <N>`，随后用 `/alice transfer-status <request-uuid>` 查询最终状态。
- 截图时机：执行前拍源箱；完成并显示 `VERIFIED` 后拍目标箱。
- 禁止：一次只执行一个 request；不要在完成前改动任一箱子。

## 本场景引用
- 对应总表 request UUID：见 `../evidence-report.md` 的 A2 行
- 场景结果：`PASS` / `FAIL` / `UNCLEAR`

## 最少文件清单
- `evidence-report.md`
- `A2-source-before.png`：源箱执行前
- `A2-destination-after.png`：目标箱完成后
- 共用日志：`../latest.log`、`../debug.log`

## 场景
1. source 是否恰好减少 N？`是 / 否`
2. destination 是否恰好增加 N？`是 / 否`
3. status 最终是否为 `VERIFIED`？`是 / 否`
4. 是否没有自动 retry/resume/额外写入？`是 / 否`
5. 日志是否能关联该 request UUID 和三端 delta？`是 / 否`

## 异常与停止
- 是否出现无法解释的物品 delta、错误端点写入、崩溃或卡死？`是 / 否`
- 说明：[填写]

## 监督员使用区
- 审核状态：待审核
- 审核备注：[留空]
