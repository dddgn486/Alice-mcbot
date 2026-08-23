# Interface C1 Maintenance F1-F4 Planner Review

- 日期：2026-08-21
- 角色：Alice 实现规划员（只读路线复核）
- Git 基线：`ba63d15b7dec9bf4887f099bd45e407af5e99a19`
- 前序计划：`20260821-interface-readonly-snapshot-v1`，状态 `USER_ACCEPTED`
- 候选计划：`20260821-interface-c1-maintenance-f1-f4-v1`，当前 `NEEDS_USER_DECISION`
- 证据基线：`.alice-supervision/reviews/20260821-8bb2d7b.md`、`.alice-supervision/research/interface-c1-maintenance-f1-f4-shallow-20260821.md`

> **本规划不构成实现授权。** 本报告仅供监督员复核；不得据此修改 `active-plan.md`、派发开发任务或向用户宣告客户端验收。

## 1. 结论摘要

F1-F4 可以继续作为一个最小、隔离的 maintenance 包，前提是监督员先采纳本报告并取得用户对候选计划 Allowed/Forbidden Scope 与验证矩阵的明确决定，再将计划状态转换为 `APPROVED_FOR_IMPLEMENTATION`。当前没有证据需要深度调查；但也没有任何理由扩大到 C2+ 或将历史旧存档卡死并入该包。

建议采纳监督员草案的总体边界和停止条件，并作三项精确修正：

1. F2 的“保守 unknown”必须覆盖所有未加载分支，且 `hasChunkAt(pos)` 发生在 `getBlockState` 与 `getBlockEntity` 之前；应避免为产生 block id 而访问目标世界状态。
2. F3 的事实校准应以当前真实 HEAD `ba63d15`、已接受的 S1-S4 证据和“旧存档卡死未归因、未修复”三者为准。当前 `docs/SUPERVISOR_HANDOFF.md` 已记录该事实，不应按草案旧文字重复改写成 `NEEDS_FIX` 或未验收状态。
3. F1 的断言必须在同一 `runInterfaceSnapshotRegression` 中形成单一可见 PASS/FAIL，并只使用现有 chest/dirt fixture；不要为了测试 fluids/energy 引入新机器或新运行时 fixture。

明确确认：该路线不能扩大到 C2+、库存转移、端点/侧面语义、机器写入、GUI 自动化、LLM、Adapter、任务/移动、挖矿/拾取/道路/隧道/流体/逃生，亦不能诊断或修复历史旧存档卡死。

## 2. 已确认事实

### F1：捕获后不可变与 formatter 纯度尚未有运行时断言

`BotSelftest.runInterfaceSnapshotRegression` 当前位于 `src/main/java/com/dddgn/alice/bot/BotSelftest.java:349-371`。它创建箱子、写入 slot 0 的钻石、捕获快照，然后只断言状态、方块 ID、27 槽和初始数量；没有调用 `InterfaceScanner.format`，没有在捕获后改变箱子再检查快照，也没有尝试修改快照列表。评审 `20260821-8bb2d7b.md:11-19` 已将此列为下一功能包前的 required 条件。

`InterfaceSnapshot` 在 `src/main/java/com/dddgn/alice/capability/InterfaceSnapshot.java:21-28` 使用 `BlockPos.immutable()` 和 `List.copyOf`，当前结构支持窄回归断言；这不是 schema 重设计任务。

### F2：未加载判断顺序不满足保守语义

`InterfaceScanner.capture` 位于 `src/main/java/com/dddgn/alice/capability/InterfaceScanner.java:41-67`。当前第 43 行先执行 `level.getBlockState(pos)` 构造 `blockName`，第 45 行才调用 `hasChunkAt(pos)`。这与评审 `20260821-8bb2d7b.md:21-29` 及浅调研第 15-16、26-27 行要求的顺序相冲突。未加载返回不得为了生成方块 ID 而读取目标状态；应使用显式保守常量（例如 `unknown`），并在返回前不访问目标 block entity。

### F3：文档事实必须保持与当前基线一致

当前 Git HEAD 和 `origin/master` 均为 `ba63d15`；提交历史显示 `8bb2d7b` 是业务实现，`f9d89da`/`276339f`/`ba63d15` 是监督治理文档 checkpoint。`docs/HANDOVER.md:4-19, 42-50` 已明确记录当前基线、C1 S1-S4 的 `USER_ACCEPTED`、F1-F4 open，以及旧存档卡死仅为未归因观察。`docs/SUPERVISOR_HANDOFF.md:6-13, 15-32, 46-56` 仍需与当前事实保持一致；其当前文字包含旧 checkpoint 的历史状态，不能被误读为当前实现基线或授权状态。

必须保留：正常进入旧 superflat 只表示本轮未复现，不表示历史卡死已修复；扫描器客户端验收不扩展为 C2+ 或历史问题结论。

### F4：旧泛化 heuristic 无调用，但 live formatter 已是 raw index

`InterfaceScanner.java:155-193, 316-337` 的 `scanItems`、`scanEnergy`、`scanFluid`、`slotHint` 在源码全局搜索中仅互相引用，没有其他调用者；它们实现了旧的输入/输出/能量槽位 heuristic。当前实际 formatter 在 `InterfaceScanner.java:85-105` 只输出 raw slot/tank index 与事实，且 `107-109` 明确把 Mek projection 标成非 C1 通用事实。可删除的范围应严格限于这四个无调用方法及对应过时 class Javadoc，不应删除 live `scanMek` 投影或改变 snapshot schema。

### 客户端与架构边界

已接受证据 `.alice-supervision/client-tests/8bb2d7b.md:85-91` 将接受范围限定为独立扫描器身份、只读箱子扫描、无 block entity 扫描和原版钻石铲隔离；不包含 C2/C3/C4、转移、endpoint semantics、机器写入或旧存档卡死。协议 `docs/SUPERVISION_PROTOCOL.md:11-18, 67, 79-96` 以及路线图 `docs/PRODUCT_ARCHITECTURE_ROADMAP.md:151-164, 261-271` 要求维持 C1/C2 分界。

## 3. 最小实现路线与文件级步骤

以下是建议给监督员采用的顺序。每一步都可单独审查、回滚；实现者只能在用户批准且 active plan 为 `APPROVED_FOR_IMPLEMENTATION` 后执行。

1. **F2：先修 capture 的未加载分支**
   - 文件：`src/main/java/com/dddgn/alice/capability/InterfaceScanner.java`。
   - 将 `level.hasChunkAt(pos)` 放在目标 `getBlockState(pos)` 和 `getBlockEntity(pos)` 之前。
   - 未加载时构造 `CHUNK_NOT_LOADED` 快照，`blockId` 使用 `unknown`（或计划批准的同等常量），不读取目标状态/实体；维度、位置、tick 等非目标世界事实可保留。
   - 已加载路径保持现有 C1 capability 捕获和 legacy Mek projection 语义不变。

2. **F4：删除死的泛化 heuristic 代码并校正注释**
   - 同一文件：删除 `scanItems`、`scanEnergy`、`scanFluid`、`slotHint` 及只服务于它们的旧 Javadoc 描述。
   - 将类级说明改为 C1 raw unsided readonly facts；明确 `legacyProjection` 是 Mek-specific、非通用 C1 事实。
   - 不触碰 `captureItems/captureEnergy/captureFluids`、`format`、`scanMek`、Mek 依赖或任何 capability 写/侧面访问。

3. **F1：扩展既有 focused selftest**
   - 文件：`src/main/java/com/dddgn/alice/bot/BotSelftest.java`，仅改 `runInterfaceSnapshotRegression`。
   - 使用现有箱子 fixture：捕获后调用 `InterfaceScanner.format(chestSnapshot)`，并以捕获前后箱子内容/快照事实相等证明 formatter 不改变来源。
   - 捕获后修改箱子 slot 0，再断言 `chestSnapshot` 仍为 diamond×3；不要将后续捕获结果误当成旧快照。
   - 对 `items`（以及 fixture 中实际存在的 fluids 若无 fixture 则不新增 fixture）尝试变更并断言抛出 `UnsupportedOperationException`；所有新增断言必须汇总进同一 `INTERFACE_SNAPSHOT_SELFTEST PASS|FAIL`。
   - 注意：不要求用反射或测试私有方法；不为覆盖 energy/fluid 构造新的模组机器。

4. **F3：校准交接文档事实**
   - 文件：`docs/HANDOVER.md`、`docs/SUPERVISOR_HANDOFF.md`，仅修正当前 HEAD、accepted C1、F1-F4 open、工具身份/历史限制等事实。
   - 保留旧存档卡死的时间线与“未归因、未修复、复发时先取完整日志/线程转储”的限制。
   - 不把 compile/headless/selftest PASS 写成客户端 `USER_ACCEPTED`；不把治理文档提交混成业务实现事实。

5. **验证与交付审查**
   - 代码 diff 必须确认没有超出四个源码/文档文件的范围（若监督员批准的计划列出同等必要文档，则以 active plan 为准）。
   - 执行 `./gradlew compileJava`。
   - 执行 `./gradlew runServer -Dalice.selftest.auto=true`，从 `run/logs/latest.log` 提取 focused `INTERFACE_SNAPSHOT_SELFTEST PASS`；已有整套 smoke 的外部超时/exit 143 不能报告为全套通过。
   - 静态审查 `hasChunkAt` 顺序、无调用方法移除、raw index formatter 和文档事实。
   - 提交前核对 Git/HANDOVER，之后由开发员按协议提交、push 并生成审核包；本规划不授权这些动作。

## 4. 依赖关系

```text
用户批准候选 F1-F4 路线
        |
        v
监督员更新 active-plan -> APPROVED_FOR_IMPLEMENTATION
        |
        v
F2 capture 顺序/保守 unloaded
        |
        +--> F4 删除 dead heuristic/修注释
        |
        +--> F1 selftest 断言（可与 F4 并行，但同文件变更时建议顺序执行）
        |
        +--> F3 文档事实校准（依赖最终实现事实）
        |
        v
compileJava + focused headless + diff/doc audit
        |
        v
监督员 review；若发现客户端可见交互变化，转 CLIENT_TEST_PENDING
        |
        v
下一功能包仍需另行 review/用户决定；不可自动释放 C2+
```

更严格的提交顺序建议为：F2/F4/F1 同一实现包内按上述顺序完成；F3 在代码与验证事实稳定后最后编辑，避免 handover 再次过时。F2、F4、F1 之间不存在产品语义依赖，但共享 `InterfaceScanner`/selftest 文件，串行可降低冲突与回滚成本。

## 5. 主要风险与缓解

- **未加载访问风险**：`hasChunkAt` 之前的任何目标状态读取都可能破坏“不加载”假设。缓解：先分支；unknown 仅用常量；禁止新增加载/生成 fixture。停止条件：证明该 API 仍会隐式加载或需要扩大到世界加载策略。
- **测试 fixture 扩大风险**：为断言 fluid/energy 而引入 Mek 机器或 capability mock 会把 C1 维护扩大到版本适配。缓解：仅使用现有 chest/dirt，列表不可变性断言以实际存在的 item list 为主。停止条件：没有不扩范围的可重复 fixture。
- **formatter 纯度误证风险**：只比较字符串不能证明来源状态未变。缓解：调用 format 前后比较 chest slot/快照字段，并在捕获后改源容器检查旧 snapshot。
- **dead-code 误删风险**：全局搜索显示四个方法无外部调用，但实现前仍须再次搜索并检查 diff；若出现 live caller，停止并回报监督员。
- **文档漂移风险**：`HEAD`、origin、accepted evidence 和历史 hang 容易混写。缓解：以 Git 与固定 evidence 路径逐项核对；任何冲突停止，不自行裁决。
- **客户端门禁误判风险**：F2/F4/F1/F3 预期不改变 scanner item interaction contract，但不能把 headless 或 compile 当客户端验收。缓解：保留上一包 S1-S4 `USER_ACCEPTED` 的窄范围；任何新交互/日志协议变化停止并新建 client matrix。
- **架构越界风险**：删除 heuristic 不得演变为 endpoint role、transfer 或 adapter 设计。缓解：按 C1 raw facts 和 R38 分界审查；确认不能扩大到 C2+。

## 6. 停止条件

立即停止并交还监督员，满足任一条件：

1. 用户未明确批准候选计划，或 active plan 仍为 `NEEDS_USER_DECISION`/其他非 `APPROVED_FOR_IMPLEMENTATION`。
2. 需要修改 `active-plan.md`、扩大 Allowed Scope、跨入冻结边界，或必须派发未完成深度调查。
3. `hasChunkAt` 之前的安全访问无法在现有 API 下实现，或要求加载/生成 chunk 才能验证。
4. F1 需要新机器、流体/能量 fixture、mock capability 或 schema 改动。
5. F4 删除前发现任一旧方法有 live caller，或注释修正会改变运行时语义。
6. 文档事实与 Git、client evidence、用户决定互相冲突，尤其不能解释为旧存档卡死已修复。
7. 编译失败、focused selftest 未输出明确 PASS、异常被吞掉，或全局 smoke 仅以 timeout/143 结束。
8. 观察到 scanner 物品交互、GUI、世界状态、日志契约或其他客户端可见行为发生变化；此时暂停并要求新的客户端矩阵。
9. 任何改动触及 C2+、读写/转移、endpoint/side semantics、机器写、LLM、Adapter、MineTask、DropCollectionTask、道路、隧道、流体、FollowTask、HARD_PATH 或 SOFT_SURFACE。

## 7. 验收矩阵

| 检查项 | 证据 | 客户端是否需要 | 通过条件 | 不能宣称 |
|---|---|---:|---|---|
| Git 基线/范围 | `git rev-parse HEAD`、diff、文件清单 | 否 | 仅 F1-F4 批准文件有改动，基线可追溯 | 不能把 bus/文档状态替代提交事实 |
| F2 顺序 | `InterfaceScanner.capture` 源码/diff | 否 | `hasChunkAt` 先于目标 state/entity 读取；未加载 block id 为 unknown | 不能宣称完整运行时未加载加载证明，除非有专用证据 |
| F4 清理 | 全局调用搜索 + diff | 否 | 四个死方法及 heuristic Javadoc 消失；live formatter 仍只报 raw index | 不能宣称 C2 endpoint role 兼容 |
| F1 compile | `./gradlew compileJava` 输出 | 否 | `BUILD SUCCESSFUL` | 不能宣称客户端通过 |
| F1 focused headless | `run/logs/latest.log` | 否 | `INTERFACE_SNAPSHOT_SELFTEST PASS` 且包含 formatter purity、post-capture stability、unmodifiable list 断言结果 | 不能宣称完整 smoke 或 energy/fluid runtime 覆盖 |
| 全局 smoke（若运行） | 完整日志和退出原因 | 否 | 仅如实际完成才可称全套通过 | exit 143/外部 timeout 不是 PASS |
| F3 文档一致性 | `HANDOVER.md`、`SUPERVISOR_HANDOFF.md` 与 Git/evidence 对照 | 否 | HEAD/status/tool identity/limitations 全部真实 | 不能宣称旧 hang 已解决 |
| 客户端 S1-S4 既有证据 | `.alice-supervision/client-tests/8bb2d7b.md` 及 evidence | 是（已完成） | 只引用既有 `USER_ACCEPTED` 窄范围 | 不能扩大到新语义或 C2+ |
| 新客户端回归 | 仅当交互/日志可见契约改变时 | 条件需要 | 新矩阵由监督员发布并由用户填写 | 规划员不能标记 `USER_ACCEPTED` |
| 架构边界 | diff + review checklist | 否 | 无 C2+/任务/移动/路径行为变化 | 不能把维护包当功能扩展 |

## 8. 对监督员草案的采纳与修正建议

### 采纳

- 采纳 `.alice-supervision/reviews/20260821-interface-c1-maintenance-route.md:22-32` 的最小目标、maintenance lane、与旧存档观察隔离、无需深度研究的判断。
- 采纳其 `:34-52` 的 Allowed/Forbidden Scope：F1-F4 仅限 scanner、snapshot selftest 和交接文档，不进入 C2+、transfer、endpoint、GUI automation、任务/移动或冻结行为。
- 采纳其 `:54-64` 的验证和客户端门禁：compile、focused headless、静态 diff/doc audit；默认不新增 Windows 矩阵，但一旦出现客户端可见变化即停止并重新建矩阵。

### 修正

- 草案 `:44-46` 要求修正文档事实是正确方向，但应按当前 `ba63d15` 重新核对 `docs/SUPERVISOR_HANDOFF.md`，不能机械恢复草案中较早的 `NEEDS_USER_DECISION/NEEDS_FIX` 快照；当前 handoff 同时保留历史状态与后续 accepted 事实，报告中应明确时间线。
- F2 的 `unknown` 是建议值而非现有 schema 新增枚举；实现计划应把它限定为现有 `blockId` 字符串字段的保守值，不得把 `ObservationStatus` 扩展成新状态或修改 snapshot schema。
- F1 的 fluid list 断言仅在现有 fixture 实际提供 fluid list 时才执行；不应为了满足文字覆盖而加入 Mek fixture。至少对现有 `items` `List.copyOf` 做不可变断言，并对无 fluid fixture 的“未覆盖”诚实记录。
- `ScanWand.java:46` 仍在 `capture` 后直接调用 `level.getBlockState(pos)` 生成提示 block key。候选草案未列该文件；由于 F2 只针对 `InterfaceScanner.capture`，默认不扩大。但若监督员要求“全链路未加载不访问”或改变该行为，则必须停止并重新规划，不能在本包顺手修正。
- F3 “更新 HANDOVER/SUPERVISOR_HANDOFF”必须避免把治理文档当前未提交状态误写为业务实现提交；报告/开发收尾应分别记录业务 commit 与治理文件状态。

## 9. 待调查问题与用户决策

### 待调查问题（不阻塞当前小包，但实现时必须核对）

- 当前 `InterfaceSnapshot` 的 `BlockPos` 与嵌套 record 字段是否已足以支撑 immutable assertion；初读显示足够，但开发员需在编译前确认测试表达式不引入额外 API。
- `runInterfaceSnapshotRegression` 对 chest 的直接 mutation 是否会被当前 level/block entity 生命周期立即反映；若不能稳定反映，停止并回报，不用强制添加刷新或 tick 逻辑。
- `ScanWand` 的提示 block lookup 是否在未加载目标情况下可触发独立风险；这不是本候选包已批准的范围。

### 必须由用户决定

1. 是否批准候选计划 `20260821-interface-c1-maintenance-f1-f4-v1` 的精确 Allowed/Forbidden Scope 与验证矩阵。
2. 是否接受默认“不新增客户端矩阵”；前提是实现不改变 item interaction、GUI 隔离或日志/消息契约；任何变化都应转为新的 `CLIENT_TEST_PENDING`。
3. 是否保持历史旧存档卡死为未归因观察并单独待诊断，不将其并入 F1-F4 maintenance。

在用户决定前，监督员不得发布实现授权；规划员不替监督员或用户作决定。

## 10. 交付状态

- 报告已落盘：`.alice-supervision/research/interface-c1-maintenance-f1-f4-planner-20260821.md`
- 报告仅为只读路线复核，不修改业务代码、`active-plan.md`、HANDOVER、客户端验收记录，也未派发任务。
- **本规划不构成实现授权。**
