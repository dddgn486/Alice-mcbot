# 提交任务：方案 C C-1 兜底段正式提交 + Windows 同步（用户已批准）

- 派发：Alice 架构监督员
- 执行：Alice 主开发员（session-30b693e7）
- 日期：2026-08-25（本地 8月 25 日）
- 基线：`21f653f`（HEAD；C-1 改动在工作区 M 状态，未 commit）
- 用户批准：✅（2026-08-25 明确"批准提交"，含 Windows 同步要求——用户既定要求"每次修改同步到 Windows 端"）
- 验收记录：`.alice-supervision/research/physics-fix-c-result-20260825.md`（监督员已验收：C-1 兜底生效 7 次 + 边界零违反 + 服务端断言如实）
- 性质：**提交 + 同步闭环**（不改任何实现逻辑——C-1 已落盘且已验收，本任务只做 commit/push/HANDOVER 更新/同步确认）

## 提交范围（严格限定）

| 项 | 内容 |
|---|---|
| **代码提交** | 仅 `src/main/java/com/dddgn/alice/bot/BotManager.java` 的 C-1 兜底段（import 2 行 + onServerTick 尾段兜底消费 + helper——工作区已有，**直接 commit 此改动，不得新增/修改任何其他行**）|
| **文档提交** | `docs/HANDOVER.md` 更新：实际 HEAD、方案 C 完成项、服务端验证证据、未验证限制（客户端 M2-M5 待 Windows 实测）、下一安全步 |
| **证据配套** | 提交信息引用监督员验收记录（`physics-fix-c-result-20260825.md`）|

**禁止**：不改 fixture（BotPhysicsAssertionFixture 零改动）、不改 FakeConnection、不改矿链（MineTask/DropCollectionTask/BotMiner）、不改 5 个 travel 调用点、不改 P0 门禁、不翻案、不混入其他改动（工作区若有其他 M 状态文件，**不得一并 commit**——只提交 C-1 相关）。

## 提交前置（push 前必做——HANDOVER §九 教训）

1. `git fetch github`（或 origin）检查远端状态——**当前 ahead 6 / behind 2**，push 可能 non-fast-forward（HANDOVER 记录过 3 次教训）
2. 非 fast-forward 处理：先 `git log HEAD..origin/master` 看远端新提交；**不得强推（push -f）**；用 rebase（`git rebase origin/master`）或监督员确认合并策略——rebase 后必须重新 `./gradlew compileJava`（C-1 改动不得在 rebase 中丢）
3. rebase/合并后提交信息保持语义（方案 C C-1）

## 提交与同步步骤

1. `git add src/main/java/com/dddgn/alice/bot/BotManager.java docs/HANDOVER.md`（**精确 add，不 add 其他**）
2. commit：`feat(phys): C-1 task-layer residual-delta fallback consumption (scheme C, user-approved)` + 正文引用验收记录
3. `git push origin master`（GitHub → Windows `receive.denyCurrentBranch=updateInstead` 自动更新）
4. 验证 push 成功：`git log --oneline -1` 确认新 HEAD + `git status -sb` 确认与 origin 同步（ahead 0）
5. 记录：`DEPLOYED_COMMIT` 类标记或证据报告尾部追加"提交+push+Windows 同步触发"记录

## 产出

- commit hash + push 成功确认（git status -sb ahead 0）
- HANDOVER 更新（新 HEAD、方案 C 完成项、待 Windows 实测项）
- 证据报告 `.alice-supervision/research/physics-fix-c-result-20260825.md` 尾部追加提交闭环记录（或单独 `physics-fix-c-commit-20260825.md`）
- 完成后 report_task

## 验收标准

- 仅 BotManager.java（C-1）+ HANDOVER.md 被 commit（git show 确认无其他文件混入）
- push 前 fetch 检查已执行；ahead 6/behind 2 已正确处置（rebase 或经监督员确认的合并），**无强推**
- push 后 `git status -sb` 显示 ahead 0（与 origin 同步）
- HANDOVER 已更新（新 HEAD/完成项/待实测限制/下一安全步）
- Windows 端同步触发说明已记录（用户实测前需确认 Windows 已 pull 到新 commit）
- 无任何其他 src/fixture 改动混入；不翻案

**本提交后由用户确认 Windows 端同步状态，再进行 M2-M5 客户端实测。**