# a7e02fd 转移端点选择器客户端失败审核

- Review ID: `20260822-a7e02fd-client-failure`
- Reviewed commit: `a7e02fd63ced80cb7bdf6591aa6c30d418e4c657`
- Client evidence: Windows `.alice-supervision/client-tests/a7e02fd-transfer-selector/evidence/`
- User result: `FAIL / NEEDS_FIX`
- Verdict: `BLOCK_FURTHER_EXPANSION`
- Stage: `CLIENT_TEST_FAILED / NEEDS_USER_DECISION`

## 已确认事实

1. E1：独立木棍外观与 OP 获得通过；非 OP 因单客户端限制未测试。
2. E2：Shift+右键确实记录 source；日志多次出现 `role=source code=accepted`，status 显示正确 source；但 GUI 未打开。
3. E3：普通右键 GUI 正常打开，但日志中完全没有 `role=destination`；status 始终 `destination=null`。因此 destination 选择入口失败。
4. E4：非容器点击返回 `endpoint_not_single_chest`，非法目标未覆盖草稿，行为通过。
5. E5-E10 未继续；没有 request UUID、没有 submit、没有已报告库存变异。停止是正确的。

## 根因

Minecraft/Forge 右键方块交互中，普通右键原版箱子会由箱子方块先消费并打开 GUI，物品 `Item.useOn` 不一定被调用；当前 destination 记录只位于 `TransferEndpointSelector.useOn`，因此普通右键箱子无法记录 destination。

Shift+右键属于原版 secondary-use/crouch 语义，通常绕过箱子方块交互，因此“记录 source 的同时必须打开 GUI”不是用户原始需求，也不是可靠的原版行为。E2 的 GUI PASS 条件属于测试设计错误。

## 最小修复方向（尚未授权实现）

- 使用独立服务端 `PlayerInteractEvent.RightClickBlock` handler，在方块交互被消费前识别 `alice:transfer_endpoint_selector` 并记录 source/destination；不 cancel 事件、不写库存、不提交任务。
- 普通右键：记录 destination，随后允许原版箱子 GUI 打开。
- Shift+右键：记录 source，保留原版潜行右键语义（不要求 GUI 打开）。
- 移除或禁用 Item.useOn 的重复记录路径，避免同一次点击双写草稿；事件 handler 仍复用 `ChestEndpointRef.validate`、permission 2、server-scoped draft 和稳定码。
- A1.1 transfer admission、ledger、primitive、Task、HARD_PATH 均不得改动。

## 状态

- `a7e02fd` 客户端验收失败，不得标记 USER_ACCEPTED。
- active plan 转为 `CLIENT_TEST_FAILED / NEEDS_USER_DECISION`。
- 用户确认 Shift 交互语义后，才能批准窄修复工作包；修复后只回归 E1-E4 和必要的 E9/E10，再继续 E5-E10。
