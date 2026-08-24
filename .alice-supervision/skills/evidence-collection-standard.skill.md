---
name: evidence-collection-standard
description: 让每次 bug/测试/调查结论都带可复核证据并注明证据等级；没有证据就写"未证实"。
---
# Skill: Evidence Collection Standard

## When to use this skill

Use this skill when:
- Reporting a bug or test failure (need to prove what went wrong)
- Submitting an investigation report (need evidence to support conclusions)
- Marking a feature complete (need evidence that it works)
- User tests a feature on Windows client (need to know what evidence to collect)

## Core Principle

**"No evidence = No confidence."** Verbal descriptions are ambiguous; logs, screenshots, and videos provide objective proof.

---

## How to Use This Skill (Progressive Disclosure)

先读顶部「When to use this skill」与「Core Principle」→ 判定当前结论需要哪类证据 → **按需展开**下方对应章节，无需一次性读完所有模板：

- **Evidence Types**：Log / Screenshot / Video / Comparative 四类，按场景选择
- **Log Keywords Checklist (Alice-Specific)**：服务端日志关键字参考
- **Evidence Collection Workflow + Dual-End Evidence**：采集步骤；涉及同步链路的场景必须读双端证据小节
- **Common Mistakes**：对照避免坏证据
- **Integration with Alice Workflow**：调查/客户端测试/验收各阶段如何落地
- **Evidence Quality Checklist**：提交前逐项自检（升级为 eval 样式）
- **Example: P1 Client Test Evidence**：好证据 vs 坏证据对比

---

## Evidence Types (expand on demand)

### Type 1: Log Evidence (Always Required)

#### What to collect
- **Latest.log**: Primary log (task lifecycle, key events, failures)
- **Debug.log**: Detailed diagnostics (optional, for deep investigation)
- **Specific lines**: Not the whole log (thousands of lines), but **key snippets** with line numbers

#### How to extract key snippets
```bash
# Search for keywords
grep -n "soft_phys_obs\|soft_phys_disruption" latest.log

# Extract lines around a timestamp
sed -n '1234,1256p' latest.log  # Lines 1234-1256

# Extract last N lines
tail -50 latest.log
```

#### What makes good log evidence
✅ **Include timestamps**: `[238月2026 20:01:31.175]`
✅ **Include log level**: `[Server thread/INFO]` or `[ERROR]`
✅ **Include source**: `[com.dddgn.alice.log.BotLog/]`
✅ **Include context**: 2-3 lines before/after the key line

❌ **Bad**: "Log says bot failed"
✅ **Good**: 
```
[238月2026 20:02:45.543] [Server thread/INFO] [BotLog]: task_execution_terminal 
  kind=MineTask target=方块@16,-60,17 terminal=COMPLETED code=done
```

---

### Type 2: Screenshot Evidence (Required for Visual Bugs) (expand on demand)

#### When to take screenshots
- GUI layout issues (misaligned slots, overlapping panels)
- Rendering issues (equipment not visible, bot model broken)
- Client-side state (inventory contents, bot position)
- Before/after comparison (broken → fixed)

#### What makes good screenshots
✅ **Clear target**: Highlight or annotate what to look at
✅ **Context visible**: Show enough UI to identify the scenario (e.g., which GUI is open)
✅ **Filename describes content**: `V1-bot-equipment-helmet.png` not `screenshot_123.png`

❌ **Bad**: Blurry, no annotation, unclear what the problem is
✅ **Good**: Clear image with arrow pointing to "Expected: helmet, Actual: empty"

#### Tools
- **F2** (Minecraft default screenshot key) → Saves to `run/screenshots/`
- **Windows Snipping Tool** (for annotations)

---

### Type 3: Video Evidence (Required for Animation/Physics) (expand on demand)

#### When to record video
- Physics behavior (bot collision, knockback, movement)
- Animation (bot walking, jumping, falling)
- Multi-step interactions (click → drag → release)
- Timing issues (delays, stuttering, desyncs)

#### What makes good videos
✅ **Short and focused**: 5-15 seconds per scenario (not 5-minute session)
✅ **Clear action**: Visible player input (e.g., "Player attacks bot at 0:03")
✅ **Stable camera**: Not spinning wildly, target visible

❌ **Bad**: 5-minute video, unclear what to watch for
✅ **Good**: 10-second clip, "Bot knockback test: player attacks at 0:03, bot slides back 0:04-0:06"

#### Tools
- **OBS Studio** (free, records Minecraft gameplay)
- **Windows Game Bar** (Win+G, quick clips)

---

### Type 4: Comparative Evidence (Required for Regressions/Fixes) (expand on demand)

#### When to use comparisons
- Proving a bug exists (before fix)
- Proving a bug is fixed (after fix)
- Finding which commit introduced a problem (git bisect)

#### Template
```
Before (commit abc123):
- Log: [Error line showing failure]
- Screenshot: [Broken behavior]
- Observation: Bot walks through player

After (commit def456):
- Log: [Success line showing fix]
- Screenshot: [Fixed behavior]
- Observation: Bot stops when colliding with player
```

---

## Log Keywords Checklist (Alice-Specific) (expand on demand)

### Task Lifecycle
- `任务创建: <TaskType>`
- `task_execution_terminal kind=<Type> terminal=<Status> code=<Code>`
- Keywords: `COMPLETED`, `FAILED`, `CANCELLED_REPLACED`, `code=done`

### SOFT_SURFACE (P1 Physics)
- `soft_phys_obs`: Physical observation (deltaMovement, collision, support)
- `soft_phys_disruption`: Velocity disruption (knockback, push)
- `soft_phys_revalidate`: Segment revalidation (blocked, unstable)
- `soft_phys_replan`: Path replanning (trigger, old/new path, replanCount)

### Transfer
- `TRANSFER_FIXTURE_SUITE PASS`
- `TransferLedger: <UUID> state=<State> code=<Code>`
- Keywords: `COMPLETED`, `SUSPENDED`, `capacity_rejected`

### Bot Inventory
- `BOT_INVENTORY_FIXTURE_SUITE PASS`
- `[GUI_DEBUG]`: Pickup, place, quickMoveStack diagnostics

### Equipment Rendering
- `syncBotEquipment: bot=<Name> mainHand=<Item>`
- `ClientboundSetEquipmentPacket`

---

## Evidence Collection Workflow (expand on demand)

### Step 1: Before Testing
1. Clear old logs (or note the timestamp where new test starts)
2. Prepare screenshot/video tools
3. Read test matrix: Know what to observe

### Step 2: During Testing
1. Execute test scenario
2. Observe client behavior (does it match expected?)
3. Take screenshot/video immediately if unexpected behavior occurs

### Step 3: After Testing
1. Extract log snippets (search for keywords)
2. Organize evidence by test case:
   ```
   evidence/
     latest.log
     debug.log
     T1-equipment-visible.png
     T2-gui-layout.png
     T4-physics-collision.mp4
     evidence-report.md
   ```

### Step 4: Write Evidence Report
Template:
```markdown
# Test Results: <Feature Name>

## T1: <Scenario Name>
- **Result**: PASS / FAIL
- **Observation**: <What you saw>
- **Log Evidence** (lines X-Y):
  ```
  [timestamp] [log line 1]
  [timestamp] [log line 2]
  ```
- **Visual Evidence**: [Screenshot filename]

## T2: ...
```

---

## Dual-End Evidence (server introspection + real client view) (expand on demand)

**适用场景**：任何依赖服务端→客户端同步链路的结论（bot 位置/速度被客户端观察、碰撞/击退从客户端视角确认、GUI/渲染从客户端确认）。单边证据（仅服务端日志或仅客户端截图）在此类场景不足以支撑结论。

### 同一复现必须同时采集

1. **服务端内省**：entity state（position/deltaMovement/collision flags）、packet recipient（哪个玩家收到哪些包）、tick/时间、correlation id（日志与客户端时间对应）。
2. **真实客户端所见**：截图或 10-15 秒短视频、GUI/menu 可见状态、用户观察描述（含时间点）。

### 冲突规则

- 服务端内省与客户端所见**矛盾** → 标 `EVIDENCE_CONFLICT`，不得以"服务端 PASS"覆盖客户端可见失败。
- 需先定位哪一端失真（如 FakeConnection 丢包：服务端物理正常但客户端看到陈旧位置），再修复同步链路本身。

### Alice 既有教训（引用）

- `ab510fd`（P1 客户端同步修复）：此前 `65f4863` 结论"服务端物理正常"被客户端实测否定——服务端日志全 PASS，但客户端看到 bot 位置陈旧。根因：`FakeConnection.send()` 空实现丢弃所有 S2C 位置/速度包。修复后才满足 `test-coverage-matrix` 的 Dual-View 规则。
- `test-coverage-matrix` skill 的 Server-Side Limitations 段落已列出同类清单（客户端渲染/客户端物理/网络同步/GUI 交互/多人场景无法由服务端 fixture 证明）。

### 模式借鉴边界（硬性）

- DebugBridge / VitaminMCP 的思路（服务端内省 + 真实客户端协议观察）**只作模式借鉴**：这些工具针对 Fabric/Paper 版本，**不支持 Forge 1.20.1**，不接入 Alice 工具链。Alice 用自身日志 + Windows 真实客户端测试实现同等的双端证据。
- 本节是方法论，不承诺任何外部工具可用性。

---

## Common Mistakes (expand on demand)

❌ **"It failed" (no details)**
- What failed? At what step? What was the error message?

❌ **"See attached log" (10,000 lines)**
- Extract the relevant 5-10 lines, not the whole file

❌ **"Screenshot shows the bug" (no annotation)**
- Point out what's wrong: Circle it, add arrow, add text label

❌ **"Video of entire test session" (5 minutes)**
- Clip the specific 10-second failure moment

❌ **"Before fix: broken, After fix: works" (no proof)**
- Show the actual log/screenshot before and after

❌ **"Server log PASS but no client view"（双端证据缺失）**
- 依赖同步链路的结论只提供服务端日志 → 标 `EVIDENCE_CONFLICT` 或补客户端证据

---

## Integration with Alice Workflow (expand on demand)

### Investigation Reports (调查员)
1. State the problem: "Bot cannot be pushed by player"
2. Evidence of problem:
   - Log: `Entity.push()` called on server? `deltaMovement` changed?
   - Client observation: Bot position updated?
3. Root cause hypothesis: "FakeConnection drops sync packets"
4. Evidence of root cause:
   - Code: `FakeConnection.send()` is empty
   - Comparative: Real player (works) vs FakePlayer (broken)

### Client Test Results (User)
1. For each test case (T1, T2, ...):
   - PASS/FAIL
   - 3-5 line observation
   - Key log snippet (2-3 lines with timestamps)
   - Screenshot/video (if applicable)
2. Summary: "5 PASS, 2 FAIL (T4 and T7)"
3. Failed scenarios: Detailed evidence

### Feature Acceptance (Supervisor)
1. Review evidence against PASS criteria
2. If insufficient: "T4 marked PASS but no collision log evidence → Please re-test and collect `soft_phys_obs horizontalCollision=true` log"
3. If complete: Mark `USER_ACCEPTED`

---

## Eval Checklist: Before Submitting Evidence (expand on demand)

升级自既有「Evidence Quality Checklist」——原条目逐字保留，追加双端证据自检。方法论文档自检，不改变 skills-manifest 的 gate 语义。

原 Checklist（逐字保留）：
- [ ] **Specific**: Not "bot broken", but "bot walks through player at position X,Y,Z"
- [ ] **Timestamped**: Log lines include timestamps
- [ ] **Reproducible**: Described steps to reproduce (or confirmed one-time fluke)
- [ ] **Visual proof**: Screenshot/video if behavior is visual
- [ ] **Contextual**: Enough info to understand what happened (which test, which commit, which scenario)
- [ ] **Comparative**: Before/after if claiming a fix or regression

追加自检（P0-S3 / 双端证据）：
- [ ] 每个 claim 是否带时间戳/行号/文件名（日志引用格式）？
- [ ] Visual 证据是否标注关键点（箭头/圈注/文本标签）？
- [ ] 是否区分 **server evidence** 与 **client evidence**（并在依赖同步链路的场景同时提供两端）？
- [ ] 是否声明证据等级（服务端 fixture / 客户端矩阵 / 双方确认 / 未证实）？
- [ ] 双端矛盾是否标 `EVIDENCE_CONFLICT`（而非以服务端 PASS 覆盖客户端失败）？

→ 全部勾选 = **PASS**；任一项未勾选 = **FAIL**，修复缺项（补行号、补标注、补另一端证据、补冲突标记）后再提交报告。

Self-check result: `PASS` / `FAIL`（每次使用本 skill 后填写）。

---

## Example: P1 Client Test Evidence (Good vs Bad) (expand on demand)

### ❌ Bad Evidence
```
T4 failed. Bot doesn't work.
```

**Problem**: No details, no proof, impossible to diagnose.

---

### ✅ Good Evidence
```
## T4a: Player blocks bot path

- **Result**: FAIL
- **Observation**: Bot walks through player. Player at (10, -60, 15), bot path
  from (5, -60, 15) to (15, -60, 15). Bot does not stop or reroute.
- **Log Evidence** (latest.log lines 342-345):
  ```
  [238月2026 20:03:15] [Server thread/INFO] [BotLog]: soft_phys_obs: 
    segment=3/5 action=HORIZONTAL deltaMove=(0.12,-0.08,0.00) 
    hDev=0.8 vDev=0.0 horizontalCollision=false ...
  ```
- **Expected**: `horizontalCollision=true`, bot stops
- **Actual**: `horizontalCollision=false`, bot continues
- **Video**: `T4a-bot-walks-through-player.mp4` (0:05-0:12, bot at 0:08 
  overlaps player but keeps walking)
```

**Why good**: Exact scenario, exact log proof, video shows the problem, expected vs actual stated.

---

**This skill helps you provide clear, verifiable evidence, avoiding 6-10 hours of back-and-forth clarification.**