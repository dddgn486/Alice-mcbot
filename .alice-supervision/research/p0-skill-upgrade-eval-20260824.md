# P0 Skill 升级评估（P0-S5 语义一致性核对）

- 执行角色：Alice 主开发（按 `20260824-skill-upgrade-p1-git-mcp-line-20260824.md` P0-S1~S4）
- 日期：2026-08-24
- 改动基线：`ab510fd`（本次只改 3 个 skill 文档 + 本评估文件，未改任何业务代码/manifest/state-machine）
- 依据：`skill-upgrade-p1-git-mcp-line-20260824.md` §2.1~§2.4（P0-S1/S2/S3/S4）

## 改动点（每文件）

### 1. `.alice-supervision/skills/debugging-root-cause-analysis.skill.md`
- S1：第一行新增 YAML frontmatter（`---` + `name: debugging-root-cause-analysis` + `description:`），`name` 与 skills-manifest.yml line 9 的 id 完全一致；保留原 `# Skill: Debugging Root Cause Analysis` 标题。
- S2：新增 `## How to Use This Skill (Progressive Disclosure)` 入口指引（先读顶部 → 判定适用 → 按需展开）；深度小节标题追加 `(expand on demand)` 标记：`## Core Methods`、`### Method 1..4` 各模板、`## Anti-Patterns`、`## Checklist Before Claiming "Root Cause Found"`、`## Integration with Alice Workflow`、`## Example: P1 Physics Investigation`。全部既有方法/示例/模板内容逐字保留，未删除或改语义。
- S3：`## Checklist Before Claiming "Root Cause Found"` 升级为 eval 清单样式——原 5 个勾选条目逐字保留于 `## Eval Checklist` 中，追加 P0-S3 自检 3 项（可证伪假设+定向测试、排除替代解释、规避 Anti-Patterns），末尾 `→ 全部勾选 = PASS；任一项未勾选 = FAIL` + `Self-check result`。

### 2. `.alice-supervision/skills/test-coverage-matrix.skill.md`
- S1：frontmatter（`name: test-coverage-matrix`，匹配 manifest line 17）；保留 `# Skill: Test Coverage Matrix`。
- S2：新增 `## How to Use This Skill (Progressive Disclosure)`；深度小节加 `(expand on demand)`：Server-Side Limitations、Test Pyramid、Client Test Decision Tree、Client Test Matrix Template、Common Mistakes、Integration、Example。
- S3：`## Checklist Before Marking Feature "Complete"` 升级为 eval——原 6 条目逐字保留，追加计划自检（决策树 Q1–Q4、服务端不可证项标注、证据标准、双端证据采用、PASS/FAIL 结尾）。
- S4：决策树新增 **Q4（同步链路一问）**——"行为是否依赖服务端→客户端同步链路（packet recipient/dispatch）？是 → 客户端测试必需 + 双端证据（引用 FakeConnection 同步教训）"；新增 `## Dual-View Matrix` 小节（三列：Server event / Recipient-client-visible / Evidence + EVIDENCE_CONFLICT 规则），标注模式借鉴自 DebugBridge/VitaminMCP 且不支持 Forge 1.20.1（只取模式，不接入）。

### 3. `.alice-supervision/skills/evidence-collection-standard.skill.md`
- S1：frontmatter（`name: evidence-collection-standard`，匹配 manifest line 25）；保留 `# Skill: Evidence Collection Standard`。
- S2：新增 `## How to Use This Skill (Progressive Disclosure)`；深度小节加 `(expand on demand)`：Evidence Types（Type 1-4）、Log Keywords Checklist、Evidence Collection Workflow、Common Mistakes、Integration、Example。
- S3：`## Evidence Quality Checklist` 升级为 eval——原 6 条目（Specific/Timestamped/Reproducible/Visual proof/Contextual/Comparative）逐字保留，追加计划自检（时间戳/行号/文件名、visual 标注、区分 server/client evidence、声明证据等级、EVIDENCE_CONFLICT），PASS/FAIL 结尾。
- S4：新增 `## Dual-End Evidence (server introspection + real client view)` 小节——同一复现必须同时采集服务端内省（entity state/packet recipient/tick/correlation id）与真实客户端所见（截图/10-15 秒视频/GUI 状态/用户描述）；矛盾 → 标 `EVIDENCE_CONFLICT`，不得以"服务端 PASS"覆盖客户端可见失败；引用 `ab510fd` 教训（服务端物理正常被客户端实测否定——FakeConnection 丢包导致客户端陈旧位置）；明确模式借鉴边界（DebugBridge/VitaminMCP 不支持 Forge 1.20.1，只取模式，不接入）。

## P0-A~D 自检结果

- **P0-A**：PASS——3 个文件 frontmatter YAML 均以 `---` 起始（第一行、前无空白），`name` 与 skills-manifest.yml 的 3 个 id（`debugging-root-cause-analysis` / `test-coverage-matrix` / `evidence-collection-standard`）完全匹配；description 均 <=160 字符的单一一句话。
- **P0-B**：PASS——3 个文件既有方法模板全部保留（5-Whys/Comparative/Binary Search/Hypothesis-Driven、矩阵模板、证据采集步骤、Checklist 逐字条目），新增内容仅 frontmatter/入口分层/`(expand on demand)` 标题后缀/eval 小节/双端证据小节；未删除、未改写任何既有方法语义。
- **P0-C**：PASS——`evidence-collection-standard` 新增 `## Dual-End Evidence`（服务端内省+真实客户端所见）显式方法段；`test-coverage-matrix` 新增 `## Dual-View Matrix` 显式方法段；两处均标注模式借鉴边界（DebugBridge/VitaminMCP 不支持 Forge 1.20.1，仅模式借鉴，不接入）。
- **P0-D**：待提交前复核——`git status` 应只含 3 个 skill 文件 + 本评估文件（+ 既有的 .dsh-runtime 会话记录，属运行期文件，按项目惯例随提交）；skills-manifest.yml / state-machine.yml / docs/SUPERVISION_PROTOCOL.md / docs/HANDOVER.md / 业务代码均未改动（本任务明示 P0-S6 HANDOVER 记录由监督员做，主开发不改）。

## 冻结边界确认

- 未改 `skills-manifest.yml`（3 条目保持 maintainer-approved v1.0.0、owner、scope、summary 不变）。
- 未改 `state-machine.yml`、`docs/SUPERVISION_PROTOCOL.md`、`docs/HANDOVER.md`。
- 未改任何业务代码、build.gradle、DSH/部署配置。
- 未新增 skill 文件、未新增工具；eval 小节标注"方法论文档自检，不改变 skills-manifest 的 gate 语义"。