# E5 同端点与跨维度拒绝测试报告

## 本场景引用
- 总表：`../evidence-report.md`
- 场景结果：`PASS / FAIL / UNCLEAR`

## 场景说明
验证 source=destination 和第二端点跨维度均被精确拒绝，合法草稿不被破坏。

## 测试工具提示
- 主世界准备源/目标箱，另一维度准备原版单箱。
- 分别尝试同一箱两角色和跨维度第二端点；每次拒绝后立即截图并执行 status。
- 不提交 request；两个拒绝场景不要混成同一草稿。

## 核心观察
1. 同端点返回 `same_endpoint_rejected`？`是 / 否`
2. 跨维度返回 `cross_dimension_rejected`？`是 / 否`
3. 拒绝后合法草稿不变？`是 / 否`
4. 没有创建 request？`是 / 否`

## 最少文件清单
- `evidence-report.md`
- `E5-same-endpoint.png`
- `E5-cross-dimension.png`
- 共用 `../latest.log`、`../debug.log`

## 异常与停止
- 是否出现停止条件？`是 / 否`
- 说明：[填写]

## 监督员使用区
- 审核状态：待审核
- 审核备注：[留空]
