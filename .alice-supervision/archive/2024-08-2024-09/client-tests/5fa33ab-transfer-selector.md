# 5fa33ab 转移选择器右键修复 Windows 回归测试

- Windows 提交：`5fa33ab36730eda59a95e9beda182842b0ff430d`（内容对应已审 `3fcababce01690fd4aad7df6652852dfe2f4b314`）
- 监督审核：`.alice-supervision/reviews/20260822-3fcabab-rightclick-fix-review.md`
- 状态：`NOT_STARTED / CLIENT_TEST_PENDING`

## 背景

`a7e02fd` 首轮测试 E3 失败：普通右键箱子先被方块消费，`useOn` 未执行，destination 无法记录。已修复：选择记录移到服务端 `RightClickBlock` handler，普通右键记录 destination 且原版箱子 GUI 正常打开；Shift+右键记录 source（按用户确认，不要求打开 GUI）。`useOn` 仅返回 `PASS` 防止双写。

## 测试前

1. 在 `D:\JAVA_projects\alice` 更新到新基线：`git pull` 后执行 `git rev-parse HEAD`，必须输出 `5fa33ab36730eda59a95e9beda182842b0ff430d`。
2. 启动客户端，OP 执行 `/give @s alice:transfer_endpoint_selector`。
3. 本回归只验证修复场景 R2/R3/R4/R9/R10；通过后才继续原 E5-E8。

## 场景矩阵

| ID | 场景 | 核心结论 |
|---|---|---|
| R2 | Shift+右键 source | 记录 source，GUI 不作为通过条件 |
| R3 | 普通右键 destination | 记录 destination，且原版箱子 GUI 正常打开 |
| R4 | 非容器拒绝 | 稳定拒绝码，草稿不变 |
| R9 | status/clear/失败保留 | 状态真实、clear 生效、失败保留草稿 |
| R10 | 重启失效 | 重启后草稿为空，无自动提交 |

## 共用停止条件

出现点击即写库存、GUI 被拦截、错误箱子被记录、自动 retry/resume、草稿跨重启恢复、无法解释的 delta、崩溃时立即停止并保留日志。
