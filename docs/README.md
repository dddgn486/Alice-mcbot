# Alice 文档索引

> **第一入口：[`START_HERE.md`](START_HERE.md)** —— 一句话接手口令和完整恢复流程。
> 归档内容仅用于追溯，**不作为当前规则**；与当前状态冲突时，以用户本轮决定、当前代码和真实客户端证据为准。

## 每次 AI 会话先读（活文档）

1. [`START_HERE.md`](START_HERE.md) —— 接手口令与恢复流程
2. [`AI_PROJECT_STATE.md`](AI_PROJECT_STATE.md) —— 当前目标、已验证状态、下一步
3. [`AI_DEVELOPMENT_PLAYBOOK.md`](AI_DEVELOPMENT_PLAYBOOK.md) —— 协作流程、skills 使用、Windows 测试习惯
4. [`AI_DECISIONS.md`](AI_DECISIONS.md) —— 稳定架构决策（D-001 起；新增决策按同一格式追加）
5. [`AI_TEST_MATRIX.md`](AI_TEST_MATRIX.md) —— 测试入口、预期行为、真实验收结果
6. 相关 skill：`.alice-supervision/skills/`（跨模块改动先读 `minimal-implementation-planning`）

规则文件还有仓库根的 [`../AGENTS.md`](../AGENTS.md)（会话级不可悄悄改变的边界）。

## 当前架构与设计

| 文件 | 用途 |
|---|---|
| [`ALICE_PATHING_CORE_ARCHITECTURE.md`](ALICE_PATHING_CORE_ARCHITECTURE.md) | 寻路内核总体架构（Baritone 兼容路线，D-036） |
| [`ALICE_PATHING_CORE_R1_CONTRACT.md`](ALICE_PATHING_CORE_R1_CONTRACT.md) | R1 契约（坐标/落点/完成判定） |
| [`ALICE_PATHING_CORE_R2_MOVEMENTS.md`](ALICE_PATHING_CORE_R2_MOVEMENTS.md) | R2 Movement 原语契约 |
| [`R4_BARITONE_ALIGNMENT_AUDIT.md`](R4_BARITONE_ALIGNMENT_AUDIT.md) | 与 Baritone 的四层对照审计（未登记偏离/缺失清单） |
| [`MINING_STAND_SELECTION_DESIGN.md`](MINING_STAND_SELECTION_DESIGN.md) | 挖掘站位选优设计（v7 定稿，两模式 + 成本估算） |
| [`MINE_MIGRATION_DESIGN.md`](MINE_MIGRATION_DESIGN.md) | Mine 迁移设计与寻路红线（§6） |
| [`JOB_LAYER_DESIGN.md`](JOB_LAYER_DESIGN.md) | **L3 目标级任务层（`Job`）设计**：决策缝三件套 + 伐木第一消费者（D-073 专项） |
| [`ALIGNMENT_OPEN_QUESTIONS.md`](ALIGNMENT_OPEN_QUESTIONS.md) | 待决对齐问题（Q1/Q3/Q4/Q7…） |
| [`RISK_MODES_DISCUSSION.md`](RISK_MODES_DISCUSSION.md) | 风险模式 H/G/S 讨论（只讨论，未实现） |
| [`MULTI_BOT_INTERFACE_RESERVATION.md`](MULTI_BOT_INTERFACE_RESERVATION.md) | 多 bot 并行接口预留 |
| [`AI_CHANGELOG.md`](AI_CHANGELOG.md) | 历史改动流水（含失败与根因假设） |

## 待裁定草案（**非最终方案**）

> 与外部 AI 讨论产出的设计草案与问题清单：**未定案，不作为当前实现授权**。
> 入库目的是把"已核实的事实 / 待裁定的问题 / 建议切片"留存下来，避免重复调查。

| 文件 | 内容 |
|---|---|
| [`RISK_SYSTEM_DESIGN_DRAFT.md`](RISK_SYSTEM_DESIGN_DRAFT.md) | 风险系统实现草案：8 条设计原则、契约三种时机、切片 S1–S6、与既有决策的对齐 |
| [`RISK_SYSTEM_ISSUE_LIST.md`](RISK_SYSTEM_ISSUE_LIST.md) | 风险与维生系统问题修复清单：P0-A/B/C、P1-A/B/C、P2-A/B/C 与待确认事实 Q1 |
| [`RISK_SYSTEM_REVIEW_20260910.md`](RISK_SYSTEM_REVIEW_20260910.md) | **本仓库复核记录**：9 条断言逐条核对全部属实、Q1 反编译答案（同步加载而非 void air）、P0-B 机制精确化、§3.7 的一处反对意见、执行顺序调整建议 |

## 测试与验收

| 文件 | 用途 |
|---|---|
| [`TESTING_GUIDE.md`](TESTING_GUIDE.md) | 测试流程总纲（零参数入口、场景规则、验证等级） |
| [`BARITONE_CONTRAST_TESTING.md`](BARITONE_CONTRAST_TESTING.md) | Baritone 对照实例（`Bariton_contrast`）流程 |
| [`AI_TEST_MATRIX.md`](AI_TEST_MATRIX.md) | 每项能力的入口与实测结论 |
| `.alice-supervision/client-tests/` | 客户端证据（截图/日志/证据报告），`AI_TEST_MATRIX` 的 `USER_ACCEPTED` 条目引用此处 |

游戏内一律优先使用**已有测试物品**（如 `alice:mine_regression`、`alice:mining_scene_tester`、`alice:target_selector`、`alice:pathing_regression`），
配合数据包一键场景（`/function alice_test:<scene>`）；实测由真人在 Windows 客户端 `D:\JAVA_projects\alice\` 完成。

## 参考资料

`reference/` 仅存放外部项目、数学模型或模组语义参考，**不是当前实现授权**：

- `BARITONE_PORTING_CHECKLIST.md`、`ROAD_MATHEMATICAL_MODEL.md`、`MEK_GUI_SEMANTICS.md`

## 历史归档（不作为当前规则）

| 目录 | 内容 |
|---|---|
| `archive/legacy-2026-08/` | 2026-08/09 被取代的设计与审计：`AI_PLAYER_DESIGN`、`PRODUCT_ARCHITECTURE_ROADMAP`、`EXECUTION_FRAMEWORK`、`PATHING_REFACTOR`、`MOVEMENT_SYSTEM_ARCHITECTURE`、`MINING_SAFETY_AND_PLANNING`、`MINETASK_*`、`MOVEMENT_*`、`R2C_*`、`HANDOVER_20260907`、`TASK_OUTCOME_CONTRACT`、`BOT_CONTROLLER_QUICK_TEST` |
| `archive/legacy-design/` | 更早的设计与调查材料 |
| `archive/legacy-testing/` | 更早的测试说明与同步清单 |
| `archive/legacy-workflow/` | 已废弃的 HANDOVER / dsh-agent-bus / 监督流程模板 |
| `../.alice-supervision/archive/legacy-2026-08/` | 旧监督工作流的历史文档（`PROJECT-STATUS`、`MEMO`、`ARCHITECTURE`、`DEVELOPMENT-GUIDE`、`QUICKSTART`、`phase-3.*`、`refactoring/`、lumber/inventory 调查等） |

**2026-09-10 清理说明**：旧监督工作流的纯流程产物（`pending/` 55 份 review packet、`research/` 120 份批量评审提示词、
`templates/`、`emergency/`、`testing/`、`state-machine.yml`、`docs/testing-workflow-v2.md`）已从工作树删除；
内容仍在 git 历史中，可用 `git log --diff-filter=D --name-only` 追溯。
