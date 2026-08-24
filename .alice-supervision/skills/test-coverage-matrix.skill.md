---
name: test-coverage-matrix
description: 规划验证时显式区分：服务端 fixture、客户端矩阵、不可由服务端证明的边界。
---
# Skill: Test Coverage Matrix

## When to use this skill

Use this skill when:
- Planning verification for a new feature implementation
- Server-side tests pass but client-side behavior is unknown (e.g., P1 physics observation)
- Unsure whether a headless fixture is sufficient or client testing is required
- Need to design a client test matrix (what to test, how to observe, what evidence to collect)

## Core Principle

**Not everything can be verified server-side.** Some behaviors require client observation or real player interaction.

---

## How to Use This Skill (Progressive Disclosure)

先读顶部「When to use this skill」与「Core Principle」→ 判定本场景需要哪种验证 → **按需展开**下方对应部分，无需一次性读完所有模板：

- **Server-Side Limitations**：先确认哪些行为本就不能由服务端证明
- **Test Pyramid for Minecraft Mods**：为需求分配测试层级
- **Client Test Decision Tree**：对每个功能判定是否需要客户端测试（含同步链路一问）
- **Client Test Matrix Template + Dual-View Matrix**：设计客户端场景与双端证据
- **Common Mistakes**：对照避免"服务端 PASS = 完成"的误区
- **Integration with Alice Workflow**：规划/实施/验证/复审各阶段如何落地
- **Example: P1 Physics Observation**：参考真实失败案例

---

## Server-Side Limitations (What Fixtures CANNOT Test) (expand on demand)

### Cannot Test: Client Rendering
- Equipment rendering (helmet/chestplate visible on bot)
- GUI display (container menu layout, slot positions)
- Entity models (bot animation, pose)
- Particle effects, sounds

**Why**: Server does not render; rendering happens only on client.

**Example**: `BOT_INVENTORY_FIXTURE_SUITE PASS` ✓ (slot count correct) but client sees misaligned GUI ✗

---

### Cannot Test: Client-Side Physics
- Player jump height (affected by client-side velocity calculations)
- Entity collision from client perspective (server says "collided" but client may not see it)
- Knockback animation (server changes deltaMovement but client may not render the motion)

**Why**: Client and server run separate physics simulations; server-side logs only prove server-side state.

**Example**: P1 physics `soft_phys_obs` logs show `horizontalCollision=true` ✓ but client sees bot walk through player ✗

---

### Cannot Test: Network Synchronization
- Packet delivery (does client receive position updates?)
- Sync timing (does client update immediately or with delay?)
- Packet loss or reordering

**Why**: FakeConnection or test environment may skip network stack.

**Example**: `Entity.push()` modifies server-side bot position ✓ but FakeConnection drops sync packets → client never updates ✗

---

### Cannot Test: GUI Interaction
- Click detection (does right-click open the GUI?)
- Slot dragging (pickup, place, shift-click)
- Client-side input validation (can player place items in read-only slots?)

**Why**: GUI interaction requires real client input events.

**Example**: `quickMoveStack` server logic correct ✓ but client pickup vanishes ✗ (setChanged not called)

---

### Cannot Test: Multi-Player Scenarios
- How does Player A see Player B's bot?
- Does bot broadcast reach all nearby players?
- Cross-dimension or long-distance sync

**Why**: Headless tests typically run single-player or mock scenarios.

---

## Test Pyramid for Minecraft Mods (expand on demand)

```
        /\
       /  \    E2E (Client acceptance)       ← Expensive, slow, manual
      /____\   ~10% of tests                   Real Windows client + user observation
     /      \
    / Integration \  (Focused fixtures)      ← Moderate cost, automated
   /______________\  ~30% of tests             Headless server + assertions
  /                \
 /  Unit Tests      \ (Pure logic)           ← Cheap, fast, many
/____________________\ ~60% of tests          No Minecraft, no world, no entities
```

**Alice current status**: Heavy on Integration (focused fixtures), light on Unit, almost no E2E automation.

---

## Client Test Decision Tree (expand on demand)

**Question 1**: Does this feature affect rendering, client physics, or GUI?
- **Yes** → Client testing REQUIRED
- **No** → Go to Question 2

**Question 2**: Does this feature involve network sync (position, inventory, equipment)?
- **Yes** → Client testing REQUIRED (verify sync)
- **No** → Go to Question 3

**Question 3**: Does this feature involve player interaction (click, drag, command feedback)?
- **Yes** → Client testing RECOMMENDED (may catch UX issues)
- **No** → Server-side fixture may be sufficient

**Question 4 (sync-link gate)**: Does this feature's observable behavior depend on the **server→client sync link** (packet recipient / dispatch to real players)?
- **Yes** → Client testing **REQUIRED** **and** dual-end evidence needed (server introspection + real client view). Reference: `FakeConnection` dropping S2C packets caused "server physics normal but client sees stale position" (commit chain `65f4863` → `ab510fd`, see `evidence-collection-standard` skill).
- **No** → Server-side fixture may be sufficient (unless Q1–Q3 already required client).

---

## Client Test Matrix Template (expand on demand)

For each feature, define:

### Scope
- **What to test**: Specific scenarios (e.g., "Bot equipment visible", "Pickup item from bot inventory")
- **What NOT to test**: Out-of-scope scenarios (e.g., "Performance with 100 bots" if not a goal)

### Test Cases
| ID | Scenario | Operation | Expected Observation | PASS Criteria | Evidence |
|----|----------|-----------|----------------------|---------------|----------|
| T1 | Equipment visible | Transfer 3 iron_ingot to bot | Bot model shows helmet in main hand | Helmet visible in 3rd person | Screenshot |
| T2 | GUI layout | Open bot inventory GUI | 36 bot slots + 4 armor + player grid | No overlap, aligned | Screenshot |
| T3 | Pickup item | Left-click bot inventory slot | Item moves to cursor | Item visible on cursor, slot empty | Log + Screenshot |
| T4 | Physics collision | Player blocks bot path | Bot stops or reroutes | Bot does not walk through player | Video |
| T5 | Knockback | Attack bot with sword | Bot slides backward | Visible displacement animation | Video |

### Evidence Requirements
- **Latest.log**: Must contain key log lines (task start, completion, failure codes)
- **Debug.log**: Optional, for detailed diagnostics
- **Screenshots**: Required for rendering/GUI tests
- **Video**: Recommended for animation/physics tests
- **Before/After comparison**: Required when verifying a fix (e.g., "Equipment not visible before fix, visible after fix")

### Failure Recovery
- If T_N fails: Stop and diagnose before continuing (do not batch-test all scenarios if T1 already fails)
- Root cause required: "T2 failed because..." (use `debugging-root-cause-analysis` skill)
- Re-test after fix: Must re-run the exact failed scenario (not just "seems to work now")

---

## Dual-View Matrix (expand on demand)

对依赖服务端→客户端同步链路的场景，每个用例三列记录 **服务端内省 + 真实客户端所见** 双端证据（模式借鉴自 DebugBridge / VitaminMCP 的"服务端内省 + 真实协议客户端"思想；**这些工具不支持 Forge 1.20.1，只取模式，不接入**）：

| ID | Scenario | Server event (observable state) | Recipient / client-visible (what client should see) | Evidence (corr id, log line, screenshot/video) |
|----|----------|--------------------------------|-----------------------------------------------------|------------------------------------------------|
| D1 | Movement broadcast | `bot.getDeltaMovement()`, position changed on server | Client renders bot moving along path | `soft_phys_obs` log line + video of client view |
| D2 | Collision with player | `horizontalCollision=true`, velocity disrupted | Client sees bot stop at player (not walk through) | log `soft_phys_disruption` + video |
| D3 | Knockback | deltaMovement spiked on server | Client sees bot knocked back (visible displacement) | log + video of displacement |
| D4 | Replan after block | path replaced, `soft_phys_replan` logged | Client sees bot reroute (no teleport/jump-through-wall) | log + video |

**Rule**: server event 与 client-visible 矛盾 → 标 `EVIDENCE_CONFLICT`，不得以"服务端 PASS"覆盖客户端可见失败（与 `evidence-collection-standard` 的 Dual-End Evidence 一致）。

---

## Common Mistakes (expand on demand)

❌ **"Server fixture PASS → Feature complete"**
- Server fixture only proves server-side correctness
- Client may still see broken behavior (sync, rendering, GUI)

❌ **"Client test passed once → No need to re-test"**
- Client behavior may regress in later changes
- Must re-test after any related code changes

❌ **"No screenshot/video → Verbal description sufficient"**
- "Bot穿过玩家" is ambiguous (server collision? client rendering? sync timing?)
- Screenshot/video provides objective evidence

❌ **"Test all scenarios together → Save time"**
- If T1+T2+T3 all fail, you don't know which one broke first
- Test incrementally: T1 PASS → T2 PASS → T3...

---

## Integration with Alice Workflow (expand on demand)

### Planning Phase (Planner / Supervisor)
1. Read active plan scope
2. For each feature, apply the Decision Tree
3. Add client test matrix to active plan if required
4. Mark as `CLIENT_TEST_PENDING` in HANDOVER

### Implementation Phase (Main Developer)
1. After server-side fixture PASS, check active plan
2. If client testing required: Do NOT mark feature complete
3. Create client test instructions (`.alice-supervision/client-tests/<commit>-<feature>.md`)

### Verification Phase (User)
1. Execute client test matrix on Windows
2. Collect evidence (log, screenshot, video)
3. Report: "PASS" or "FAIL: T_N failed because..."

### Review Phase (Supervisor)
1. Verify evidence matches PASS criteria
2. If evidence insufficient: Request re-test with specific requirements
3. If PASS: Mark feature `USER_ACCEPTED`

---

## Example: P1 Physics Observation (expand on demand)

**Decision Tree**:
- Q1: Affects client physics? → **YES** (collision, knockback)
- → Client testing REQUIRED

**Client Test Matrix** (simplified):
| ID | Scenario | Expected | Evidence |
|----|----------|----------|----------|
| P1-G5 | Wall collision | Bot stops, revalidates | Log: `horizontalCollision=true`, bot visible stopped |
| P1-G8 | Knockback | Bot slides back, revalidates | Log: `velocityDisruption`, **video of bot moving backward** |

**What went wrong**:
- Server fixture: `SOFT_PHYSICS_OBSERVATION_SUITE PASS` ✓
- Marked as "complete" without client test
- Client test revealed: Bot walks through player (sync failure), no knockback animation (physics failure)
- **Root cause**: Assumed server-side correctness = client-side correctness ✗

**Correct approach**:
1. Server fixture PASS → Mark as "server-side complete"
2. Create client test matrix P1-G1 to P1-G13
3. User tests on Windows → Discovers sync/physics failures
4. Fix sync/physics → Re-test client
5. All PASS → Mark as `USER_ACCEPTED`

---

## Eval Checklist: Before Marking Feature "Complete"

升级自既有「Checklist Before Marking Feature \"Complete\"」——原 Checklist 条目逐字保留，并追加计划自检方向。方法论文档自检，不改变 skills-manifest 的 gate 语义。

原 Checklist（逐字保留）：
- [ ] Server-side fixture PASS (if applicable)
- [ ] Applied Test Coverage Decision Tree
- [ ] If client testing required: Created client test matrix
- [ ] If client testing required: User executed tests and provided evidence
- [ ] Evidence matches PASS criteria (log keywords, screenshots, behavior description)
- [ ] No failed scenarios remaining (or all failures documented as known limits)

追加自检（P0-S3）：
- [ ] 决策树 Q1–Q4 是否都回答完毕（尤其 Q4 同步链路一问是否判定过）？
- [ ] 服务端不可证项是否已标注（对应 Server-Side Limitations 列表）？
- [ ] 每个客户端场景是否都有证据要求（截图/视频/日志关键字），而非仅文字描述？
- [ ] 双端证据模式是否在依赖同步链路的场景被采用（`Dual-View Matrix` / `EVIDENCE_CONFLICT`）？

→ 全部勾选 = **PASS**；任一项未勾选 = **FAIL**，修复缺项（补矩阵、补证据标准、补同步链路判定）后再宣称 feature complete。

Self-check result: `PASS` / `FAIL`（每次使用本 skill 后填写）。

**This skill helps you avoid "server PASS but client FAIL" scenarios, saving 12-18 hours of rework.**