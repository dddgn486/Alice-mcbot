# E7 item 参数解析修复二审（370ff343）

- 审核人：Alice 架构监督员
- 审核日期：2026-08-22
- 受审提交：`370ff34323597f6c75f0532173300bc765357154`（amend 自 `cab2552`）
- 前序提交：`cab25521d0e5d2fd31afac278bec40b4d225a8af`（条件不通过后由 958446e8 修正）
- Active plan：`20220822-transfer-selector-item-argument-v1`，状态 APPROVED_FOR_IMPLEMENTATION
- 基线：`5fa33ab36730eda59a95e9beda182842b0ff430d`
- 审核包：`.alice-supervision/pending/370ff34.md`

## 结论：PASS（服务端侧）+ E7 客户端回归 PASS + E8 客户端回归 PASS

生产修复与验证均满足计划范围。用户已于 2026-08-22 21:03-21:05 在 Windows 完成 E7 客户端回归（基线 `f655be2`），证据见 `.alice-supervision/client-tests/a7e02fd-transfer-selector/evidence/latest.log:142-167`：
- 非 `minecraft:` item → `transfer code=invalid_item_id`（`latest.log:153`，21:05:00）；
- 合法 `minecraft:` item → request `15cc8aae` accepted → `TransferTask COMPLETED code=done`（`latest.log:159,167`，21:05:22-23）。

E8 客户端回归（21:24-21:36）也已 PASS：
- selection submit 路径 request `324c537b` → `COMPLETED done`（`latest.log:149,154`）；
- 坐标 transfer-test 路径 request `190ff741` → `COMPLETED done` + `VERIFIED transfer_verified source=0 bot=-3 destination=3`（`latest.log:195,202,206`）；
- 中途两次失败为坐标书写错误的正确拒绝（`endpoint_not_single_chest`/`hard_path_unreachable`），用户聊天记录佐证（`latest.log:171,179`），非实现缺陷。

E7 参数解析阻塞与 E8 一致性场景均已关闭。active plan 状态为 USER_ACCEPTED，selector 场景全部关闭。

## 依据

1. **参数修复正确**（`src/main/java/com/dddgn/alice/command/BotCommand.java`）：
   - `transfer-selection submit` 的 item 参数改用 `ResourceLocationArgument.id()`，item-only 与 item+count 两分支均用 `ResourceLocationArgument.getId(ctx, "item").toString()`；
   - `/alice transfer-test` 保持原 `StringArgumentType.word()` 与 String getter，语义不变；
   - 未触碰 resolver、admission、ledger、TransferTask、BotManager、权限、GUI、packet、NBT。

2. **fixture 修正正确**（`TransferSelectionCommandParseFixture.java`）：
   - 断言 `minecraft:iron_ingot`、`mekanism:ingot_steel` 完整消费解析；
   - 断言 `bad:item:id`、含空格 token 被拒绝；
   - `minecraft:` 与 `:iron_ingot` 按固定 Forge 1.20.1 API 实测记录（`emptyPathByApi=minecraft:`、`emptyNamespaceByApi=minecraft:iron_ingot`，即 `:iron_ingot` 被规范化），不再错误断言 parser 拒绝；
   - mekanism 等非 `minecraft:` namespace 的业务拒绝仍由既有 `TransferSelectionSubmission.resolve` 的 `invalid_item_id` 负责（`TransferSelectionSubmission.java:43-45`），fixture 不调用 transfer 层。

3. **验证证据**：
   - `./gradlew compileJava` PASS；`git diff --check` PASS；
   - focused 2026-08-22 20:48:31：`TRANSFER_SELECTION_COMMAND_PARSE_FIXTURE_SUITE PASS`（vanilla=true modSyntax=true resolverPolicy=true badColon=true whitespace=true）；既有 TRANSFER/SELECTION/EVENT fixtures 均 PASS；
   - broad selftest 240s 外部终止 exit 143，未作为 parser 或客户端证据；
   - HANDOVER 已准确记录限制与下一步（`.alice-supervision/pending/370ff34.md`）。

## 边界核对

- 未修改挖矿/拾取 HARD_PATH、SOFT_SURFACE、道路、隧道、流体、逃生等冻结边界；
- 未把实验能力接入普通任务链；
- 客户端实测仍未完成：E7 的 `invalid_item_id` 与合法 `minecraft:` 无回退需 Windows 回归验证，本次 PASS 不代表用户验收。

## 下一步

1. 监督员完成最终确认后，将 `370ff343` 同步到 Windows origin（stash/push/pop 保留治理改动）；
2. 用户执行 E7 客户端回归：`/alice transfer-selection submit mekanism:ingot_steel 16` 预期 `invalid_item_id`；`/alice transfer-selection submit minecraft:iron_ingot 1` 预期正常路径；
3. E7 客户端证据收齐后再标记场景结果。
