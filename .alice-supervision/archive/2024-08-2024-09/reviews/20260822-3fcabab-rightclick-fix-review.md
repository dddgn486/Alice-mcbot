# Alice Supervisor Review

- Review ID: `20260822-3fcabab-rightclick-fix`
- Reviewed commit: `3fcababce01690fd4aad7df6652852dfe2f4b314`
- Plan ID: `20260822-transfer-selector-rightclick-fix-v1`
- Verdict: `PASS`
- Stage status: `CLIENT_TEST_PENDING`

## Evidence Reviewed

- `84e12b6..3fcabab` 实际 diff、pending 包和当前 HEAD。
- `src/main/java/com/dddgn/alice/item/TransferEndpointSelectorEvents.java`。
- `src/main/java/com/dddgn/alice/item/TransferEndpointSelectorEventsFixture.java`。
- `run/logs/latest.log` 2026-08-22 04:44:51：`TRANSFER_SELECTOR_EVENT_FIXTURE_SUITE PASS` 与既有 `TRANSFER_SELECTION_FIXTURE_SUITE PASS`。
- 独立执行 `./gradlew compileJava`：PASS；`git diff --check`：PASS。

## Architecture Review

修复保持在 GUI 交互入口层。Forge `PlayerInteractEvent.RightClickBlock` 服务端 handler 使用 event hand 匹配选择器，permission level 2 后复用 `ChestEndpointRef.validate` 和 `TransferSelectionData.select`；secondary-use 记录 source，普通右键记录 destination。handler 不 cancel、不 override result，普通箱子交互仍交给原版 GUI。`useOn` 只返回 `PASS`，不再重复记录。

生产路径未修改 TransferRequest、admission、ledger、primitive、TransferTask、BotManager、HARD_PATH、SOFT_SURFACE 或普通任务链；无 NBT、packet/UI、主动 openMenu、持久化或默认 item/count 变更。

## Verification Review

补强后的 `dispatch` seam 由生产 handler 实际调用。fixture 通过可观察计数验证：普通右键 destination 一次、Shift source 一次、非 selector hand 计数为零、拒绝码路径不增加计数、non-cancel policy 保持 true。该证据仍不能替代真实 Forge 事件排序和 Windows GUI 行为，因此只提升至 `CLIENT_TEST_PENDING`。

## Client Regression Required

用户需在 Windows 新基线 `3fcababce01690fd4aad7df6652852dfe2f4b314` 回归：

- E2：Shift+右键 source，GUI 不作为通过条件；确认 source/status/log。
- E3：普通右键 destination，必须同时确认 destination/status/log 和原版箱子 GUI 打开。
- E4：非容器稳定拒绝且草稿不变。
- E9：status/clear；提交失败保留草稿。
- E10：服务器重启后草稿为空。

E5-E8 只有上述回归通过后继续；原失败证据保留，不覆盖。

## Next State

`3fcabab`：服务端 `PASS -> CLIENT_TEST_PENDING`。Windows origin 同步成功后才允许用户回归；不得标记 `USER_ACCEPTED`，直到用户提交新 SHA 的客户端证据并明确确认。
