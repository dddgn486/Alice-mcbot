# 5fa33ab Windows 客户端回归审核

- Review ID: `20260822-5fa33ab-client-regression`
- Commit: `5fa33ab36730eda59a95e9beda182842b0ff430d`
- 对应服务端修复：`3fcababce01690fd4aad7df6652852dfe2f4b314`
- Verdict: `CONDITIONAL_PASS / USER_ACCEPTANCE_BLOCKED`
- Stage: `CLIENT_TEST_PENDING`

## 已核实通过

日志 `latest.log` 证明入口修复真实生效：

- R2 source：12:58:01 `role=source code=accepted`，随后 status 显示 source。
- R3 destination：13:01:17 `role=destination code=accepted`；13:01:23 status 同时显示 source/destination。用户报告普通箱子 GUI 打开。
- R4：13:03:23 等多次非容器点击返回 `endpoint_not_single_chest`。
- R9 status/clear：13:10:21 `transfer_selection: cleared`，随后 `selection_missing`。
- R10：重启/重载后出现 `selection_missing`，没有自动 request 或库存变异证据。

因此原始 E3 destination 失败已修复，普通右键 destination 与原版 GUI 场景可视为通过。

## 证据缺口与矛盾

1. 总表第 1-3 行用户备注写明“多次未知原因 status 丢失，下一轮修复前需定位”；但第 14-15 行同时填写 `PASS`、`USER_ACCEPTED`，场景汇总仍为占位 `PASS / FAIL / UNCLEAR`。不能将自相矛盾的总体决定视为有效用户接受。
2. 代码 `TransferSelectionData.EXPIRY_TICKS=2400`，`active()` 在 `tick - selectedTick > EXPIRY_TICKS` 时主动删除草稿并返回空。日志中部分 `selection_missing` 与选择相隔约 2,400 ticks，可能是预期过期，不足以证明随机丢失；但备注没有给出每次操作/间隔，无法排除异常。
3. R9 报告声称“提交失败后草稿仍存在”，但共用日志没有 `/alice transfer-selection submit`、失败 request、admission 或失败后 status 的可审计记录。只能确认 status/clear，不能验收 failure-retain。
4. Forge/Mod 版本在总表仍为空；场景时间和日志关键字未填写，降低可追溯性。

## 结论

- 右键入口修复：客户端事实支持 `PASS`。
- R2/R3/R4/R10：有日志支持，但 R10 的重启边界需按场景报告补充明确时间点。
- R9 failure-retain：证据不足，保持未验收。
- 总包：`CONDITIONAL_PASS`，不得标记 `USER_ACCEPTED`，不得扩展 E5-E8 或 A1.1。

## 最小下一步

用户补充一轮短回归：

1. 新建/清空草稿后立即执行 status，记录 selectedTick 与现实时间。
2. 在明确少于 2,400 ticks 的时间内执行一次确定失败 submit，保存 submit 反馈和随后 status；证明失败保留 draft。
3. 另做一次超过 2,400 ticks 的 status，明确标记为预期 `selection_missing`，与“未知丢失”区分。
4. 把总表五个场景填写为实际 `PASS/FAIL/UNCLEAR`，移除矛盾的 `USER_ACCEPTED`，补 Forge/Mod 版本与日志时间。

在此补证完成前，阶段保持 `CLIENT_TEST_PENDING`。
