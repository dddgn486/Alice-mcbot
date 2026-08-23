# a7e02fd 转移端点选择器 Windows 客户端测试

- 关联提交：`a7e02fd63ced80cb7bdf6591aa6c30d418e4c657`
- 计划：`20260822-transfer-endpoint-selector-v1`
- 监督审核：`.alice-supervision/reviews/20260822-a7e02fd-transfer-selector-review.md`
- 状态：`NOT_STARTED / CLIENT_TEST_PENDING`

## 使用方式

在同一轮 Windows 客户端会话连续完成 E1-E9；E10 只重启服务器/重新进入世界，不要求关闭客户端。测试前确认 `git rev-parse HEAD` 为上列 SHA。根目录总表单只填写一次，并用本轮完整日志覆盖 `evidence/latest.log`、`evidence/debug.log`。每个场景只填写对应中文表单并保存要求的截图。

普通管理员可用 `/give @s alice:transfer_endpoint_selector` 获得木棍外观的独立选择器。Shift+右键原版单箱选择 source，普通右键原版单箱选择 destination；两次点击都应正常打开箱子 GUI。提交命令支持：

- `/alice transfer-selection submit`：默认源箱按槽位第一种无 NBT 原版物品 + 该物品全部数量；
- `/alice transfer-selection submit minecraft:<item>`：显式物品 + 默认该物品全部数量；
- `/alice transfer-selection submit minecraft:<item> <count>`：显式物品与正数量。

## 场景矩阵

| ID | 场景 | 核心结论 |
|---|---|---|
| E1 | 物品身份与权限 | 独立木棍贴图；非 OP 点击/命令均拒绝 |
| E2 | Shift+右键 source | source 写入草稿且箱子 GUI 正常打开 |
| E3 | 右键 destination | destination 写入草稿且箱子 GUI 正常打开 |
| E4 | 非容器拒绝 | 泥土等目标返回稳定码且草稿不变 |
| E5 | 同端点/跨维度 | 精确拒绝且不覆盖合法草稿 |
| E6 | 无参默认提交 | 第一种物品的全部数量走既有 admission 完成 |
| E7 | 显式参数与世界漂移 | 显式 item/count 生效；失效端点不被隐式重选 |
| E8 | 与坐标命令一致 | 两入口进入相同 transfer admission/结果码 |
| E9 | status/clear/失败保留 | 状态真实，clear 生效，提交失败保留草稿 |
| E10 | 重启失效 | 重启后草稿为空，无自动提交/恢复 |

## 共用停止条件

出现点击即写库存、GUI 被拦截、非 OP 可选择/提交、错误箱子被采用、默认值移动多种物品、自动 retry/resume、草稿跨重启恢复、无法解释的物品 delta、崩溃或卡死时立即停止。保留完整日志和对应截图，不重试已发生库存变异的同一 request。

## 填写位置

测试日期、Windows SHA、Bot、默认/显式 item/count、request UUID、E1-E10 汇总、异常和用户总体决定只填写 `evidence/evidence-report.md`；本文件不重复填写。
