# A1 权限与显式命令测试报告

> 直接填写 `[填写]`；用同目录实际文件覆盖同名日志/图片模板。

## 场景说明

验证 `permission level 2` 是 transfer 命令的唯一 bootstrap gate，同时确认参数拒绝不会造成库存变异。

## 测试工具提示

- 准备：两个已加载、同维度、原版普通单箱；源箱放入一种无 NBT 的 `namespace:item`。
- 合法命令：`/alice transfer-test <source> <destination> <namespace:item> <正整数数量>`。
- 非法输入任选一项：不存在 item、`count=0`、非箱子坐标或不同维度端点。
- 截图：聊天栏中同时出现合法 request UUID 与非法拒绝结果。
- 禁止：非法请求被拒绝后不得再次用同一错误输入反复尝试。

## 本场景引用
- 测试者权限等级：[填写]
- 场景结果：`PASS` / `FAIL` / `UNCLEAR`

## 最少文件清单
- `evidence-report.md`
- `A1-command-output.png`：合法命令与一次非法输入的聊天/命令输出
- 共用日志：`../latest.log`、`../debug.log`

## 场景
1. 权限等级 2 执行一次合法 `/alice transfer-test`：是否返回 request UUID？`是 / 否`
2. 使用一次非法 item/count/endpoint 输入：是否被拒绝？`是 / 否`
3. 被拒绝后 source、bot、destination 是否都未变化？`是 / 否`
4. 日志是否有稳定拒绝 code 或命令结果？`是 / 否`

## 异常与停止
- 是否出现物品变化、GUI 拦截、崩溃或卡死？`是 / 否`
- 说明：[填写]

## 监督员使用区
- 审核状态：待审核
- 审核备注：[留空]
