# E6 无参默认提交测试报告

## 本场景引用
- 总表：`../evidence-report.md`
- 场景结果：`PASS / FAIL / UNCLEAR`

## 场景说明
验证无参 submit 选取源箱按槽位顺序第一种无 NBT 原版物品，并移动该种物品的全部数量；不移动后续物品种类。

## 测试工具提示
- 源箱按槽位放两种物品，第一种分布多个槽；目标容量充足，Bot 空闲。
- 选择两端并截图源箱，执行 `/alice transfer-selection submit`；完成后截图目标箱和 request 结果。
- 不在提交后手工移动物品；若出现异常 delta 立即停止，不重试该 request。

## 核心观察
1. 默认 item 是槽位顺序第一种可转移物品？`是 / 否`
2. 默认 count 是该物品在源箱总数？`是 / 否`
3. 第二种物品完全未移动？`是 / 否`
4. request 最终 `transfer_verified`？`是 / 否`
5. 成功后 status 为空？`是 / 否`

## 最少文件清单
- `evidence-report.md`
- `E6-source-before.png`
- `E6-destination-after.png`
- `E6-request-result.png`
- 共用 `../latest.log`、`../debug.log`

## 异常与停止
- 是否出现停止条件？`是 / 否`
- 说明：[填写]

## 监督员使用区
- 审核状态：待审核
- 审核备注：[留空]
