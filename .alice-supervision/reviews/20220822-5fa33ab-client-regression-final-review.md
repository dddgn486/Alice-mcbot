# 5fa33ab Windows 客户端回归最终审核

- Review ID: `20220822-5fa33ab-client-regression-final`
- Commit: `5fa33ab36730eda59a95e9beda182842b0ff430d`
- 服务端修复：`3fcababce01690fd4aad7df6652852dfe2f4b314`
- Verdict: `PASS`
- Stage: `USER_ACCEPTED`

## 证据

- 总表：`.alice-supervision/client-tests/5fa33ab-transfer-selector/evidence/evidence-report.md`
- R2/R3/R4/R9/R10 场景报告、截图。
- R9 独立日志：`.alice-supervision/client-tests/5fa33ab-transfer-selector/evidence/R9-status-clear-retain/latest.log`。
- Windows SHA：`5fa33ab36730eda59a95e9beda182842b0ff430d`。

## 审核结论

用户已澄清 `TransferSelectionData.EXPIRY_TICKS=2400`，因此等待超过期限后的 `selection_missing` 是预期草稿过期，不是随机丢失。R2 source、R3 destination+原版 GUI、R4 非容器拒绝、R10 重启失效均有日志/截图支持。

R9 补证独立日志在 14:42:54/55 记录 source 与 destination accepted，14:43:14 记录 `transfer code=default_item_unavailable`；这证明 submit 失败路径被触发且未创建 request。更新后的 `R9-failure-retains.png` 与场景报告共同证明失败后草稿仍可查看，且无自动 retry。旧总表备注中的“未知原因”已由用户澄清覆盖；总表五个场景均已填写 PASS，用户总体决定为 USER_ACCEPTED。

## 边界

本次只接受转移端点选择器的 E2 修复回归范围 R2/R3/R4/R9/R10。未将其扩大为 A1.1 完整客户端验收，也未替代后续 E5-E8 或 A1-A9。Forge/Mod 版本字段仍未填充，作为记录完整性缺口保留，不影响本轮已验证行为。
