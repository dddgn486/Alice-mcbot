# Alice 工作流程审计：E7 窄修复耗时分析

- 审计人：Alice 架构监督员
- 审计日期：2026-08-22
- 审计对象：`20220822-transfer-selector-item-argument-v1`（E7 item 参数解析修复）从派发到交付的全流程
- 受审工作包：selector submit item 参数 `StringArgumentType.word()` → `ResourceLocationArgument.id()`

## 一、事实时间线

| 时间 | 事件 |
|---|---|
| 10:50 | 规划员报告 `83163101` 完成（只读，~2 分钟） |
| 10:58 | 派发实现任务 `9614dec7` |
| 11:11 | `9614dec7` failed (timeout, ~13 分钟) |
| 11:13 | 派发收尾 `57d6a578` |
| 11:34 | `57d6a578` failed (timeout, ~21 分钟) |
| 11:36 | 派发 `c5286dfa` |
| 11:48 | `c5286dfa` failed (no-response, ~12 分钟) |
| 11:52 | 派发 `70239450`（后取消） |
| 19:48 | 开发员实际完成 focused（20:48 实为 19:48+1h 时区？→ 见注） |
| 19:52 | `cab2552` 提交（实际完成） |
| 20:51 | `370ff343` amend 提交（fixture 修正后） |
| 12:46→12:52 | `958446e8` 重派后 6 分钟完成（已切换稳定模型） |

注：`cab2552` 提交时间 19:52，而最后失败任务 11:48，中间约 8 小时空窗——与上游 API 不稳定导致模型无回复吻合（用户确认）。

## 二、拖延根因分类

### A. 外部因素（不计入流程效率问题）

1. **上游 API 不稳定**：开发员模型长时间无回复，导致任务在 10–20 分钟超时。8 小时空窗属此因。用户已切换稳定模型，`958446e8` 重派后 6 分钟完成，证明修复本身工作量极小。

### B. 流程可优化点（本次审计重点）

1. **bus 超时配置与 Forge 开发耗时不匹配**
   - 实际配置（`~/.dsh/profiles/alice-bus-lab/cordis.patch.yml:15-16`）：
     ```yaml
     taskTimeoutMs: 600000    # 10 分钟
     offlineGraceMs: 120000   # 2 分钟
     ```
   - bus 默认（`node_modules/dsh-agent-bus/lib/index.js:54`）：`taskTimeoutMs` 2 小时、`offlineGraceMs` 15 分钟。
   - Forge 窄修复 = 门禁 + 编译（数分钟）+ focused runServer（数分钟）+ 提交，10 分钟必然超时。
   - 建议：`taskTimeoutMs` 至少 60 分钟；`offlineGraceMs` 至少 15 分钟（对齐默认）。配置在 `cordis.patch.yml`，需用户/dsh 维护者调整。

2. **任务 failed 后 report_task 被拒，交付链断裂**
   - 开发员完成 `cab2552` 时任务已 failed，`report_task` 被拒，只能改 note 交付（`f96bbba2`、`8c6ce6c0`）。
   - 后果：无法走正常 settle 验收链，监督员只能靠查工作树/日志确认，增加往返。
   - 建议：failed 任务应允许 executor 附上工作摘要（类似 canceled 的 report 语义），或监督员在超时后先检查工作树再决定是否重派，避免"工作已完成但无法上报"。

3. **一次窄修复重派 5 次**
   - `9614dec7 → 57d6a578 → c5286dfa → 70239450(取消) → e1d285d7(discarded) → 958446e8(成功)`。
   - 每次超时等待 10–20 分钟；其中 `70239450` 与 `e1d285d7` 内容重叠、`e1d285d7` 被系统 discarded、`958446e8` 才是有效交付。
   - 建议：重派前先 `get_task` 核对状态（discarded/failed 的区别），并用 `get_task` 的 updated 时间判断是否已被领取；对 "working 但无响应" 先 interrupt + note 确认，再决定是否新派。

4. **验证矩阵错误导致返工**
   - `cab2552` fixture 断言 `minecraft:`/`:iron_ingot` 必须被 parser 拒绝，但固定 Forge API 实测接受（`:iron_ingot` 规范化为 `minecraft:iron_ingot`）。
   - 根因：规划阶段（`83163101`）只做了 javap 静态核验，未做 parse 行为实测；监督员采纳矩阵时未验证。
   - 建议：规划报告对"参数解析行为"类问题应包含最小运行/单测实测，或标注"API 行为以实测为准，矩阵待实现时校准"；避免提交后返工。

5. **等待守护被历史噪音反复唤醒**
   - `a6ab5dba`（2026-08-21 已 failed 的规划任务）被唤醒 7+ 次，每次消耗一次 get_task 确认。
   - 建议：将已确认 obsolete 的 task id 加入本地忽略清单（记录在 `docs/SUPERVISOR_HANDOFF.md` 或 `.alice-supervision/`），守护唤醒后先查忽略清单，不在清单中才 get_task。

6. **队列卡死/投递问题**
   - `e1d285d7` 停在 submitted 后被系统 discarded；`958446e8` 显示 working 但用户反映队列卡死。
   - 建议：监督员在任务 submitted 超过一个检查间隔后主动确认；必要时用 `send_note` 提醒（本次 `c0f757d0` 有效）。

## 三、结论

- **流程本身的设计（门禁、active plan、审核包、客户端验收）是健全的**；本轮真正的大块耗时来自上游 API 不稳定（外部因素），不归咎于流程。
- 流程侧可优化的 6 点中，**影响最大的是超时配置（10 分钟）与 failed 后交付链断裂**，其余为效率损耗。
- 修复本身工作量极小（2 处参数类型 + 1 个 fixture），切换稳定模型后 6 分钟完成，证明任务粒度是合适的。

## 四、建议行动（按优先级）

1. [用户/dsh 维护] 调整 `cordis.patch.yml`：`taskTimeoutMs` 60 分钟、`offlineGraceMs` 15 分钟（与 bus 默认对齐）。
2. [监督员] 超时后先查工作树与 note，确认是否有已完成未上报的工作，再决定重派。
3. [监督员] 建立 obsolete 任务忽略清单，减少历史噪音唤醒。
4. [监督员/规划] 参数解析类规划的矩阵须含 API 行为实测或明确标注待校准。
5. [监督员] failed 任务交付路径：接受 note + 工作树证据作为交付，不必强制重跑任务生命周期。

## 四·补、给 dsh 维护者的一段话（可直接转达）

> Alice 四角色 bus 部署的 `cordis.patch.yml` 把 `taskTimeoutMs` 压到 10 分钟、`offlineGraceMs` 压到 2 分钟，远低于 bus 默认（2 小时/15 分钟），与 Forge 开发的实际节奏严重不匹配：一次窄修复（门禁 + 编译 + focused runServer + 提交）通常要 15–30 分钟，导致正常工作的执行方任务反复 `failed(timeout)`；任务失败后执行方 `report_task` 被拒，已完成的成果只能改走 note 交付，正式验收链断裂；此外已确认的旧 `failed` 任务仍会被 wait-watch 反复唤醒（同任务 7+ 次），消耗监督方 token。建议把 `taskTimeoutMs` 恢复到至少 60 分钟、`offlineGraceMs` 恢复到默认 15 分钟，并允许 failed 任务的执行方补交工作摘要，同时为已被监督员确认 obsolete 的任务提供忽略清单，避免重复唤醒。

## 五、当前项目状态（审计后）

- E7 服务端修复：`370ff343`（WSL）/ `f655be2`（Windows overlay）PASS；E7 客户端回归 PASS（Windows 2026-08-22 21:03-21:05，`latest.log:142-167`）；
- Active plan：`USER_ACCEPTED`（E7）；
- 待用户：E8 场景（selection submit vs transfer-test 的 request UUID 与最终状态映射）；
- 流程审计：本文档（含"给 dsh 维护者的一段话"）。
