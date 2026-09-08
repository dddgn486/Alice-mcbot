# E7 ResourceLocation 参数解析最小修复规划

- 规划角色：Alice 实现规划员+
- 审查基线：`5fa33ab36730eda59a95e9beda182842b0ff430d`
- 当前工作树计划：`20260822-transfer-selector-item-argument-v1`
- 当前计划状态：`NEEDS_REPLAN`
- 工作会话门禁：`./tools/work-session-start.sh` 已执行，但因 active plan 非 `APPROVED_FOR_IMPLEMENTATION` 以退出码 1 拒绝启动。
- 用户客户端事实：E5/E6 PASS；R2/R3/R4/R9/R10 `USER_ACCEPTED`；E7 被 `mekanism:ingot_steel` 在 Brigadier 参数阶段拒绝，尚未到达 resolver 的 `invalid_item_id`。

> **本规划不构成实现授权。** 当前只允许监督员审核本报告；在计划恢复为 `APPROVED_FOR_IMPLEMENTATION` 前不得修改代码、运行实现验证或要求客户端重测。

## 结论

最小修复是把**selector submit 的 item 参数**从 `StringArgumentType.word()` 替换为 `ResourceLocationArgument.id()`，并用 `ResourceLocationArgument.getId(ctx, "item")` 取值。该类型在固定 Forge/Minecraft 1.20.1-47.4.10、Parchment 2023.09.03 映射 jar 中已核实存在：

```text
net.minecraft.commands.arguments.ResourceLocationArgument
static id()
static getId(CommandContext<CommandSourceStack>, String)
```

建议只替换 selector submit 路径（`BotCommand.java:118-123`）；保持 `/alice transfer-test` 的 `StringArgumentType.word()` 与其现有 parse/getter 语义不变（`BotCommand.java:101-110`）。这样严格满足当前 active plan 的“只修 E7 item argument parsing，不改变旧命令语义”范围。

## 已确认事实

1. active plan 明确目标是让 selector submit 的显式 ResourceLocation 可解析并验证 E7，且禁止扩大到 transfer admission/ledger/task/BotManager（`.alice-supervision/active-plan.md:10-16`）。门禁状态 `NEEDS_REPLAN`，因此目前没有实现授权。
2. selector submit 注册为 `submit`、可省略参数、可提供 `item`、可继续提供 `count`；当前 item 节点使用 `StringArgumentType.word()`（`src/main/java/com/dddgn/alice/command/BotCommand.java:114-123`）。``StringArgumentType.word()` 在 Brigadier 中按 token 解析，不接受未引号的 `:`，故 `mekanism:ingot_steel` 在 resolver 前失败。
3. `/alice transfer-test` 的 item 节点也使用 `StringArgumentType.word()`（`BotCommand.java:101-110`），执行时以 `StringArgumentType.getString(ctx, "item")` 传给 `transfer(...)`；该方法再自行 `ResourceLocation.tryParse(rawItem)` 并检查注册表（`BotCommand.java:154-170`）。这个命令是 A1.1 原始坐标入口，当前计划要求保持既有语义。
4. selector submit 当前执行阶段已经接受 `String rawItem`，随后 `ResourceLocation.tryParse(rawItem)`，再交给 `TransferSelectionSubmission.resolve(...)`（`BotCommand.java:190-203`）。因此参数类型替换不应改 resolver、TransferRequest、ChestEndpointRef、primitive、ledger、Task 或 admission。
5. 固定映射 API 已通过只读 `javap` 核验，来源 jar 为：`/home/fb486/.gradle/caches/forge_gradle/minecraft_user_repo/net/minecraftforge/forge/1.20.1-47.4.10_mapped_parchment_2023.09.03-1.20.1/forge-1.20.1-47.4.10_mapped_parchment_2023.09.03-1.20.1.jar`。该类提供 `id()`、`getId(CommandContext, String)` 与 `parse(StringReader)`。
6. 现有客户端最终审核明确 E7 是命令解析阻塞，而不是 resolver 或 transfer 语义失败；`20220822-5fa33ab-client-regression-final-review.md:16-24` 记录已接受范围不包括 E7，当前 active plan 仅处理该窄问题。

## 最小代码线路（仅供批准后实施）

### Selector submit

在 `src/main/java/com/dddgn/alice/command/BotCommand.java:118-123`：

- import `net.minecraft.commands.arguments.ResourceLocationArgument`；
- 将 `Commands.argument("item", StringArgumentType.word())` 替换为 `Commands.argument("item", ResourceLocationArgument.id())`；
- 将该节点的 getter 从 `StringArgumentType.getString(ctx, "item")` 改为 `ResourceLocationArgument.getId(ctx, "item").toString()`，继续传入现有 `transferSelectionSubmit(CommandSourceStack, String, Integer)`；
- `count` 节点和无参/仅 item 的分支保持原样；
- 不改 `TransferSelectionSubmission.resolve()` 的输入/输出，不改 `invalid_item_id` 逻辑。

保留 String 形式传入既有方法的优点是 diff 最小，避免把本包扩大成 resolver API 重构。也可以把内部方法改为接收 `ResourceLocation`，但这不是 E7 所需，增加了非必要触及面，拒绝作为最小路线。

### `/alice transfer-test` 兼容性评估

建议**不改** `BotCommand.java:104` 的旧命令参数类型和 `StringArgumentType.getString` getter：

- 该命令当前用 `String` 经 `ResourceLocation.tryParse`，已有 `minecraft:` 形态不能到达业务层，但这不是 active plan E7 目标；
- 计划明确“preserve `/alice transfer-test` behavior”，只修 selector submit 可避免旧入口的 parse tree、错误文本、tab-completion 或命令调用契约变化；
- `/alice transfer-test` 的 `transfer(...)` 仍是显式坐标入口，不能因 selector 的用户体验修复而变成新语义。

若监督员另行决定把两条命令统一为 ResourceLocation 参数，必须扩展 active plan 并单独覆盖旧命令的 parse/getter/错误行为；不属于本报告推荐线路。

## 解析结果矩阵

| 输入 | selector submit 参数阶段 | resolver / 业务结果 |
|---|---|---|
| `minecraft:iron_ingot` | `ResourceLocationArgument.id()` 接受 | 若已注册且满足默认无 NBT/组件规则，继续正常解析；否则由既有 resolver 返回对应稳定码 |
| `mekanism:ingot_steel` | 参数语法接受，冒号不再被 Brigadier 截断 | 到达 `TransferSelectionSubmission.resolve()`；在当前 selector 政策下返回 `invalid_item_id`（不应改为 parse failure） |
| `bad:item:id`、空 namespace、非法字符等 | `ResourceLocationArgument.id()` 抛 Brigadier `CommandSyntaxException` | 解析失败，不进入 resolver；这是预期的非法 token parse failure，不应伪装成 `invalid_item_id` |
| `minecraft:`、`:iron_ingot`、含空格 token | `ResourceLocationArgument.id()` 拒绝 | Brigadier parse failure，不进入 resolver |
| selector 无 item 参数 | 维持当前无参默认 resolver 路径 | 不改变计划中默认 item/count 与 `default_item_unavailable` 语义 |
| `/alice transfer-test ... minecraft:iron_ingot ...` | 在推荐线路下仍按旧 `word()` 解析，冒号输入仍可能 parse failure | 旧命令行为保持不变；不将其混写成 E7 的 resolver 结果 |

注：`mekanism:ingot_steel` 的“返回 `invalid_item_id`”前提是其 token 本身符合 ResourceLocation 语法、但在 Alice 当前 selector 的允许/注册策略中无效；它不能在 parse 阶段被拒绝。若实现当前 resolver 实际还采用 namespace 白名单，fixture 应以代码真实稳定码为准，禁止为了 E7 强行把 mod namespace 当作 parse 错误。

## 验证证据与回归要求

### 必须增加的 focused fixture

需要一个窄的 Brigadier parse/execute fixture（可扩展现有 command/selection fixture，但不得启动 transfer admission）：

1. 构造真实 `BotCommand` 注册树或等价固定 dispatcher；
2. selector submit 的 `minecraft:iron_ingot` 能完成 parse，并断言进入 resolver 的输入为 `minecraft:iron_ingot`；
3. `mekanism:ingot_steel` 能完成 parse，断言 resolver 返回 `invalid_item_id`，而不是 dispatcher/Brigadier parse failure；
4. 非法 token（至少 `bad:item:id` 与 `minecraft:`）在 Brigadier parse 阶段失败，且 resolver 未被调用；
5. 无参 selector submit 仍能走原有默认 resolver；仅 item 与 item+count 两个分支的 getter 均覆盖；
6. 旧 `/alice transfer-test` 至少做回归断言：命令树仍存在、参数顺序/count 校验未变、方法仍使用旧 String getter；若不改该节点，不要求其支持带冒号 item。

Fixture 必须是 command parse/resolve 层测试，不写 chest/bot inventory，不调用 `assignTransfer`，不修改 ledger/Task/BotManager。现有 server-side fixture 已明确未覆盖 Brigadier parsing/permission（`docs/HANDOVER.md:70`），因此不能将既有 `TRANSFER_SELECTION_FIXTURE_SUITE PASS` 当作 E7 证据。

### 必须执行的代码验证

- 计划恢复批准后，至少运行 `./gradlew compileJava`；
- 运行 focused fixture，记录每个输入的 parse/resolve 结果与稳定码；
- 不把 broad selftest 的外部 `143` 或 headless PASS 当作客户端验收；
- 推送到 Windows origin 后，用户只需重测 E7（并确认已接受 R2/R3/R4/R9/R10 无回退）。建议补充一条 E7 场景证据：`mekanism:ingot_steel 16` 到达 resolver 并显示 `invalid_item_id`，另保留一个合法 `minecraft:` 输入证明冒号解析路径已打通。

## 风险与停止条件

### 风险

- **错误地同时修改旧命令**：会扩大范围并可能改变 `/alice transfer-test` 的 Brigadier tree、错误阶段或兼容行为；默认禁止。
- **只把冒号当作普通字符串放宽**：会让非法 token 越过语法层，削弱 `ResourceLocationArgument` 的稳定 parse contract；必须使用官方参数类型。
- **getter 类型误配**：`ResourceLocationArgument.getId` 返回 `ResourceLocation`，若仍按 String getter 使用会编译失败或产生隐式错误；应显式 `.toString()` 或最小化改内部签名。
- **把 mod namespace 误判为语法错误**：`mekanism:ingot_steel` 语法合法，必须让 resolver 负责 `invalid_item_id`；不要在 Brigadier 参数层加入 Alice 业务 registry 检查。
- **误触发 transfer**：fixture 和验证都不应执行 admission；修复只影响 parse/getter。
- **客户端证据不足**：编译和 focused fixture 只能证明 server command layer；E7 仍须 Windows 实测，不能标 USER_ACCEPTED。

### 停止条件

- active plan 仍为 `NEEDS_REPLAN` 或门禁再次拒绝；
- 需要修改 `TransferSelectionSubmission`、`TransferRequest`、`ChestEndpointRef`、primitive、ledger、Task、BotManager 或 transfer admission；
- 需要改变 `/alice transfer-test` 语义、参数顺序、默认行为或错误码；
- ResourceLocation 参数在固定 Forge/Parchment API 中无法编译，需另行调查而非退回 `StringType` 放宽；
- 需要客户端 packet/UI、物品选择状态、GUI 事件或扩大到 E8/完整 A1-A9；
- focused fixture 与客户端观察冲突，或合法 token 仍在 parser 阶段被拒。

## 最小修正清单与验收门

1. 监督员审核本报告并将 active plan 从 `NEEDS_REPLAN` 重新规划为仅 E7 参数解析修复的 `APPROVED_FOR_IMPLEMENTATION`；
2. 只改 `BotCommand.java:118-123` 的 selector submit item argument 类型及 getter，旧坐标命令 `BotCommand.java:101-110` 不改；
3. 增加/运行 focused command parse fixture，覆盖合法 `minecraft:`, 合法但业务拒绝 `mekanism:`, 非法 token parse failure，以及旧 transfer-test 树回归；
4. `compileJava` + focused fixture 通过，生成新的审核包；
5. Windows 仅回归 E7：确认 `mekanism:ingot_steel 16` 显示 `invalid_item_id`，并确认 selector 原有 USER_ACCEPTED 场景无回退。

当前结论：**路线可行，但实现被 active plan 门禁阻止；本报告不授权实现。**
