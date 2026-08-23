# 5fa33ab Windows 客户端回归复审

- Review ID: `20220822-5fa33ab-client-regression-rerun`
- Commit: `5fa33ab36730eda59a95e9beda182842b0ff430d`
- User clarification: `EXPIRY_TICKS=2400` 导致的 status `selection_missing` 属于预期过期行为
- Verdict: `CONDITIONAL_PASS`
- Stage: `CLIENT_TEST_PENDING`

## 采纳的修正

用户已解释 status 丢失来自 2,400 ticks 草稿过期，且场景汇总已填写为 R2/R3/R4/R9/R10 全部 PASS。日志中的 `selection_missing` 与等待、clear、重启场景相符；不再把该现象认定为实现随机丢失。

## 已核实

- R2：`12:58:01` source accepted，status 正确。
- R3：`13:01:17` destination accepted，`13:01:23` status 同时包含 source/destination；用户报告 GUI 打开，核心修复成立。
- R4：非容器返回 `endpoint_not_single_chest`，合法草稿未被错误覆盖。
- R9：status/clear 路径有日志：`13:10:21` cleared，随后 selection_missing。
- R10：重启后 selection_missing，无自动 request 或库存变异证据。

## 尚未闭合的唯一证据项

R9 报告第 19 行声称“提交失败后草稿仍存在”，但共用 `latest.log` 没有 `transfer-selection submit`、失败 request/admission、失败后 status 的可审计序列。该观察无法仅由截图和报告文字独立核验。

## 结论

- 右键入口修复及 R2/R3/R4/R10：通过。
- `selection_missing`：按用户说明视为预期过期/clear/重启结果，不要求修复。
- R9 failure-retain：待补一段短证据。
- 整体：`CONDITIONAL_PASS`，暂不接受 `USER_ACCEPTED`，不影响已验证的入口修复。

## 最小补证

在草稿刚建立后、明确少于 2,400 ticks 内：

1. 制造确定失败的 `/alice transfer-selection submit ...`；
2. 保存 chat/log 中失败 code 或 request 结果；
3. 立即执行 `/alice transfer-selection status`，保存显示草稿仍存在的截图和日志；
4. 将 R9 报告与总表的时间/关键字补齐。

补证只涉及 R9，不需要重跑 R2/R3/R4/R10，也不改变 active plan 的 CLIENT_TEST_PENDING 状态。
