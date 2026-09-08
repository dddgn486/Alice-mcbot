# Alice 项目二次审核协议

## 目标

本协议把“实现工作”和“项目监督”分开：主工作会话负责交付可验证改动；监督员负责根据架构、已冻结决策和长期路线审查其方向。监督员不接管实现、不替代测试，也不在没有明确授权时修改业务代码。

Alice 的总目标保持不变：LLM 只做目标级决策，确定性执行器完成可靠动作；服务端权威；GUI 操作必须映射为语义接口。每一项功能都必须能放回这条链路，而不是只在局部看起来可用。

## 固定边界

审核时优先检查以下边界，任何突破都必须先由用户显式批准并同步修改设计文档：

1. `MineTask`、`DropCollectionTask` 与普通挖矿保持 `HARD_PATH`；实验性 `SOFT_SURFACE` 不能提前接入。
2. `SOFT_SURFACE` 只按 `PATHING_REFACTOR.md` 的分阶段路线推进；平地、转向、高差、流体和逃生不能混在同一个未经验证的实现里。
3. 曲面寻路、通道规划和手动道路蓝图相互独立；`SEARCH_LIMIT` 绝不等价于 `UNREACHABLE`，也不能自动授权开通道。
4. 决策层不能绕过执行层的安全区、流体、工具、材料、碰撞和可回收性验证。
5. 每次代码改动至少执行 `./gradlew compileJava`，并在提交后 `git push origin master`，供 Windows 客户端实测。
6. 任何需要客户端验收的行为工作包必须在实现前定义可复现、可观察、可停止的游戏内测试入口；实验能力优先使用独立诊断入口，未获用户验收前不得借金斧默认行为、`FollowTask` 或普通任务链作为测试载体。

完整的已验证状态、R# 决策和当前下一步始终以 `docs/HANDOVER.md` 与 `docs/PATHING_REFACTOR.md` 为准。

## 工作包状态机

跨会话的唯一工作授权文件是本地 `.alice-supervision/active-plan.md`；模板见 `docs/supervision/ACTIVE_PLAN_TEMPLATE.md`。它由监督员维护，主工作会话只能消费，不能自行扩大或改写授权范围。

```text
监督员规划 DRAFT / NEEDS_USER_DECISION
  -> 用户确认范围或接受前置客户端结果
  -> APPROVED_FOR_IMPLEMENTATION
  -> 主工作会话实现并收尾
  -> 监督员 REVIEW
  -> CLIENT_TEST_PENDING（客户端可见行为）
  -> 用户明确确认 USER_ACCEPTED 或要求 NEEDS_REPLAN
  -> 监督员发布下一份 APPROVED_FOR_IMPLEMENTATION 计划
```

`APPROVED_FOR_IMPLEMENTATION` 只表示可以实现当前工作包，不表示客户端效果已通过。任何 `CLIENT_TEST_PENDING`、`NEEDS_USER_DECISION`、`BLOCKED` 或缺失计划均禁止主工作会话自行推进功能范围。唯一例外是用户明确授权的、不改变项目方向的维护；主会话须带 `--allow-no-plan` 运行启动脚本，并在 HANDOVER 记录该例外。

## 研究分流：监督员浅调研与深度调查

每个新模块、跨模块能力或高风险行为在创建 active plan 前，都先由监督员做**浅调研**：读取 Alice 现有代码、`HANDOVER.md`、相关设计文档、R# 决策、当前 Git 状态与已有客户端证据，写出本轮范围、复杂度、前置条件和验证缺口。浅调研足以支持小而隔离、复用已验证模式的工作包时，监督员可直接规划，不必为了流程而派发深度调查。

监督员必须派发**深度调查**的典型情形：

1. 关键实现依赖外部项目、原版/Forge 版本语义或成熟算法，且现有文档不足以可靠适配；
2. 改动跨越规划、movement primitive、执行、物理、网络/假连接、维生或用户体验等多个边界；
3. 已有源码、日志、用户客户端现象或设计决策彼此冲突；
4. 主开发员在批准范围内连续受阻，无法用小修复或已有证据安全解决；
5. 用户明确要求先做深入调研。

监督员以 `docs/supervision/RESEARCH_TASK_TEMPLATE.txt` 派发 `.alice-supervision/research/<topic>-research-task.txt`；调查员使用 `RESEARCH_REPORT_TEMPLATE.md` 输出同目录报告。任务必须写清问题、优先来源、证据要求、禁止事项和输出路径。报告只提供证据和建议，不修改业务代码、active plan 或客户端验收记录，也不构成实现授权；监督员审核报告后，才可在 active plan 的 `Research Decision` 中明确采纳哪些结论。

### 规划员路线审核

规划员是独立的路线设计角色，不是监督员的替代者，也不是用户入口。监督员可以把后续开发线路、依赖拆分、风险、停止条件和客户端验收矩阵派给规划员；规划员只读分析并通过任务报告交付方案，不得修改业务代码、`active-plan.md`、审核记录或客户端验收记录，不得直接向用户呈报，也不得自行派发开发或调查任务。

规划员报告必须设置监督员为审核者。监督员须在报告基础上明确记录采纳、拒绝或需要用户决策的结论；未经监督员验收，规划报告不得转化为 active plan、开发任务或实现授权。只有监督员审核通过并向用户呈报、用户明确批准后，监督员才能把采纳路线写入 `active-plan.md`，再按依赖派发深度调查或主开发员工作包。规划报告不等于 `APPROVED_FOR_IMPLEMENTATION`，用户批准路线也不等于 `USER_ACCEPTED` 客户端效果。

## 调研证据与进度治理

### 证据等级与采纳

1. **Alice 本地事实优先**：当前代码、当前 Git 提交、`HANDOVER.md`、冻结 R# 决策和用户客户端证据优先于外部项目结论。外部源码只能说明可借鉴的机制，不能覆盖本项目已观测的行为。
2. **报告必须区分事实与建议**：调查员应标记固定版本/commit、源码文件/方法、观察到的事实、推断、不可直接迁移的前提。未固定版本、二手文章、README 宣称或历史镜像只能作为弱证据，不能单独成为高风险实现前提。
3. **采纳可追溯**：监督员在 active plan 的 `Research Decision` 中逐条写出采纳的报告路径与具体结论，并写明未采纳的关键建议及原因。不得用“参考某项目”笼统授权实现。
4. **研究结论会失效**：目标 Minecraft/Forge/模组版本、fake-player 生命周期、接口签名、客户端证据或冻结边界变化时，监督员必须重新评估既有报告；旧报告不能自动延续到新工作包。
5. **研究不等于验证**：外部源码、headless simulation 和编译通过都不能替代 Alice 的服务端运行证据；涉及客户端物理、可见行为、GUI 或交互，仍须按客户端矩阵由用户验收。

### 调研止损与连续受阻

1. **简单调查优先**：监督员可自行完成本地代码/文档/提交/日志与少量官方源码核查；若结论能把范围限定为一个小、可回滚、可验收的工作包，即不派发深度调查。
2. **连续受阻的判定**：同一已批准假设在两个独立实现尝试后仍缺乏客户端改善，或同一根因无法由当前代码、日志和设计文档一致解释，即视为连续受阻。监督员必须停止继续叠加参数、epsilon、超时或特判，转为深度调查或请求用户选择保守降级/重新规划。
3. **深调研应有边界**：任务必须声明一个具体问题、优先源码、版本范围、最少必答项、输出路径和完成条件；禁止把“泛搜类似项目”当作无限期研究。
4. **并行而非阻塞**：深调研期间，监督员应优先从路线图选择与受阻模块无共享风险的已批准底层工作包并行推进。只有该模块是其他工作包不可替代的前置条件时，才允许暂停依赖链，并在 active plan 记录阻塞原因。
5. **研究的停止条件**：报告已能支持下列任一结果时即停止扩大搜索：采纳的最小实现方向、保守拒绝/能力降级、需要用户决策、或确认现有证据不足。不得为了寻找“完美实现”拖延可独立推进的项目主线。

### 模块节奏与工作包容量

1. **先稳定共享底座，再扩展新行为**：任务结果、失败/回收、权限、服务端语义快照和 HARD_PATH 等共享契约未稳定时，不以 AttackTask、LLM 调用、多任务队列或实验移动来掩盖底座缺陷。
2. **每包只解决一个可验证问题**：一个工作包不得同时改变规划算法、movement primitive、实体物理、网络/假连接、任务接入和客户端入口。若必须同时触及多个层，先做深调研并拆成有明确接口的阶段。
3. **维护项目吞吐量**：监督员必须同时维护主线、独立线和冻结线：主线优先推进用户价值最高的稳定底层；独立线可并行但不得共享未验能力；冻结线只保留证据和诊断，未经新计划不得继续修补。
4. **不以表面成功换进度**：不通过放宽完成条件、隐藏失败、传送、世界编辑、隐式回退或删除回归来制造 PASS。可接受的进度是明确缩小能力边界、保住已验能力并推进不相关模块。
5. **流程确认点**：监督员每次发布新 `APPROVED_FOR_IMPLEMENTATION` 包时，必须说明它属于主线/独立线/维护线、与当前受阻模块的隔离、预期验证成本及下一次用户确认点。

## 客户端测试入口规则

对客户端可见的工作包，监督员在发布计划时必须同时指定测试入口与矩阵，至少包含：

1. **入口类别与易用性**：优先独立诊断命令或专用测试工具；常用场景优先采用短子命令、手持测试物品右键/Shift+右键、或管理员交互式测试台，而非要求用户反复输入长坐标。入口只能创建明确的实验任务，不得隐式复用挖矿、拾取、道路、隧道或其他稳定链。
2. **坐标/目标契约**：即使入口从玩家交互推导目标，也必须明确输入最终是脚位、支撑方块、实体还是语义目标；由服务端从点击面、玩家脚位、视线或预置测试台推导后，仍须复用执行层结构化校验，不得为了易测跳过碰撞、流体、维生或可回收性检查。
3. **最小交互设计**：优先提供安全的默认目标和服务端确定性场景构造，例如“从 bot 当前脚位测试前方一格上/下阶”或“右键支撑方块测试其上方脚位”。只有需要精确复现或边界诊断时才保留坐标参数；自动构造/清理测试场景必须是专门批准的管理员工具，且不得改变普通任务世界语义。
4. **可观察证据**：输出稳定的任务 ID/动作阶段、推导后的输入与实际脚位、关键世界状态、完成或失败码；客户端矩阵指定应保留的日志、截图或视频和 bot 最终位置。
5. **停止与回收**：每个场景必须列出立即停止条件；失败时只允许清任务并保留可回收 bot，不得改世界、切换到 `HARD_PATH` 或扩大任务权限来“完成测试”。
6. **升级门槛**：独立入口的用户 `USER_ACCEPTED` 只验证该项原语。接入金斧默认行为、`FollowTask` 或任何普通任务链，必须创建新的工作包、复核调用边界并接受新的客户端矩阵。

金斧等现有正式或半正式入口可以继续作为已验能力的回归入口；它们不能成为未验高差、流体、逃生、跑步或攻击能力的首个测试载体。若独立命令不足以构造场景，可在专门批准的工作包中增加仅开发/管理员可用的测试工具或交互测试物品，但必须保留上述隔离、日志和停止契约。

## 主工作会话启动与收尾

每次主工作会话按以下顺序执行：

1. 运行 `./tools/work-session-start.sh`，阅读打印出的 active plan、`docs/HANDOVER.md` 与计划点名的设计文档；脚本只接受 `APPROVED_FOR_IMPLEMENTATION` 状态。
2. 仅实现该计划的 Allowed Scope；发现需要扩大范围、修改冻结边界或补做未列前置验证时停止，交还监督员重规划。
3. 运行与本次风险匹配的验证；代码改动必须至少通过 `./gradlew compileJava`。
4. 更新 `docs/HANDOVER.md`：写明当前 HEAD、实际完成内容、验证证据、尚未验证的限制、不能踩回去的决策与下一步。
5. 提交改动并推送 `origin/master`。
6. 运行 `tools/session-complete.sh`。脚本会拒绝未更新 `HANDOVER.md` 的普通收尾，并把本提交的摘要、变更文件、handover diff 和 active plan 快照写入本地监督队列。

首次启用时运行一次：

```bash
./tools/install-supervision-hook.sh
```

这会安装本地 `post-commit` 钩子：只有提交内包含 `docs/HANDOVER.md` 时才自动生成审核包。该钩子不进入 Git，因此每个开发副本都需要单独安装；手工执行收尾脚本仍是最终检查点。

## 审核包与 dsh-agent-bus 通知

审核包位于 `.alice-supervision/pending/<short-commit>.md`，已被 `.gitignore` 忽略。它保留：提交 SHA、文件清单、统计、交接文档差异和固定审查清单。

项目可通过 `dsh-agent-bus` 在同一 Alice 工作区的四个常驻角色会话间派发任务、唤醒执行者并把报告回传给监督员。启用前必须确认四个会话运行在实际加载该插件的同一 DSH profile/workspace，并能看到 bus 工具；仅在隔离 profile 中安装依赖不等于当前 3081 Web 已启用。bus 是**编排与通知层**，不是授权或事实层：

- Git commit/HEAD、`docs/HANDOVER.md`、`.alice-supervision/active-plan.md`、审核包、研究报告和用户客户端结论仍是权威事实；
- bus 任务必须写明 Alice 工作包/提交号、允许范围、验收标准、报告文件路径和 reviewer；不能用一条自由文本消息替代 `APPROVED_FOR_IMPLEMENTATION`；
- `send_note` 仅用于无需验收的短消息；规划、调查、实现和审核均使用 `create_task`，执行方必须 `report_task`，监督员必须 `settle_task`；需要用户或监督员补充信息时使用 `request_input`；
- 开发员收到 bus 任务后仍必须先通过 `./tools/work-session-start.sh`。active plan 未批准、范围不一致或项目处于待命时，即使 bus 已投递也不得实现；
- 长链路可用 `create_flow` 和依赖 DAG，但下游实现节点只有在监督员验收规划/调查节点且 active plan 已获用户批准后才能释放；
- bus 报告不是长期项目档案。规划、研究、审核、客户端记录仍写入 `.alice-supervision/`；功能事实仍进入 Git/HANDOVER。

`tools/session-complete.sh` 仍必须生成审核包。部署桥接脚本时可继续使用：

```bash
export ALICE_SUPERVISOR_NOTIFY_CMD='/absolute/path/to/notifier'
./tools/session-complete.sh
```

通知命令收到审核包绝对路径。桥接器只应向既定监督员发送 note/任务提示，不得自行改 active plan、验收提交或标记 `USER_ACCEPTED`。

### bus 故障降级

- 已验证能力：任务/依赖投递、`report_task -> settle_task`、取消、超时、账本重启恢复和离线 note 补投。
- 当前已知限制：浏览器关闭或会话归档后，运行时可能仍把执行者保留为 live，`offlineGraceMs` 不一定及时产生离线通知。不要把“没有离线通知”解释为成员仍在工作。
- 监督员在接管、恢复或怀疑断链时，应同时检查 `list_tasks`/`get_task`、`.alice-supervision/pending/`、Git HEAD 和对应角色会话；必要时取消或转派原 task id，禁止为同一工作包创建重复实现任务。
- bus 不可用时退回 `.alice-supervision` + Git + 用户消息流程。不得因通知层故障跳过审核、用户确认或客户端验收。

监督员进入会话后，先读取所有待审包，再读取当前 `HANDOVER.md`、Git HEAD 与涉及的设计文档，输出以下之一：

- `通过`：方向、边界和验证记录一致；
- `带条件通过`：可保留当前提交，但必须先补充明确的测试、文档或隔离；
- `阻止后续扩展`：发现架构漂移、违反 R# 决策或把实验接入稳定链；
- `需要用户决策`：改动涉及产品方向或已冻结边界，不能由监督员自行裁定。

每份审核使用 `docs/supervision/REVIEW_TEMPLATE.md` 写入 `.alice-supervision/reviews/<short-commit>.md`，必须对照审核包中的 active plan 快照确认范围是否匹配。监督员随后创建或更新唯一的 `.alice-supervision/active-plan.md`；只有用户已批准当前范围、前置验收满足且状态为 `APPROVED_FOR_IMPLEMENTATION` 时，主工作会话才可开工。

对客户端可见行为，监督员用 `docs/supervision/CLIENT_TEST_TEMPLATE.md` 写入 `.alice-supervision/client-tests/<short-commit>.md` 的测试矩阵，用户填写观察与总体决定。监督员不得替用户将结果标为 `USER_ACCEPTED`；收到用户明确结论前，计划必须保持 `CLIENT_TEST_PENDING` 或 `NEEDS_USER_DECISION`。

## 四角色 preset 与监督会话启动

四个角色都是同一 Alice 工作区内的独立常驻 DSH 会话，通过 `dsh-agent-bus` 协作：

1. **Alice项目监督员**（`alice-supervisor`）：唯一调度者和 reviewer；读取权威状态、向用户呈报、创建/验收 bus 任务、维护 active plan 和客户端门禁。
2. **Alice实现规划员**（`alice-planner`）：只读产出最小实现线路、依赖、风险和验收矩阵；必须 `report_task` 给监督员，不直接呈报用户，不创建实现任务。
3. **Alice主开发员**（`alice-developer`）：只执行 `APPROVED_FOR_IMPLEMENTATION` 的 Allowed Scope；提交、push、生成审核包后 `report_task`，等待监督员验收。
4. **Alice深度调查员**（`alice-researcher`）：只执行监督员派发的研究任务；报告落入指定 `.alice-supervision/research/` 路径后 `report_task`，不实施、不审批。

首次建立团队时，四个会话都应在同一个 `/home/fb486/projects/alice` workspace 下创建并选择对应 preset；各自调用 `update_card` 声明稳定能力键。监督员用 `list_peers` 确认成员身份后再派任务，禁止仅按会话标题猜角色。

监督员接管或被 bus 唤醒时按以下顺序恢复事实：

1. 读取 `docs/SUPERVISION_PROTOCOL.md`、`docs/HANDOVER.md` 和 `docs/SUPERVISOR_HANDOFF.md`；
2. 读取当前 Git HEAD/工作区、`.alice-supervision/active-plan.md`、全部 pending/review/client-test/research 状态；
3. 使用 `list_tasks`/`get_task` 核对未结算 bus 工作，避免重复派发；
4. 若项目为 `NEEDS_USER_DECISION`、`CLIENT_TEST_PENDING` 或待命，先向用户确认，不因成员在线而自行恢复实现；
5. 只有 active plan、用户批准和 bus task 三者范围一致时，才向开发员释放实现。

`dsh-agent-bus` 负责跨会话投递、唤醒、DAG 和回传；`.alice-supervision` + Git 负责长期状态、授权与可审计证据。两层冲突时以 Git、active plan、用户实测为准，并由监督员修正/取消 bus 任务。

## 监督员工作方式

监督员关注系统性风险，而不是重做普通 code review：

- 功能是否位于正确分层，输入/输出契约是否能支撑后续 LLM 决策与确定性执行；
- 新功能是否复用了已验证的原语，是否把临时实验错误扩展为默认行为；
- 是否把失败、资源不足、搜索预算耗尽和复杂世界状态保守地返回给决策层或用户；
- 文档中的“已验证”和“待验证”是否和提交、客户端实测事实一致；
- 下一步是否是最小可验证增量，而不是跳过前置条件的功能堆叠。

监督员可以给出路线建议、拆分阶段、提出验证矩阵，并标记值得确认的兴趣线索；但未经用户指派，不向业务实现直接塞新玩法或改变已有语义。
