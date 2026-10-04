# 清旧家 · 判据表（**生成物，禁手改**）

> 本文件由 `tools/cleanup-classify.py` 生成 —— 手改会被门禁判红。
> 重新生成：`python3 tools/cleanup-classify.py --write`　·　校验：`--check`　·　自证：`--selftest`
> 门禁：`tools/check-cleanup-classify.sh`（挂 `tools/check-all.sh`）。

## 先说清楚：**本表不是「可删清单」**

它只**算**四类机械信号（依据 = 草案 `§D′-9`），**一个字都不判「该不该留」**。
用户只判两问：**① 这句话对不对 ② 这句话过期没有**（原文逐字见 `§D′-9` 9.5）。

| 本表给的 | 本表**不给**的 |
|---|---|
| 「它归哪个好家」·「全仓有几个文件提到它」·「生成物陈不陈旧」·「同族重复度」 | 「该丢还是该留」·「该合还是该并」·「它现在还算不算数」 |

## 一 · 分桶读数（**必须按好家分桶读**）

**为什么**：零引用**不是**错的信号 —— ⑧ 域外的归档件**本来就该零引用**。
把总数（本表最后一行的合计）当成「可删清单」是**本表最容易被误读的地方**。

| 好家 | 件数 | 零引用 | 零引用**合法**吗 |
|---|---|---|---|
| ① 常驻规范 | 2 | **0** | **绝不合法** —— 常驻件零引用 = 没有任何 agent 会读到它 |
| ② 入口 ＋ 地图 | 2 | **0** | **待用户判**（本表只报数） |
| ③ 决策/裁定 | 4 | **0** | **待用户判**（本表只报数） |
| ④ 状态 ＋ 构想 | 38 | **0** | **待用户判**（本表只报数） |
| ⑤ 报告 ＋ 证据 | 42 | **0** | **待用户判**（本表只报数） |
| ⑥ 边界即机器 | 115 | **0** | **待用户判**（本表只报数） |
| ⑦ 事实/数据 | 17 | **0** | **待用户判**（本表只报数） |
| ⑨ 待删（一次性脚本） | 2 | **0** | **待用户判**（本表只报数） |
| ⑧ 域外（⛔ 不进体系） | 225 | **0** | **合法** —— 域外件不进体系，本来就没人引 |
| **合计** | **447** | **0** | 这个合计**没有行动含义**（见上） |

## 二 · 行动面：**零引用且不在 ⑧ 域外**

⚠️⚠️ **本节这个信号会被「提及」污染 —— 实测过一次，如实记**：本表头一版列了 **2 件**
（`tools/dsh-phone-qr.sh` · `tools/render-scene-preview.py`），而**下一个提交里**
主工作流在台账 `O155` 写了这两个文件名 ⇒ 它们**当场变成「被提到过 1 次」** ⇒ 本节归零。
⭐ **那两次读数都对**，差的是**我改了仓**（不是判据漂了）—— 而它暴露的是 `§D′-9` 9.3 判据②
自己写下的那条：**「提到它」会被当成「引用它」**，所以本列只叫**被提到过**，⛔ 不叫被引用。
⇒ ⭐ **结论**：本节**不是**主要行动面（⛔ 别把 `0 件` 读成「没活可干」）；
    真正有信息的是**下一节**（自称生效而 `src/` 零落点）。

**0 件** —— 打勾栏：`保留` / `丢` / `合并到 X` / `已过期`（`§D′-9` 9.5 的四个选项）。

| # | 件 | 好家 | 提到它的文件数 | 候选理由（机械） | 保留 | 丢 | 合并到 | 已过期 |
|---|---|---|---|---|---|---|---|---|

## 三 · **批 1 的靶子**：自称生效、而 `src/` 里零引用 ⭐⭐

**为什么单列这一节**：`W7′-3` 把批 1 定为「**声称生效但零 `src/` 引用**」。
⭐ 这是**最贵的一族假边界**的形状 —— 一份文档自称「已生效／已落地／已接线」，
而**代码里根本找不到它** ⇒ 后续会话会把它当**依据**引（`§E`：假边界会被当依据引用）。

⚠️ 判据两条**都是机械的**：① 头部 40 行里出现「已生效／生效中／已落地／已接线／已实现／已拍板」
（**文件自己的自称**，⛔ 不是我的判断）② 全仓 `src/` 下**零命中它的文件名**。

**6 件** —— 打勾栏同 `§D′-9` 9.5。

| # | 件 | 好家 | 自称 | 全仓提到它 | `src/` 里 | 保留 | 丢 | 合并到 | 已过期 |
|---|---|---|---|---|---|---|---|---|---|
| 1 | `docs/MINE_TASK_DESIGN.md` | ④ 状态 ＋ 构想 | **已落地** | 9 | **0** | ☐ | ☐ | ☐ | ☐ |
| 2 | `docs/archive/legacy-2026-08/HANDOVER_20260907.md` | ⑧ 域外（⛔ 不进体系） | **已实现** | 1 | **0** | ☐ | ☐ | ☐ | ☐ |
| 3 | `docs/archive/legacy-2026-08/MINING_SAFETY_AND_PLANNING.md` | ⑧ 域外（⛔ 不进体系） | **已实现** | 1 | **0** | ☐ | ☐ | ☐ | ☐ |
| 4 | `docs/archive/legacy-2026-08/PATHING_REFACTOR.md` | ⑧ 域外（⛔ 不进体系） | **已实现** | 6 | **0** | ☐ | ☐ | ☐ | ☐ |
| 5 | `docs/archive/legacy-workflow/SUPERVISOR_HANDOFF.md` | ⑧ 域外（⛔ 不进体系） | **已实现** | 10 | **0** | ☐ | ☐ | ☐ | ☐ |
| 6 | `docs/authz/POLICY_MATRIX_PROPOSAL.md` | ③ 决策/裁定 | **已拍板** | 9 | **0** | ☐ | ☐ | ☐ | ☐ |

⚠️ ⛔ **`src/` 里零命中不等于它错了** —— 文档本来就不必被代码引用。
它只说明：**这份自称生效的东西，在代码里没有落点**。⇒ 该由你来判「这句话对不对／过期没有」。

### 附：自称生效且有 `src/` 落点的（**供对照**，⛔ 这些不是候选）

**2 件**：

| 件 | 自称 | `src/` 里提到它的文件数 |
|---|---|---|
| `docs/OPEN_ITEMS_LEDGER.md` | 已落地 | **4** |
| `docs/MINE_MIGRATION_DESIGN.md` | 已落地 | **1** |

## 四 · 生成物新鲜度（陈旧 ⇒ 该重生成，**不是该删**）

| 生成物 | 门禁 | 现在 |
|---|---|---|
| `docs/DOC_REFACTOR_PLAN.md` | `tools/check-plan-doc-refactor.sh` | **陈旧** |
| `docs/DESIGN_INDEX.md` | `tools/check-design-index.sh` | **陈旧** |
| `docs/DECISIONS_INDEX.md` | `tools/check-decisions-index.sh` | **陈旧** |
| `survey/README.md` | `tools/check-survey-index.sh` | **陈旧** |
| `docs/plans/README.md` | `tools/check-doc-registry.sh` | **陈旧** |
| `docs/reviews/README.md` | `tools/check-doc-registry.sh` | **陈旧** |
| `.alice-supervision/skills/README.md` | `tools/check-skills-index.sh` | **陈旧** |
| `docs/CLEANUP_CLASSIFY.md` | `tools/check-cleanup-classify.sh` | **陈旧** |

## 五 · 同族重复度（8-gram Jaccard >= **0.30** 才列）

**0 对** —— 实测**没有任何同族对达到 0.30**。

这与本项目既有读数一致（`RISK_*` 0.085 · `MINE_*` 0.032 · `DECISION_*` 0.056 · `CLIENT_*` 0.052 · `ALICE_PATHING_CORE_*` 0.052 —— 全部 < 0.085）⇒ **「前缀像、内容各写各的」是本仓的常态**，所以「合并」这一档在本仓几乎没有活可干。

## 六 · 全表（**每一件的归位与信号**；供逐件复核）

| 件 | 好家 | 提到它的文件数 | `src/` 里 | 自称生效 | 生成物陈旧 |
|---|---|---|---|---|---|
| `AGENTS.md` | ① 常驻规范 | 83 | 5 | — | — |
| `docs/AI_DEVELOPMENT_PLAYBOOK.md` | ① 常驻规范 | 23 | 0 | — | — |
| `README.md` | ② 入口 ＋ 地图 | 48 | 0 | — | — |
| `docs/README.md` | ② 入口 ＋ 地图 | 49 | 0 | — | — |
| `docs/AI_DECISIONS.md` | ③ 决策/裁定 | 110 | 7 | — | — |
| `docs/DECISIONS_INDEX.md` | ③ 决策/裁定 | 25 | 0 | — | 是 |
| `docs/authz/POLICY_MATRIX_PROPOSAL.md` | ③ 决策/裁定 | 9 | 0 | 已拍板 | — |
| `docs/authz/PROPOSAL_B_survival_write_authorization.md` | ③ 决策/裁定 | 5 | 0 | — | — |
| `docs/ACCEPTANCE_GUIDE.md` | ④ 状态 ＋ 构想 | 6 | 0 | — | — |
| `docs/AI_PROJECT_STATE.md` | ④ 状态 ＋ 构想 | 32 | 0 | — | — |
| `docs/ALICE_PATHING_CORE_ARCHITECTURE.md` | ④ 状态 ＋ 构想 | 13 | 0 | — | — |
| `docs/ALICE_PATHING_CORE_R1_CONTRACT.md` | ④ 状态 ＋ 构想 | 11 | 0 | — | — |
| `docs/ALICE_PATHING_CORE_R2_MOVEMENTS.md` | ④ 状态 ＋ 构想 | 8 | 0 | — | — |
| `docs/ALIGNMENT_OPEN_QUESTIONS.md` | ④ 状态 ＋ 构想 | 9 | 0 | — | — |
| `docs/BATTERY_CURATION.md` | ④ 状态 ＋ 构想 | 27 | 5 | — | — |
| `docs/CLEANUP_CLASSIFY.md` | ④ 状态 ＋ 构想 | 5 | 0 | — | 是 |
| `docs/CLIENT_AGENT_CHANNEL.md` | ④ 状态 ＋ 构想 | 5 | 0 | — | — |
| `docs/CLIENT_AGENT_NEW_DEVICE_TEST.md` | ④ 状态 ＋ 构想 | 4 | 0 | — | — |
| `docs/CLOUD_MIGRATION.md` | ④ 状态 ＋ 构想 | 13 | 0 | — | — |
| `docs/DEATH_AND_REVIVAL_DESIGN_DRAFT.md` | ④ 状态 ＋ 构想 | 4 | 0 | — | — |
| `docs/DECISION_LAYER_DESIGN.md` | ④ 状态 ＋ 构想 | 13 | 2 | — | — |
| `docs/DECISION_LAYER_FINAL_FORM.md` | ④ 状态 ＋ 构想 | 11 | 0 | — | — |
| `docs/DOC_REFACTOR_PLAN.md` | ④ 状态 ＋ 构想 | 17 | 0 | — | 是 |
| `docs/EXPECTED_REDS.md` | ④ 状态 ＋ 构想 | 14 | 1 | — | — |
| `docs/GLOSSARY.md` | ④ 状态 ＋ 构想 | 16 | 0 | — | — |
| `docs/HANDOVER.md` | ④ 状态 ＋ 构想 | 57 | 1 | — | — |
| `docs/INTERACTION_LAYERS_COMPARISON.md` | ④ 状态 ＋ 构想 | 11 | 2 | — | — |
| `docs/JOB_LAYER_DESIGN.md` | ④ 状态 ＋ 构想 | 26 | 11 | — | — |
| `docs/KNOWLEDGE_RECIPE_GRAPH_NOTES.md` | ④ 状态 ＋ 构想 | 10 | 0 | — | — |
| `docs/MINE_MIGRATION_DESIGN.md` | ④ 状态 ＋ 构想 | 13 | 1 | 已落地 | — |
| `docs/MINE_SURVEY_PROTOCOL.md` | ④ 状态 ＋ 构想 | 5 | 0 | — | — |
| `docs/MINE_TASK_DESIGN.md` | ④ 状态 ＋ 构想 | 9 | 0 | 已落地 | — |
| `docs/MINING_STAND_SELECTION_DESIGN.md` | ④ 状态 ＋ 构想 | 12 | 3 | — | — |
| `docs/MOD_ADAPTER_PROTOCOL.md` | ④ 状态 ＋ 构想 | 15 | 1 | — | — |
| `docs/MOD_COMPAT_CRAFT_STATION_PLAN.md` | ④ 状态 ＋ 构想 | 7 | 1 | — | — |
| `docs/MULTI_BOT_INTERFACE_RESERVATION.md` | ④ 状态 ＋ 构想 | 11 | 0 | — | — |
| `docs/OPEN_ITEMS_LEDGER.md` | ④ 状态 ＋ 构想 | 51 | 4 | 已落地 | — |
| `docs/QUESTIONS_LEDGER.md` | ④ 状态 ＋ 构想 | 5 | 0 | — | — |
| `docs/REGION_REPLANT_ASYNC_DESIGN.md` | ④ 状态 ＋ 构想 | 6 | 2 | — | — |
| `docs/RISK_MODES_DISCUSSION.md` | ④ 状态 ＋ 构想 | 10 | 0 | — | — |
| `docs/RISK_SYSTEM_DESIGN_DRAFT.md` | ④ 状态 ＋ 构想 | 19 | 0 | — | — |
| `docs/STAGE3A_CRAFT_PLAN.md` | ④ 状态 ＋ 构想 | 7 | 0 | — | — |
| `docs/TESTING_GUIDE.md` | ④ 状态 ＋ 构想 | 17 | 2 | — | — |
| `docs/WORLD_WRITE_AUTHORIZATION.md` | ④ 状态 ＋ 构想 | 13 | 1 | — | — |
| `docs/plans/README.md` | ④ 状态 ＋ 构想 | 49 | 0 | — | 是 |
| `docs/reference/ROAD_MATHEMATICAL_MODEL.md` | ④ 状态 ＋ 构想 | 5 | 0 | — | — |
| `.alice-supervision/client-tests/d220-t3-20260915/evidence/evidence-report.md` | ⑤ 报告 ＋ 证据 | 73 | 0 | — | — |
| `.alice-supervision/client-tests/legacy-ascend-20260908/evidence/evidence-report.md` | ⑤ 报告 ＋ 证据 | 73 | 0 | — | — |
| `.alice-supervision/client-tests/minetask-scene-a-20260905/evidence/evidence-report.md` | ⑤ 报告 ＋ 证据 | 73 | 0 | — | — |
| `.alice-supervision/client-tests/minetask-scene-b-20260905/evidence/evidence-report.md` | ⑤ 报告 ＋ 证据 | 73 | 0 | — | — |
| `.alice-supervision/client-tests/minetask-scene-c-20260905/evidence/evidence-report.md` | ⑤ 报告 ＋ 证据 | 73 | 0 | — | — |
| `.alice-supervision/client-tests/minetask-unreachable-20260906/evidence/evidence-report.md` | ⑤ 报告 ＋ 证据 | 73 | 0 | — | — |
| `.alice-supervision/client-tests/movement-physics-experiment-1-20260906/evidence/evidence-report.md` | ⑤ 报告 ＋ 证据 | 73 | 0 | — | — |
| `.alice-supervision/client-tests/movement-physics-experiment-2-20260906/evidence/evidence-report.md` | ⑤ 报告 ＋ 证据 | 73 | 0 | — | — |
| `.alice-supervision/client-tests/movement-physics-experiment-3-20260906/evidence/evidence-report.md` | ⑤ 报告 ＋ 证据 | 73 | 0 | — | — |
| `.alice-supervision/client-tests/movement-physics-experiment-4-20260906/evidence/evidence-report.md` | ⑤ 报告 ＋ 证据 | 73 | 0 | — | — |
| `.alice-supervision/client-tests/movement-physics-experiment-5-20260906/evidence/evidence-report.md` | ⑤ 报告 ＋ 证据 | 73 | 0 | — | — |
| `.alice-supervision/client-tests/movement-physics-experiment-6a-20260906/evidence/evidence-report.md` | ⑤ 报告 ＋ 证据 | 73 | 0 | — | — |
| `.alice-supervision/client-tests/movement-physics-experiment-6b-20260906/evidence/evidence-report.md` | ⑤ 报告 ＋ 证据 | 73 | 0 | — | — |
| `.alice-supervision/client-tests/movement-physics-experiment-6c-20260906/evidence/evidence-report.md` | ⑤ 报告 ＋ 证据 | 73 | 0 | — | — |
| `.alice-supervision/client-tests/pathing-core-r2b-traverse-20260907/evidence/evidence-report.md` | ⑤ 报告 ＋ 证据 | 72 | 0 | — | — |
| `.alice-supervision/client-tests/pathing-core-r2b-traverse-20260907/evidence/r2b-traverse-logs.txt` | ⑤ 报告 ＋ 证据 | 3 | 0 | — | — |
| `.alice-supervision/client-tests/pathing-core-r2b-traverse-20260907/test-case.md` | ⑤ 报告 ＋ 证据 | 3 | 0 | — | — |
| `.alice-supervision/client-tests/pathing-core-r2c-movements-20260907/evidence/evidence-report.md` | ⑤ 报告 ＋ 证据 | 72 | 0 | — | — |
| `.alice-supervision/client-tests/pathing-core-r2c-movements-20260907/evidence/round1-diag-ok-ascend-descend-fail-r2c-logs.txt` | ⑤ 报告 ＋ 证据 | 2 | 0 | — | — |
| `.alice-supervision/client-tests/pathing-core-r2c-movements-20260907/evidence/round2-ascend-partial-descend-fail-r2c-logs.txt` | ⑤ 报告 ＋ 证据 | 2 | 0 | — | — |
| `.alice-supervision/client-tests/pathing-core-r2c-movements-20260907/evidence/round3-diagnostic-probe-r2c-logs.txt` | ⑤ 报告 ＋ 证据 | 2 | 0 | — | — |
| `.alice-supervision/client-tests/pathing-core-r2c-movements-20260907/evidence/round4-final-descend-r2c-logs.txt` | ⑤ 报告 ＋ 证据 | 2 | 0 | — | — |
| `.alice-supervision/client-tests/pathing-core-r2c-movements-20260907/test-case.md` | ⑤ 报告 ＋ 证据 | 3 | 0 | — | — |
| `.alice-supervision/client-tests/pathing-r3-battery-20260908/evidence/evidence-report.md` | ⑤ 报告 ＋ 证据 | 73 | 0 | — | — |
| `.alice-supervision/client-tests/pathing-r4-session-20260908/evidence/evidence-report.md` | ⑤ 报告 ＋ 证据 | 73 | 0 | — | — |
| `.alice-supervision/client-tests/protection-loop-guard-20260919/NOTES.md` | ⑤ 报告 ＋ 证据 | 3 | 0 | — | — |
| `.alice-supervision/client-tests/stage3a-a4b-cookingtab-20260913/NOTES.md` | ⑤ 报告 ＋ 证据 | 3 | 0 | — | — |
| `.alice-supervision/client-tests/stage3a-a4b-cookingtab-20260913/evidence/latest-log-excerpt.txt` | ⑤ 报告 ＋ 证据 | 2 | 0 | — | — |
| `.alice-supervision/client-tests/stage3a-a4b-cookingtab-20260913/evidence/round3-pass.txt` | ⑤ 报告 ＋ 证据 | 2 | 0 | — | — |
| `docs/BARITONE_ANCHORS.md` | ⑤ 报告 ＋ 证据 | 7 | 0 | — | — |
| `docs/BARITONE_CONTRAST_TESTING.md` | ⑤ 报告 ＋ 证据 | 7 | 0 | — | — |
| `docs/R4_BARITONE_ALIGNMENT_AUDIT.md` | ⑤ 报告 ＋ 证据 | 13 | 0 | — | — |
| `docs/REVIEW_2026-09-13_FIX_AUDIT.md` | ⑤ 报告 ＋ 证据 | 3 | 0 | — | — |
| `docs/RISK_SYSTEM_ISSUE_LIST.md` | ⑤ 报告 ＋ 证据 | 13 | 1 | — | — |
| `docs/RISK_SYSTEM_REVIEW_20260910.md` | ⑤ 报告 ＋ 证据 | 9 | 2 | — | — |
| `docs/STAGE2_MODS_READABILITY.md` | ⑤ 报告 ＋ 证据 | 9 | 0 | — | — |
| `docs/TRANSFER_MODULE_AUDIT.md` | ⑤ 报告 ＋ 证据 | 7 | 2 | — | — |
| `docs/reference/BARITONE_PORTING_CHECKLIST.md` | ⑤ 报告 ＋ 证据 | 7 | 0 | — | — |
| `docs/reviews/2026-09-20-mine-round3-root-cause.md` | ⑤ 报告 ＋ 证据 | 6 | 2 | — | — |
| `docs/reviews/README.md` | ⑤ 报告 ＋ 证据 | 49 | 0 | — | 是 |
| `survey/INTENT.md` | ⑤ 报告 ＋ 证据 | 7 | 0 | — | — |
| `survey/README.md` | ⑤ 报告 ＋ 证据 | 48 | 0 | — | 是 |
| `tools/alice-cloud-remote.sh` | ⑥ 边界即机器 | 2 | 0 | — | — |
| `tools/alice-cloudctl.sh` | ⑥ 边界即机器 | 4 | 0 | — | — |
| `tools/analyze-lumber-scene.py` | ⑥ 边界即机器 | 8 | 1 | — | — |
| `tools/analyze-trace.py` | ⑥ 边界即机器 | 2 | 0 | — | — |
| `tools/archive-index.py` | ⑥ 边界即机器 | 10 | 0 | — | — |
| `tools/authz-map.py` | ⑥ 边界即机器 | 13 | 0 | — | — |
| `tools/authz-map.sh` | ⑥ 边界即机器 | 4 | 0 | — | — |
| `tools/capability-list.py` | ⑥ 边界即机器 | 18 | 0 | — | — |
| `tools/capture-scene.py` | ⑥ 边界即机器 | 5 | 1 | — | — |
| `tools/check-all.sh` | ⑥ 边界即机器 | 86 | 2 | — | — |
| `tools/check-archive-index.sh` | ⑥ 边界即机器 | 9 | 0 | — | — |
| `tools/check-authz-registry.sh` | ⑥ 边界即机器 | 12 | 2 | — | — |
| `tools/check-baritone-anchor.py` | ⑥ 边界即机器 | 9 | 0 | — | — |
| `tools/check-capability-list.sh` | ⑥ 边界即机器 | 10 | 0 | — | — |
| `tools/check-cleanup-classify.sh` | ⑥ 边界即机器 | 3 | 0 | — | — |
| `tools/check-collect-callsite-shape.py` | ⑥ 边界即机器 | 11 | 3 | — | — |
| `tools/check-decisions-index.sh` | ⑥ 边界即机器 | 7 | 0 | — | — |
| `tools/check-design-index.sh` | ⑥ 边界即机器 | 9 | 0 | — | — |
| `tools/check-doc-links.py` | ⑥ 边界即机器 | 6 | 0 | — | — |
| `tools/check-doc-registry.sh` | ⑥ 边界即机器 | 6 | 0 | — | — |
| `tools/check-duplicate-class-names.py` | ⑥ 边界即机器 | 13 | 3 | — | — |
| `tools/check-effective-trace.py` | ⑥ 边界即机器 | 5 | 0 | — | — |
| `tools/check-effective-trace.sh` | ⑥ 边界即机器 | 3 | 0 | — | — |
| `tools/check-exec-record.sh` | ⑥ 边界即机器 | 4 | 0 | — | — |
| `tools/check-expected-reds.py` | ⑥ 边界即机器 | 7 | 0 | — | — |
| `tools/check-facts-tiers.py` | ⑥ 边界即机器 | 5 | 0 | — | — |
| `tools/check-facts-tiers.sh` | ⑥ 边界即机器 | 2 | 0 | — | — |
| `tools/check-far-goal-usage.py` | ⑥ 边界即机器 | 14 | 4 | — | — |
| `tools/check-fixture-hygiene.sh` | ⑥ 边界即机器 | 11 | 2 | — | — |
| `tools/check-frozen-code.py` | ⑥ 边界即机器 | 19 | 3 | — | — |
| `tools/check-glossary.py` | ⑥ 边界即机器 | 4 | 0 | — | — |
| `tools/check-glossary.sh` | ⑥ 边界即机器 | 2 | 0 | — | — |
| `tools/check-goal-vocabulary.sh` | ⑥ 边界即机器 | 7 | 0 | — | — |
| `tools/check-item-models.sh` | ⑥ 边界即机器 | 12 | 0 | — | — |
| `tools/check-job-kind-contracts.sh` | ⑥ 边界即机器 | 20 | 4 | — | — |
| `tools/check-job-menu-listable.sh` | ⑥ 边界即机器 | 10 | 1 | — | — |
| `tools/check-kernel-predicates.sh` | ⑥ 边界即机器 | 9 | 0 | — | — |
| `tools/check-layer-direction.py` | ⑥ 边界即机器 | 24 | 7 | — | — |
| `tools/check-machine-map.sh` | ⑥ 边界即机器 | 15 | 2 | — | — |
| `tools/check-new-home.sh` | ⑥ 边界即机器 | 6 | 0 | — | — |
| `tools/check-phase-transition-outlet.py` | ⑥ 边界即机器 | 12 | 0 | — | — |
| `tools/check-plan-doc-refactor.sh` | ⑥ 边界即机器 | 8 | 0 | — | — |
| `tools/check-policy-matrix.sh` | ⑥ 边界即机器 | 11 | 0 | — | — |
| `tools/check-precharge-containment.sh` | ⑥ 边界即机器 | 9 | 3 | — | — |
| `tools/check-primitive-budget-injection.py` | ⑥ 边界即机器 | 11 | 0 | — | — |
| `tools/check-primitive-readings.py` | ⑥ 边界即机器 | 10 | 0 | — | — |
| `tools/check-proposal-status.py` | ⑥ 边界即机器 | 4 | 0 | — | — |
| `tools/check-proposal-status.sh` | ⑥ 边界即机器 | 2 | 0 | — | — |
| `tools/check-protection-install-point.py` | ⑥ 边界即机器 | 8 | 2 | — | — |
| `tools/check-provision-containment.sh` | ⑥ 边界即机器 | 17 | 3 | — | — |
| `tools/check-quote-lint.py` | ⑥ 边界即机器 | 2 | 0 | — | — |
| `tools/check-quote-lint.sh` | ⑥ 边界即机器 | 2 | 0 | — | — |
| `tools/check-redline-gates.sh` | ⑥ 边界即机器 | 7 | 0 | — | — |
| `tools/check-ref-integrity.sh` | ⑥ 边界即机器 | 5 | 0 | — | — |
| `tools/check-risk-surface.sh` | ⑥ 边界即机器 | 4 | 0 | — | — |
| `tools/check-scene-connectivity.py` | ⑥ 边界即机器 | 15 | 0 | — | — |
| `tools/check-skills-index.sh` | ⑥ 边界即机器 | 5 | 0 | — | — |
| `tools/check-station-mapping.sh` | ⑥ 边界即机器 | 3 | 0 | — | — |
| `tools/check-step-names.sh` | ⑥ 边界即机器 | 5 | 0 | — | — |
| `tools/check-survey-index.sh` | ⑥ 边界即机器 | 6 | 0 | — | — |
| `tools/check-task-orchestration-split.py` | ⑥ 边界即机器 | 15 | 4 | — | — |
| `tools/check-task-top-freeze.py` | ⑥ 边界即机器 | 8 | 1 | — | — |
| `tools/check-transfer-clock.sh` | ⑥ 边界即机器 | 6 | 0 | — | — |
| `tools/check-underfoot-safety.py` | ⑥ 边界即机器 | 9 | 0 | — | — |
| `tools/check-win-script-encoding.py` | ⑥ 边界即机器 | 2 | 0 | — | — |
| `tools/cleanup-classify.py` | ⑥ 边界即机器 | 4 | 0 | — | — |
| `tools/cloud-restore-env.sh` | ⑥ 边界即机器 | 2 | 0 | — | — |
| `tools/cloud-rollback.sh` | ⑥ 边界即机器 | 8 | 0 | — | — |
| `tools/codespace-start-dsh.sh` | ⑥ 边界即机器 | 3 | 0 | — | — |
| `tools/codespace-zero.sh` | ⑥ 边界即机器 | 7 | 0 | — | — |
| `tools/death-persistence-e2e.sh` | ⑥ 边界即机器 | 7 | 2 | — | — |
| `tools/decisions-index.py` | ⑥ 边界即机器 | 13 | 0 | — | — |
| `tools/design-index.py` | ⑥ 边界即机器 | 7 | 0 | — | — |
| `tools/doc-registry.py` | ⑥ 边界即机器 | 9 | 0 | — | — |
| `tools/dsh-context-usage.sh` | ⑥ 边界即机器 | 7 | 0 | — | — |
| `tools/dsh-phone-qr.sh` | ⑥ 边界即机器 | 3 | 0 | — | — |
| `tools/dsh-session-log.mjs` | ⑥ 边界即机器 | 16 | 0 | — | — |
| `tools/dsh-session-rollback.mjs` | ⑥ 边界即机器 | 6 | 0 | — | — |
| `tools/exec-record.py` | ⑥ 边界即机器 | 7 | 1 | — | — |
| `tools/failure-ratio.py` | ⑥ 边界即机器 | 2 | 0 | — | — |
| `tools/fixture-hygiene.py` | ⑥ 边界即机器 | 13 | 1 | — | — |
| `tools/gate-inventory.py` | ⑥ 边界即机器 | 3 | 0 | — | — |
| `tools/gen-xray-pack.py` | ⑥ 边界即机器 | 2 | 0 | — | — |
| `tools/goal-vocabulary.py` | ⑥ 边界即机器 | 6 | 0 | — | — |
| `tools/headless-battery.sh` | ⑥ 边界即机器 | 42 | 4 | — | — |
| `tools/jar-content-hash.py` | ⑥ 边界即机器 | 2 | 0 | — | — |
| `tools/jar-content-hash.sh` | ⑥ 边界即机器 | 6 | 0 | — | — |
| `tools/job-kind-view.py` | ⑥ 边界即机器 | 10 | 1 | — | — |
| `tools/kernel-predicates.py` | ⑥ 边界即机器 | 47 | 9 | — | — |
| `tools/llm-relay.py` | ⑥ 边界即机器 | 4 | 1 | — | — |
| `tools/machine-map.py` | ⑥ 边界即机器 | 27 | 2 | — | — |
| `tools/make-agent-preset.py` | ⑥ 边界即机器 | 2 | 0 | — | — |
| `tools/make-cloud-tunnel-bundle.sh` | ⑥ 边界即机器 | 4 | 0 | — | — |
| `tools/mirror-windows-workspace.sh` | ⑥ 边界即机器 | 9 | 0 | — | — |
| `tools/module-selftest.sh` | ⑥ 边界即机器 | 10 | 2 | — | — |
| `tools/new-home-audit.py` | ⑥ 边界即机器 | 8 | 0 | — | — |
| `tools/plan-doc-refactor.py` | ⑥ 边界即机器 | 9 | 0 | — | — |
| `tools/policy-map.py` | ⑥ 边界即机器 | 25 | 4 | — | — |
| `tools/policy-map.sh` | ⑥ 边界即机器 | 4 | 0 | — | — |
| `tools/recipe-graph.py` | ⑥ 边界即机器 | 18 | 2 | — | — |
| `tools/recipe-readability.py` | ⑥ 边界即机器 | 9 | 0 | — | — |
| `tools/redline-gates.py` | ⑥ 边界即机器 | 9 | 0 | — | — |
| `tools/ref-integrity.py` | ⑥ 边界即机器 | 11 | 0 | — | — |
| `tools/region-ore-scan.py` | ⑥ 边界即机器 | 4 | 0 | — | — |
| `tools/render-scene-preview.py` | ⑥ 边界即机器 | 3 | 0 | — | — |
| `tools/risk-surface.py` | ⑥ 边界即机器 | 12 | 1 | — | — |
| `tools/simulate-scene-plan.py` | ⑥ 边界即机器 | 3 | 0 | — | — |
| `tools/skills-index.py` | ⑥ 边界即机器 | 6 | 0 | — | — |
| `tools/station-mapping.py` | ⑥ 边界即机器 | 6 | 0 | — | — |
| `tools/step-names.py` | ⑥ 边界即机器 | 7 | 0 | — | — |
| `tools/survey-index.py` | ⑥ 边界即机器 | 11 | 0 | — | — |
| `tools/sync-windows-artifact.sh` | ⑥ 边界即机器 | 12 | 1 | — | — |
| `tools/task-dispatch-table.py` | ⑥ 边界即机器 | 9 | 0 | — | — |
| `tools/task-retirement-map.py` | ⑥ 边界即机器 | 11 | 1 | — | — |
| `tools/transfer-clock.py` | ⑥ 边界即机器 | 6 | 0 | — | — |
| `docs/AI_CHANGELOG.md` | ⑦ 事实/数据 | 5 | 0 | — | — |
| `docs/AI_TEST_MATRIX.md` | ⑦ 事实/数据 | 28 | 1 | — | — |
| `docs/CAPABILITY_LIST.md` | ⑦ 事实/数据 | 16 | 0 | — | — |
| `docs/DESIGN_INDEX.md` | ⑦ 事实/数据 | 14 | 0 | — | 是 |
| `docs/JOB_KIND_VIEW.csv` | ⑦ 事实/数据 | 9 | 1 | — | — |
| `docs/MACHINE_MAP.csv` | ⑦ 事实/数据 | 19 | 0 | — | — |
| `docs/MEKANISM_FACTS.md` | ⑦ 事实/数据 | 8 | 0 | — | — |
| `docs/TASK_DISPATCH_TABLE.csv` | ⑦ 事实/数据 | 9 | 1 | — | — |
| `docs/TASK_RETIREMENT_MAP.csv` | ⑦ 事实/数据 | 11 | 1 | — | — |
| `docs/TASK_TOP_LEVEL_FREEZE.txt` | ⑦ 事实/数据 | 10 | 1 | — | — |
| `docs/THERMAL_FACTS.md` | ⑦ 事实/数据 | 8 | 0 | — | — |
| `docs/THERMAL_S1_FACTS.md` | ⑦ 事实/数据 | 8 | 1 | — | — |
| `docs/authz/AUTHZ_REGISTRY.csv` | ⑦ 事实/数据 | 14 | 2 | — | — |
| `docs/authz/CONTAINER_WRITE_SITES.csv` | ⑦ 事实/数据 | 10 | 1 | — | — |
| `docs/authz/OVERVIEW.md` | ⑦ 事实/数据 | 14 | 2 | — | — |
| `docs/authz/POLICY_MATRIX.csv` | ⑦ 事实/数据 | 22 | 3 | — | — |
| `docs/reference/MEK_GUI_SEMANTICS.md` | ⑦ 事实/数据 | 8 | 1 | — | — |
| `.alice-supervision/archive/2024-08-2024-09/README.md` | ⑧ 域外（⛔ 不进体系） | 49 | 0 | — | — |
| `.alice-supervision/archive/2024-08-2024-09/active-plan-draft-20260825-f1f6.md` | ⑧ 域外（⛔ 不进体系） | 1 | 0 | — | — |
| `.alice-supervision/archive/2024-08-2024-09/active-plan-draft-physics-fix-c-20260825.md` | ⑧ 域外（⛔ 不进体系） | 1 | 0 | — | — |
| `.alice-supervision/archive/2024-08-2024-09/active-plan.md` | ⑧ 域外（⛔ 不进体系） | 13 | 0 | — | — |
| `.alice-supervision/archive/2024-08-2024-09/bot-inventory-gui-investigation-report.md` | ⑧ 域外（⛔ 不进体系） | 1 | 0 | — | — |
| `.alice-supervision/archive/2024-08-2024-09/client-tests/2298dd3-p1-physics-observation-retest.md` | ⑧ 域外（⛔ 不进体系） | 1 | 0 | — | — |
| `.alice-supervision/archive/2024-08-2024-09/client-tests/2ef35e5-bot-death-filter-manual-test.md` | ⑧ 域外（⛔ 不进体系） | 1 | 0 | — | — |
| `.alice-supervision/archive/2024-08-2024-09/client-tests/36f4014-bot-inventory-sync.md` | ⑧ 域外（⛔ 不进体系） | 4 | 0 | — | — |
| `.alice-supervision/archive/2024-08-2024-09/client-tests/36f4014-bot-inventory-sync/evidence/V1-basic/evidence-report.md` | ⑧ 域外（⛔ 不进体系） | 72 | 0 | — | — |
| `.alice-supervision/archive/2024-08-2024-09/client-tests/36f4014-bot-inventory-sync/evidence/V2-multi-slot/evidence-report.md` | ⑧ 域外（⛔ 不进体系） | 72 | 0 | — | — |
| `.alice-supervision/archive/2024-08-2024-09/client-tests/36f4014-bot-inventory-sync/evidence/V3-selector/evidence-report.md` | ⑧ 域外（⛔ 不进体系） | 72 | 0 | — | — |
| `.alice-supervision/archive/2024-08-2024-09/client-tests/36f4014-bot-inventory-sync/evidence/evidence-report.md` | ⑧ 域外（⛔ 不进体系） | 73 | 0 | — | — |
| `.alice-supervision/archive/2024-08-2024-09/client-tests/36f4014-bot-inventory-sync/test-fail-root-cause.md` | ⑧ 域外（⛔ 不进体系） | 1 | 0 | — | — |
| `.alice-supervision/archive/2024-08-2024-09/client-tests/49be37e-a1-1.md` | ⑧ 域外（⛔ 不进体系） | 2 | 0 | — | — |
| `.alice-supervision/archive/2024-08-2024-09/client-tests/49be37e-a1-1/evidence/A1-permission-command/evidence-report.md` | ⑧ 域外（⛔ 不进体系） | 72 | 0 | — | — |
| `.alice-supervision/archive/2024-08-2024-09/client-tests/49be37e-a1-1/evidence/A2-normal-transfer/evidence-report.md` | ⑧ 域外（⛔ 不进体系） | 72 | 0 | — | — |
| `.alice-supervision/archive/2024-08-2024-09/client-tests/49be37e-a1-1/evidence/A3-in-transit/evidence-report.md` | ⑧ 域外（⛔ 不进体系） | 72 | 0 | — | — |
| `.alice-supervision/archive/2024-08-2024-09/client-tests/49be37e-a1-1/evidence/A4-capacity-rejection/evidence-report.md` | ⑧ 域外（⛔ 不进体系） | 72 | 0 | — | — |
| `.alice-supervision/archive/2024-08-2024-09/client-tests/49be37e-a1-1/evidence/A5-destination-conflict/evidence-report.md` | ⑧ 域外（⛔ 不进体系） | 72 | 0 | — | — |
| `.alice-supervision/archive/2024-08-2024-09/client-tests/49be37e-a1-1/evidence/A6-hazard/evidence-report.md` | ⑧ 域外（⛔ 不进体系） | 72 | 0 | — | — |
| `.alice-supervision/archive/2024-08-2024-09/client-tests/49be37e-a1-1/evidence/A7-restart/evidence-report.md` | ⑧ 域外（⛔ 不进体系） | 72 | 0 | — | — |
| `.alice-supervision/archive/2024-08-2024-09/client-tests/49be37e-a1-1/evidence/A8-abort/evidence-report.md` | ⑧ 域外（⛔ 不进体系） | 72 | 0 | — | — |
| `.alice-supervision/archive/2024-08-2024-09/client-tests/49be37e-a1-1/evidence/A9-chest-gui/evidence-report.md` | ⑧ 域外（⛔ 不进体系） | 72 | 0 | — | — |
| `.alice-supervision/archive/2024-08-2024-09/client-tests/49be37e-a1-1/evidence/evidence-report.md` | ⑧ 域外（⛔ 不进体系） | 72 | 0 | — | — |
| `.alice-supervision/archive/2024-08-2024-09/client-tests/5fa33ab-transfer-selector.md` | ⑧ 域外（⛔ 不进体系） | 1 | 0 | — | — |
| `.alice-supervision/archive/2024-08-2024-09/client-tests/5fa33ab-transfer-selector/evidence/R10-restart-expiry/evidence-report.md` | ⑧ 域外（⛔ 不进体系） | 72 | 0 | — | — |
| `.alice-supervision/archive/2024-08-2024-09/client-tests/5fa33ab-transfer-selector/evidence/R2-source-selection/evidence-report.md` | ⑧ 域外（⛔ 不进体系） | 72 | 0 | — | — |
| `.alice-supervision/archive/2024-08-2024-09/client-tests/5fa33ab-transfer-selector/evidence/R3-destination-selection/evidence-report.md` | ⑧ 域外（⛔ 不进体系） | 72 | 0 | — | — |
| `.alice-supervision/archive/2024-08-2024-09/client-tests/5fa33ab-transfer-selector/evidence/R4-invalid-endpoint/evidence-report.md` | ⑧ 域外（⛔ 不进体系） | 72 | 0 | — | — |
| `.alice-supervision/archive/2024-08-2024-09/client-tests/5fa33ab-transfer-selector/evidence/R9-status-clear-retain/evidence-report.md` | ⑧ 域外（⛔ 不进体系） | 72 | 0 | — | — |
| `.alice-supervision/archive/2024-08-2024-09/client-tests/5fa33ab-transfer-selector/evidence/evidence-report.md` | ⑧ 域外（⛔ 不进体系） | 73 | 0 | — | — |
| `.alice-supervision/archive/2024-08-2024-09/client-tests/65f4863-soft-path-test-tool-retest-result.md` | ⑧ 域外（⛔ 不进体系） | 2 | 0 | — | — |
| `.alice-supervision/archive/2024-08-2024-09/client-tests/65f4863-soft-path-test-tool-retest.md` | ⑧ 域外（⛔ 不进体系） | 1 | 0 | — | — |
| `.alice-supervision/archive/2024-08-2024-09/client-tests/8bb2d7b.md` | ⑧ 域外（⛔ 不进体系） | 5 | 0 | — | — |
| `.alice-supervision/archive/2024-08-2024-09/client-tests/a7e02fd-transfer-selector.md` | ⑧ 域外（⛔ 不进体系） | 2 | 0 | — | — |
| `.alice-supervision/archive/2024-08-2024-09/client-tests/a7e02fd-transfer-selector/evidence/E1-item-permission/evidence-report.md` | ⑧ 域外（⛔ 不进体系） | 72 | 0 | — | — |
| `.alice-supervision/archive/2024-08-2024-09/client-tests/a7e02fd-transfer-selector/evidence/E10-restart-expiry/evidence-report.md` | ⑧ 域外（⛔ 不进体系） | 72 | 0 | — | — |
| `.alice-supervision/archive/2024-08-2024-09/client-tests/a7e02fd-transfer-selector/evidence/E2-source-selection/evidence-report.md` | ⑧ 域外（⛔ 不进体系） | 72 | 0 | — | — |
| `.alice-supervision/archive/2024-08-2024-09/client-tests/a7e02fd-transfer-selector/evidence/E3-destination-selection/evidence-report.md` | ⑧ 域外（⛔ 不进体系） | 72 | 0 | — | — |
| `.alice-supervision/archive/2024-08-2024-09/client-tests/a7e02fd-transfer-selector/evidence/E4-invalid-endpoint/evidence-report.md` | ⑧ 域外（⛔ 不进体系） | 72 | 0 | — | — |
| `.alice-supervision/archive/2024-08-2024-09/client-tests/a7e02fd-transfer-selector/evidence/E5-same-cross/evidence-report.md` | ⑧ 域外（⛔ 不进体系） | 72 | 0 | — | — |
| `.alice-supervision/archive/2024-08-2024-09/client-tests/a7e02fd-transfer-selector/evidence/E6-default-submit/evidence-report.md` | ⑧ 域外（⛔ 不进体系） | 72 | 0 | — | — |
| `.alice-supervision/archive/2024-08-2024-09/client-tests/a7e02fd-transfer-selector/evidence/E7-explicit-drift/evidence-report.md` | ⑧ 域外（⛔ 不进体系） | 72 | 0 | — | — |
| `.alice-supervision/archive/2024-08-2024-09/client-tests/a7e02fd-transfer-selector/evidence/E8-command-consistency/evidence-report.md` | ⑧ 域外（⛔ 不进体系） | 72 | 0 | — | — |
| `.alice-supervision/archive/2024-08-2024-09/client-tests/a7e02fd-transfer-selector/evidence/E9-status-clear-retain/evidence-report.md` | ⑧ 域外（⛔ 不进体系） | 72 | 0 | — | — |
| `.alice-supervision/archive/2024-08-2024-09/client-tests/a7e02fd-transfer-selector/evidence/evidence-report.md` | ⑧ 域外（⛔ 不进体系） | 73 | 0 | — | — |
| `.alice-supervision/archive/2024-08-2024-09/client-tests/a8b84b4-bot-equipment-rendering.md` | ⑧ 域外（⛔ 不进体系） | 1 | 0 | — | — |
| `.alice-supervision/archive/2024-08-2024-09/client-tests/a8b84b4-bot-equipment-rendering/evidence/V1-basic/evidence-report.md` | ⑧ 域外（⛔ 不进体系） | 72 | 0 | — | — |
| `.alice-supervision/archive/2024-08-2024-09/client-tests/a8b84b4-bot-equipment-rendering/evidence/V2-different-items/evidence-report.md` | ⑧ 域外（⛔ 不进体系） | 72 | 0 | — | — |
| `.alice-supervision/archive/2024-08-2024-09/client-tests/a8b84b4-bot-equipment-rendering/evidence/V3-multi-slot/evidence-report.md` | ⑧ 域外（⛔ 不进体系） | 72 | 0 | — | — |
| `.alice-supervision/archive/2024-08-2024-09/client-tests/a8b84b4-bot-equipment-rendering/evidence/evidence-report.md` | ⑧ 域外（⛔ 不进体系） | 73 | 0 | — | — |
| `.alice-supervision/archive/2024-08-2024-09/client-tests/ab510fd-p1-client-sync-fix-retest.md` | ⑧ 域外（⛔ 不进体系） | 2 | 0 | — | — |
| `.alice-supervision/archive/2024-08-2024-09/client-tests/evidence/8bb2d7b/evidence-report.md` | ⑧ 域外（⛔ 不进体系） | 72 | 0 | — | — |
| `.alice-supervision/archive/2024-08-2024-09/client-tests/f0ef6fc-bot-inventory-gui-retest-3.md` | ⑧ 域外（⛔ 不进体系） | 1 | 0 | — | — |
| `.alice-supervision/archive/2024-08-2024-09/client-tests/f0ef6fc-bot-inventory-gui-retest-4.md` | ⑧ 域外（⛔ 不进体系） | 1 | 0 | — | — |
| `.alice-supervision/archive/2024-08-2024-09/client-tests/f0ef6fc-bot-inventory-gui-retest-5.md` | ⑧ 域外（⛔ 不进体系） | 1 | 0 | — | — |
| `.alice-supervision/archive/2024-08-2024-09/client-tests/f0ef6fc-bot-inventory-gui-retest-6.md` | ⑧ 域外（⛔ 不进体系） | 1 | 0 | — | — |
| `.alice-supervision/archive/2024-08-2024-09/client-tests/f0ef6fc-bot-inventory-gui-retest-7.md` | ⑧ 域外（⛔ 不进体系） | 1 | 0 | — | — |
| `.alice-supervision/archive/2024-08-2024-09/client-tests/f0ef6fc-bot-inventory-gui-retest-8.md` | ⑧ 域外（⛔ 不进体系） | 1 | 0 | — | — |
| `.alice-supervision/archive/2024-08-2024-09/client-tests/f0ef6fc-bot-inventory-gui-retest.md` | ⑧ 域外（⛔ 不进体系） | 1 | 0 | — | — |
| `.alice-supervision/archive/2024-08-2024-09/client-tests/f0ef6fc-bot-inventory-gui.md` | ⑧ 域外（⛔ 不进体系） | 1 | 0 | — | — |
| `.alice-supervision/archive/2024-08-2024-09/client-tests/f0ef6fc-bot-inventory-gui/evidence/G1-open/evidence-report.md` | ⑧ 域外（⛔ 不进体系） | 72 | 0 | — | — |
| `.alice-supervision/archive/2024-08-2024-09/client-tests/f0ef6fc-bot-inventory-gui/evidence/G8-task-guard/evidence-report.md` | ⑧ 域外（⛔ 不进体系） | 72 | 0 | — | — |
| `.alice-supervision/archive/2024-08-2024-09/client-tests/f0ef6fc-bot-inventory-gui/evidence/G9-idle-interact/evidence-report.md` | ⑧ 域外（⛔ 不进体系） | 72 | 0 | — | — |
| `.alice-supervision/archive/2024-08-2024-09/client-tests/f0ef6fc-bot-inventory-gui/evidence/evidence-report.md` | ⑧ 域外（⛔ 不进体系） | 73 | 0 | — | — |
| `.alice-supervision/archive/2024-08-2024-09/client-tests/f33292c-mainhand-residue-retest.md` | ⑧ 域外（⛔ 不进体系） | 1 | 0 | — | — |
| `.alice-supervision/archive/2024-08-2024-09/client-tests/f655be2-a1-1-client.md` | ⑧ 域外（⛔ 不进体系） | 1 | 0 | — | — |
| `.alice-supervision/archive/2024-08-2024-09/client-tests/f655be2-a1-1-client/evidence/C1-external-mutation/evidence-report.md` | ⑧ 域外（⛔ 不进体系） | 72 | 0 | — | — |
| `.alice-supervision/archive/2024-08-2024-09/client-tests/f655be2-a1-1-client/evidence/C2-hazard-interrupt/evidence-report.md` | ⑧ 域外（⛔ 不进体系） | 72 | 0 | — | — |
| `.alice-supervision/archive/2024-08-2024-09/client-tests/f655be2-a1-1-client/evidence/C3-restart-takeover/evidence-report.md` | ⑧ 域外（⛔ 不进体系） | 72 | 0 | — | — |
| `.alice-supervision/archive/2024-08-2024-09/client-tests/f655be2-a1-1-client/evidence/C4-vanilla-gui/evidence-report.md` | ⑧ 域外（⛔ 不进体系） | 72 | 0 | — | — |
| `.alice-supervision/archive/2024-08-2024-09/client-tests/f655be2-a1-1-client/evidence/evidence-report.md` | ⑧ 域外（⛔ 不进体系） | 72 | 0 | — | — |
| `.alice-supervision/archive/2024-08-2024-09/container-mod-compatibility.md` | ⑧ 域外（⛔ 不进体系） | 1 | 0 | — | — |
| `.alice-supervision/archive/2024-08-2024-09/container-quick-move-explained.md` | ⑧ 域外（⛔ 不进体系） | 3 | 0 | — | — |
| `.alice-supervision/archive/2024-08-2024-09/dsh-3082-e2e-verification-20260824.md` | ⑧ 域外（⛔ 不进体系） | 1 | 0 | — | — |
| `.alice-supervision/archive/2024-08-2024-09/dsh-baseline-jsx-fix-result-20260824.md` | ⑧ 域外（⛔ 不进体系） | 5 | 0 | — | — |
| `.alice-supervision/archive/2024-08-2024-09/dsh-baseline-verify-report-20260824.md` | ⑧ 域外（⛔ 不进体系） | 5 | 0 | — | — |
| `.alice-supervision/archive/2024-08-2024-09/dsh-build-chain-freeze-report-20260824.md` | ⑧ 域外（⛔ 不进体系） | 2 | 0 | — | — |
| `.alice-supervision/archive/2024-08-2024-09/dsh-delivery-stuck-fix-workpack-20260825.md` | ⑧ 域外（⛔ 不进体系） | 1 | 0 | — | — |
| `.alice-supervision/archive/2024-08-2024-09/dsh-maintainer-baseline-jsx-fix-20260824.md` | ⑧ 域外（⛔ 不进体系） | 2 | 0 | — | — |
| `.alice-supervision/archive/2024-08-2024-09/dsh-maintainer-jsx-fix-review-20260824.md` | ⑧ 域外（⛔ 不进体系） | 2 | 0 | — | — |
| `.alice-supervision/archive/2024-08-2024-09/dsh-maintainer-next-action-20260824.md` | ⑧ 域外（⛔ 不进体系） | 3 | 0 | — | — |
| `.alice-supervision/archive/2024-08-2024-09/dsh-maintainer-p0-closure-20260824.md` | ⑧ 域外（⛔ 不进体系） | 1 | 0 | — | — |
| `.alice-supervision/archive/2024-08-2024-09/dsh-maintainer-p0-deploy-3083-20260824.md` | ⑧ 域外（⛔ 不进体系） | 2 | 0 | — | — |
| `.alice-supervision/archive/2024-08-2024-09/dsh-maintainer-p0-implementation-20260824.md` | ⑧ 域外（⛔ 不进体系） | 3 | 0 | — | — |
| `.alice-supervision/archive/2024-08-2024-09/dsh-maintainer-workflow-adjustment-spec-20260824.md` | ⑧ 域外（⛔ 不进体系） | 3 | 0 | — | — |
| `.alice-supervision/archive/2024-08-2024-09/dsh-minimal-wake-request-input-20260825.md` | ⑧ 域外（⛔ 不进体系） | 1 | 0 | — | — |
| `.alice-supervision/archive/2024-08-2024-09/dsh-p0-implementation-result-20260824.md` | ⑧ 域外（⛔ 不进体系） | 6 | 0 | — | — |
| `.alice-supervision/archive/2024-08-2024-09/dsh-p0-second-review-20260824.md` | ⑧ 域外（⛔ 不进体系） | 2 | 0 | — | — |
| `.alice-supervision/archive/2024-08-2024-09/dsh-p0-second-review-closed-20260824.md` | ⑧ 域外（⛔ 不进体系） | 2 | 0 | — | — |
| `.alice-supervision/archive/2024-08-2024-09/dsh-workflow-five-questions-answer-20260824.md` | ⑧ 域外（⛔ 不进体系） | 2 | 0 | — | — |
| `.alice-supervision/archive/2024-08-2024-09/dsh-workflow-plan-revision-notes-20260824.md` | ⑧ 域外（⛔ 不进体系） | 1 | 0 | — | — |
| `.alice-supervision/archive/2024-08-2024-09/dsh-workflow-reliability-plan-20260824.md` | ⑧ 域外（⛔ 不进体系） | 12 | 0 | — | — |
| `.alice-supervision/archive/2024-08-2024-09/memory-handoff-20260824.md` | ⑧ 域外（⛔ 不进体系） | 2 | 0 | — | — |
| `.alice-supervision/archive/2024-08-2024-09/memory-handoff-message-20260824.md` | ⑧ 域外（⛔ 不进体系） | 1 | 0 | — | — |
| `.alice-supervision/archive/2024-08-2024-09/progress-snapshot-20260824.md` | ⑧ 域外（⛔ 不进体系） | 2 | 0 | — | — |
| `.alice-supervision/archive/2024-08-2024-09/progress-snapshot-20260825-transfer.md` | ⑧ 域外（⛔ 不进体系） | 1 | 0 | — | — |
| `.alice-supervision/archive/2024-08-2024-09/reviews/20220822-5fa33ab-client-regression-final-review.md` | ⑧ 域外（⛔ 不进体系） | 1 | 0 | — | — |
| `.alice-supervision/archive/2024-08-2024-09/reviews/20220822-5fa33ab-client-regression-review-rerun.md` | ⑧ 域外（⛔ 不进体系） | 1 | 0 | — | — |
| `.alice-supervision/archive/2024-08-2024-09/reviews/20260821-276339f.md` | ⑧ 域外（⛔ 不进体系） | 1 | 0 | — | — |
| `.alice-supervision/archive/2024-08-2024-09/reviews/20260821-8bb2d7b-client-test.md` | ⑧ 域外（⛔ 不进体系） | 1 | 0 | — | — |
| `.alice-supervision/archive/2024-08-2024-09/reviews/20260821-8bb2d7b.md` | ⑧ 域外（⛔ 不进体系） | 4 | 0 | — | — |
| `.alice-supervision/archive/2024-08-2024-09/reviews/20260821-a1-1-transfer-draft-audit.md` | ⑧ 域外（⛔ 不进体系） | 1 | 0 | — | — |
| `.alice-supervision/archive/2024-08-2024-09/reviews/20260821-ae32c4a.md` | ⑧ 域外（⛔ 不进体系） | 1 | 0 | — | — |
| `.alice-supervision/archive/2024-08-2024-09/reviews/20260821-c1-next-mainline-route.md` | ⑧ 域外（⛔ 不进体系） | 2 | 0 | — | — |
| `.alice-supervision/archive/2024-08-2024-09/reviews/20260821-d1def08.md` | ⑧ 域外（⛔ 不进体系） | 1 | 0 | — | — |
| `.alice-supervision/archive/2024-08-2024-09/reviews/20260821-f9d89da.md` | ⑧ 域外（⛔ 不进体系） | 1 | 0 | — | — |
| `.alice-supervision/archive/2024-08-2024-09/reviews/20260821-github-actions-ci.md` | ⑧ 域外（⛔ 不进体系） | 1 | 0 | — | — |
| `.alice-supervision/archive/2024-08-2024-09/reviews/20260821-interface-c1-maintenance-f1-f4-review.md` | ⑧ 域外（⛔ 不进体系） | 2 | 0 | — | — |
| `.alice-supervision/archive/2024-08-2024-09/reviews/20260821-interface-c1-maintenance-route.md` | ⑧ 域外（⛔ 不进体系） | 1 | 0 | — | — |
| `.alice-supervision/archive/2024-08-2024-09/reviews/20260821-inventory-transfer-transaction-a0-review.md` | ⑧ 域外（⛔ 不进体系） | 1 | 0 | — | — |
| `.alice-supervision/archive/2024-08-2024-09/reviews/20260821-original-chest-bot-transfer-a1-route-review.md` | ⑧ 域外（⛔ 不进体系） | 1 | 0 | — | — |
| `.alice-supervision/archive/2024-08-2024-09/reviews/20260822-370ff343-e7-item-argument-review.md` | ⑧ 域外（⛔ 不进体系） | 1 | 0 | — | — |
| `.alice-supervision/archive/2024-08-2024-09/reviews/20260822-3fcabab-rightclick-fix-review.md` | ⑧ 域外（⛔ 不进体系） | 2 | 0 | — | — |
| `.alice-supervision/archive/2024-08-2024-09/reviews/20260822-49be37e-a1-1-review.md` | ⑧ 域外（⛔ 不进体系） | 3 | 0 | — | — |
| `.alice-supervision/archive/2024-08-2024-09/reviews/20260822-5fa33ab-client-regression-review.md` | ⑧ 域外（⛔ 不进体系） | 1 | 0 | — | — |
| `.alice-supervision/archive/2024-08-2024-09/reviews/20260822-a1-1-final-acceptance.md` | ⑧ 域外（⛔ 不进体系） | 2 | 0 | — | — |
| `.alice-supervision/archive/2024-08-2024-09/reviews/20260822-a7e02fd-client-failure-review.md` | ⑧ 域外（⛔ 不进体系） | 1 | 0 | — | — |
| `.alice-supervision/archive/2024-08-2024-09/reviews/20260822-a7e02fd-transfer-selector-review.md` | ⑧ 域外（⛔ 不进体系） | 2 | 0 | — | — |
| `.alice-supervision/archive/2024-08-2024-09/reviews/20260822-entity-selector-plan-review.md` | ⑧ 域外（⛔ 不进体系） | 1 | 0 | — | — |
| `.alice-supervision/archive/2024-08-2024-09/reviews/20260822-entity-selector-test-tool-request.md` | ⑧ 域外（⛔ 不进体系） | 1 | 0 | — | — |
| `.alice-supervision/archive/2024-08-2024-09/reviews/20260822-workflow-audit-e7.md` | ⑧ 域外（⛔ 不进体系） | 1 | 0 | — | — |
| `.alice-supervision/archive/2024-08-2024-09/reviews/20260824-bot-inventory-interaction-final-diagnosis.md` | ⑧ 域外（⛔ 不进体系） | 2 | 0 | — | — |
| `.alice-supervision/archive/2024-08-2024-09/reviews/20260824-bot-mainhand-residue-final-acceptance.md` | ⑧ 域外（⛔ 不进体系） | 2 | 0 | — | — |
| `.alice-supervision/archive/2024-08-2024-09/reviews/dsh-build-chain-source-decision-20260824.md` | ⑧ 域外（⛔ 不进体系） | 1 | 0 | — | — |
| `.alice-supervision/archive/legacy-2026-08/ARCHITECTURE.md` | ⑧ 域外（⛔ 不进体系） | 5 | 0 | — | — |
| `.alice-supervision/archive/legacy-2026-08/DEVELOPMENT-GUIDE.md` | ⑧ 域外（⛔ 不进体系） | 2 | 0 | — | — |
| `.alice-supervision/archive/legacy-2026-08/MEMO.md` | ⑧ 域外（⛔ 不进体系） | 5 | 0 | — | — |
| `.alice-supervision/archive/legacy-2026-08/PROJECT-CLEANUP-2025.md` | ⑧ 域外（⛔ 不进体系） | 1 | 0 | — | — |
| `.alice-supervision/archive/legacy-2026-08/PROJECT-STATUS.md` | ⑧ 域外（⛔ 不进体系） | 1 | 0 | — | — |
| `.alice-supervision/archive/legacy-2026-08/QUICKSTART.md` | ⑧ 域外（⛔ 不进体系） | 5 | 0 | — | — |
| `.alice-supervision/archive/legacy-2026-08/README.md` | ⑧ 域外（⛔ 不进体系） | 49 | 0 | — | — |
| `.alice-supervision/archive/legacy-2026-08/TASK-BACKLOG.md` | ⑧ 域外（⛔ 不进体系） | 1 | 0 | — | — |
| `.alice-supervision/archive/legacy-2026-08/debugging-methodology-reflection.md` | ⑧ 域外（⛔ 不进体系） | 1 | 0 | — | — |
| `.alice-supervision/archive/legacy-2026-08/inventory-helper-design.md` | ⑧ 域外（⛔ 不进体系） | 1 | 0 | — | — |
| `.alice-supervision/archive/legacy-2026-08/inventory-system-investigation-summary.md` | ⑧ 域外（⛔ 不进体系） | 1 | 0 | — | — |
| `.alice-supervision/archive/legacy-2026-08/lumber-investigation-plan.md` | ⑧ 域外（⛔ 不进体系） | 1 | 0 | — | — |
| `.alice-supervision/archive/legacy-2026-08/lumber-movement-analysis-2025.md` | ⑧ 域外（⛔ 不进体系） | 1 | 0 | — | — |
| `.alice-supervision/archive/legacy-2026-08/lumber-obstruction-clearing-fix-2025.md` | ⑧ 域外（⛔ 不进体系） | 1 | 0 | — | — |
| `.alice-supervision/archive/legacy-2026-08/lumber-problem-discussion.md` | ⑧ 域外（⛔ 不进体系） | 1 | 0 | — | — |
| `.alice-supervision/archive/legacy-2026-08/minecraft-cursor-item-mechanism-research.md` | ⑧ 域外（⛔ 不进体系） | 1 | 0 | — | — |
| `.alice-supervision/archive/legacy-2026-08/mining-optimization-2025.md` | ⑧ 域外（⛔ 不进体系） | 1 | 0 | — | — |
| `.alice-supervision/archive/legacy-2026-08/movement-system-analysis.md` | ⑧ 域外（⛔ 不进体系） | 1 | 0 | — | — |
| `.alice-supervision/archive/legacy-2026-08/phase-3-complete-overview.md` | ⑧ 域外（⛔ 不进体系） | 1 | 0 | — | — |
| `.alice-supervision/archive/legacy-2026-08/phase-3.1-time-budget-complete.md` | ⑧ 域外（⛔ 不进体系） | 2 | 0 | — | — |
| `.alice-supervision/archive/legacy-2026-08/phase-3.2-movement-provider-complete.md` | ⑧ 域外（⛔ 不进体系） | 2 | 0 | — | — |
| `.alice-supervision/archive/legacy-2026-08/phase-3.3-unified-pathplanner-complete.md` | ⑧ 域外（⛔ 不进体系） | 1 | 0 | — | — |
| `.alice-supervision/archive/legacy-2026-08/refactoring/astar-movement-integration-plan.md` | ⑧ 域外（⛔ 不进体系） | 1 | 0 | — | — |
| `.alice-supervision/archive/legacy-2026-08/refactoring/bot-manager-cleanup-2025.md` | ⑧ 域外（⛔ 不进体系） | 2 | 0 | — | — |
| `.alice-supervision/archive/legacy-2026-08/refactoring/movement-primitives-phase1.md` | ⑧ 域外（⛔ 不进体系） | 4 | 0 | — | — |
| `.alice-supervision/archive/legacy-2026-08/refactoring/pathfinding-evaluation-report.md` | ⑧ 域外（⛔ 不进体系） | 1 | 0 | — | — |
| `.alice-supervision/archive/legacy-2026-08/refactoring/pathfinding-refactor-plan.md` | ⑧ 域外（⛔ 不进体系） | 1 | 0 | — | — |
| `.alice-supervision/archive/legacy-2026-08/refactoring/phase2a-complete.md` | ⑧ 域外（⛔ 不进体系） | 1 | 0 | — | — |
| `.alice-supervision/archive/legacy-2026-08/refactoring/phase2a-interface-complete.md` | ⑧ 域外（⛔ 不进体系） | 1 | 0 | — | — |
| `.alice-supervision/archive/legacy-2026-08/refactoring/phase2a-summary.md` | ⑧ 域外（⛔ 不进体系） | 2 | 0 | — | — |
| `.alice-supervision/archive/legacy-2026-08/refactoring/phase2b-complete.md` | ⑧ 域外（⛔ 不进体系） | 1 | 0 | — | — |
| `.alice-supervision/improvements/follow-task-vertical-tolerance.md` | ⑧ 域外（⛔ 不进体系） | 1 | 0 | — | — |
| `.alice-supervision/skills-manifest.yml` | ⑧ 域外（⛔ 不进体系） | 13 | 0 | — | — |
| `.alice-supervision/skills/README.md` | ⑧ 域外（⛔ 不进体系） | 49 | 0 | — | 是 |
| `.alice-supervision/skills/alice-baritone-kernel-alignment.skill.md` | ⑧ 域外（⛔ 不进体系） | 3 | 0 | — | — |
| `.alice-supervision/skills/alice-client-artifact-acceptance.skill.md` | ⑧ 域外（⛔ 不进体系） | 1 | 0 | — | — |
| `.alice-supervision/skills/alice-discussion-before-repair.skill.md` | ⑧ 域外（⛔ 不进体系） | 2 | 0 | — | — |
| `.alice-supervision/skills/alice-inventory-transaction-conservation.skill.md` | ⑧ 域外（⛔ 不进体系） | 1 | 0 | — | — |
| `.alice-supervision/skills/alice-path-planning-execution-contract.skill.md` | ⑧ 域外（⛔ 不进体系） | 1 | 0 | — | — |
| `.alice-supervision/skills/alice-scene-based-testing.skill.md` | ⑧ 域外（⛔ 不进体系） | 3 | 0 | — | — |
| `.alice-supervision/skills/alice-session-memory-and-direction.skill.md` | ⑧ 域外（⛔ 不进体系） | 1 | 0 | — | — |
| `.alice-supervision/skills/alice-task-lifecycle-and-recovery.skill.md` | ⑧ 域外（⛔ 不进体系） | 1 | 0 | — | — |
| `.alice-supervision/skills/alice-windows-client-collaboration.skill.md` | ⑧ 域外（⛔ 不进体系） | 1 | 0 | — | — |
| `.alice-supervision/skills/debugging-root-cause-analysis.skill.md` | ⑧ 域外（⛔ 不进体系） | 4 | 0 | — | — |
| `.alice-supervision/skills/failure-pattern-recognition.skill.md` | ⑧ 域外（⛔ 不进体系） | 1 | 0 | — | — |
| `.alice-supervision/skills/forge-blockpos-mutability.skill.md` | ⑧ 域外（⛔ 不进体系） | 7 | 0 | — | — |
| `.alice-supervision/skills/forge-capability-adapter-boundary.skill.md` | ⑧ 域外（⛔ 不进体系） | 1 | 0 | — | — |
| `.alice-supervision/skills/forge-container-menu-protocol.skill.md` | ⑧ 域外（⛔ 不进体系） | 1 | 0 | — | — |
| `.alice-supervision/skills/forge-entity-physics-collision.skill.md` | ⑧ 域外（⛔ 不进体系） | 1 | 0 | — | — |
| `.alice-supervision/skills/forge-entity-sync-broadcast.skill.md` | ⑧ 域外（⛔ 不进体系） | 1 | 0 | — | — |
| `.alice-supervision/skills/forge-event-priority-cancel.skill.md` | ⑧ 域外（⛔ 不进体系） | 1 | 0 | — | — |
| `.alice-supervision/skills/forge-fakeplayer-lifecycle.skill.md` | ⑧ 域外（⛔ 不进体系） | 2 | 0 | — | — |
| `.alice-supervision/skills/large-refactor-survey-and-verify.skill.md` | ⑧ 域外（⛔ 不进体系） | 4 | 0 | — | — |
| `.alice-supervision/skills/minecraft-client-server-sync.skill.md` | ⑧ 域外（⛔ 不进体系） | 1 | 0 | — | — |
| `.alice-supervision/skills/minimal-implementation-planning.skill.md` | ⑧ 域外（⛔ 不进体系） | 3 | 0 | — | — |
| `docs/archive/legacy-2026-08/AI_PLAYER_DESIGN.md` | ⑧ 域外（⛔ 不进体系） | 5 | 0 | — | — |
| `docs/archive/legacy-2026-08/BOT_CONTROLLER_QUICK_TEST.md` | ⑧ 域外（⛔ 不进体系） | 1 | 0 | — | — |
| `docs/archive/legacy-2026-08/EXECUTION_FRAMEWORK.md` | ⑧ 域外（⛔ 不进体系） | 5 | 1 | — | — |
| `docs/archive/legacy-2026-08/HANDOVER_20260907.md` | ⑧ 域外（⛔ 不进体系） | 1 | 0 | 已实现 | — |
| `docs/archive/legacy-2026-08/M0_ARCHITECTURE.md` | ⑧ 域外（⛔ 不进体系） | 1 | 0 | — | — |
| `docs/archive/legacy-2026-08/MINETASK_INTERNAL_CONTRACT_AUDIT.md` | ⑧ 域外（⛔ 不进体系） | 2 | 0 | — | — |
| `docs/archive/legacy-2026-08/MINETASK_MOVEMENT_MVP_DESIGN.md` | ⑧ 域外（⛔ 不进体系） | 2 | 0 | — | — |
| `docs/archive/legacy-2026-08/MINETASK_RECOVERY_CONTRACT.md` | ⑧ 域外（⛔ 不进体系） | 4 | 0 | — | — |
| `docs/archive/legacy-2026-08/MINETASK_WORLD_EDIT_TEST_SCENES.md` | ⑧ 域外（⛔ 不进体系） | 1 | 0 | — | — |
| `docs/archive/legacy-2026-08/MINING_SAFETY_AND_PLANNING.md` | ⑧ 域外（⛔ 不进体系） | 1 | 0 | 已实现 | — |
| `docs/archive/legacy-2026-08/MOVEMENT_EXPERIMENT_6_RESULT_CONTRACT.md` | ⑧ 域外（⛔ 不进体系） | 1 | 0 | — | — |
| `docs/archive/legacy-2026-08/MOVEMENT_PHYSICS_EXPERIMENT_1.md` | ⑧ 域外（⛔ 不进体系） | 2 | 0 | — | — |
| `docs/archive/legacy-2026-08/MOVEMENT_SYSTEM_ARCHITECTURE.md` | ⑧ 域外（⛔ 不进体系） | 2 | 0 | — | — |
| `docs/archive/legacy-2026-08/PATHING_REFACTOR.md` | ⑧ 域外（⛔ 不进体系） | 6 | 0 | 已实现 | — |
| `docs/archive/legacy-2026-08/PRODUCT_ARCHITECTURE_ROADMAP.md` | ⑧ 域外（⛔ 不进体系） | 13 | 0 | — | — |
| `docs/archive/legacy-2026-08/R2C_BARITONE_AUDIT.md` | ⑧ 域外（⛔ 不进体系） | 2 | 0 | — | — |
| `docs/archive/legacy-2026-08/R2C_IMPLEMENTATION_REPORT.md` | ⑧ 域外（⛔ 不进体系） | 1 | 0 | — | — |
| `docs/archive/legacy-2026-08/README.md` | ⑧ 域外（⛔ 不进体系） | 49 | 0 | — | — |
| `docs/archive/legacy-2026-08/TASK_OUTCOME_CONTRACT.md` | ⑧ 域外（⛔ 不进体系） | 2 | 0 | — | — |
| `docs/archive/legacy-design/AI_PLAYER_NOTES.md` | ⑧ 域外（⛔ 不进体系） | 2 | 0 | — | — |
| `docs/archive/legacy-design/BOT_PHYSICS_DIAGNOSTICS.md` | ⑧ 域外（⛔ 不进体系） | 1 | 0 | — | — |
| `docs/archive/legacy-design/IS_EFFECTIVE_AI_ANALYSIS.md` | ⑧ 域外（⛔ 不进体系） | 1 | 0 | — | — |
| `docs/archive/legacy-design/MINECRAFT_PLAYER_PHYSICS_EXPLAINED.md` | ⑧ 域外（⛔ 不进体系） | 1 | 0 | — | — |
| `docs/archive/legacy-design/README.md` | ⑧ 域外（⛔ 不进体系） | 49 | 0 | — | — |
| `docs/archive/legacy-testing/BOT_CONTROL_DESIGN.md` | ⑧ 域外（⛔ 不进体系） | 1 | 0 | — | — |
| `docs/archive/legacy-testing/README.md` | ⑧ 域外（⛔ 不进体系） | 49 | 0 | — | — |
| `docs/archive/legacy-testing/WINDOWS_SYNC_CHECKLIST.md` | ⑧ 域外（⛔ 不进体系） | 1 | 0 | — | — |
| `docs/archive/legacy-workflow/HANDOVER.md` | ⑧ 域外（⛔ 不进体系） | 57 | 1 | — | — |
| `docs/archive/legacy-workflow/README.md` | ⑧ 域外（⛔ 不进体系） | 49 | 0 | — | — |
| `docs/archive/legacy-workflow/SUPERVISION_PROTOCOL.md` | ⑧ 域外（⛔ 不进体系） | 8 | 0 | — | — |
| `docs/archive/legacy-workflow/SUPERVISOR_HANDOFF.md` | ⑧ 域外（⛔ 不进体系） | 10 | 0 | 已实现 | — |
| `docs/archive/legacy-workflow/supervision/ACTIVE_PLAN_TEMPLATE.md` | ⑧ 域外（⛔ 不进体系） | 3 | 0 | — | — |
| `docs/archive/legacy-workflow/supervision/CLIENT_TEST_TEMPLATE.md` | ⑧ 域外（⛔ 不进体系） | 3 | 0 | — | — |
| `docs/archive/legacy-workflow/supervision/RESEARCH_REPORT_TEMPLATE.md` | ⑧ 域外（⛔ 不进体系） | 2 | 0 | — | — |
| `docs/archive/legacy-workflow/supervision/RESEARCH_TASK_TEMPLATE.txt` | ⑧ 域外（⛔ 不进体系） | 2 | 0 | — | — |
| `docs/archive/legacy-workflow/supervision/REVIEW_TEMPLATE.md` | ⑧ 域外（⛔ 不进体系） | 2 | 0 | — | — |
| `src/main/resources/assets/alice/textures/CREDITS.md` | ⑧ 域外（⛔ 不进体系） | 9 | 0 | — | — |
| `steward-entry/entry.txt` | ⑧ 域外（⛔ 不进体系） | 3 | 0 | — | — |
| `tools/agent-presets/alice-forge-assistant/agent.cordis.yml` | ⑧ 域外（⛔ 不进体系） | 8 | 0 | — | — |
| `tools/agent-presets/alice-forge-assistant/persona.md` | ⑧ 域外（⛔ 不进体系） | 3 | 0 | — | — |
| `tools/agent-presets/alice-forge-assistant/preset.yml` | ⑧ 域外（⛔ 不进体系） | 5 | 0 | — | — |
| `tools/client-agent/make-preset-package.py` | ⑧ 域外（⛔ 不进体系） | 3 | 0 | — | — |
| `tools/client-agent/presets/alice-client-master/agent.cordis.yml` | ⑧ 域外（⛔ 不进体系） | 8 | 0 | — | — |
| `tools/client-agent/presets/alice-client-master/preset.yml` | ⑧ 域外（⛔ 不进体系） | 5 | 0 | — | — |
| `tools/cloud-tunnel-README.txt` | ⑧ 域外（⛔ 不进体系） | 3 | 0 | — | — |
| `.tmp-fix2.py` | ⑨ 待删（一次性脚本） | 4 | 0 | — | — |
| `.tmp-fix9.py` | ⑨ 待删（一次性脚本） | 5 | 0 | — | — |

<!-- CLEANUP_ROWS 447 -->

