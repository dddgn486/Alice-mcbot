---
name: debugging-root-cause-analysis
description: 在多次失败、结论与矛盾或盲试时，用可证伪假设与对照验证找根本原因，而不是修症状。
---
# Skill: Debugging Root Cause Analysis

## When to use this skill

Use this skill when:
- A feature fails multiple times with different symptoms (e.g., Bot inventory GUI 8 failed rounds)
- Investigation conclusions contradict test results (e.g., "server-side physics normal" but bot cannot be pushed)
- Blind trial-and-error wastes time (e.g., "change one thing, test, repeat")
- You need to find the fundamental cause, not just fix symptoms

## Core Principle

结论必须由"可证伪假设 + 对照验证"支持：能解释全部症状、能预测何时不再出现、能排除替代解释；做不到就不是根因，只是猜测。

---

## How to Use This Skill (Progressive Disclosure)

先读顶部「When to use this skill」与「Core Principle」→ 判定本场景是否适用本 skill → **按需展开**下方对应方法，无需一次性读完所有模板：

- **深度方法模板**（5-Whys / Comparative / Binary Search / Hypothesis-Driven）：陷入复杂根因、需要系统收敛时再展开
- **Anti-Patterns**：对照确认自己是否在踩坑
- **Checklist Before Claiming "Root Cause Found"**：宣称找到根因之前逐项自检
- **Integration with Alice Workflow**：调查报告/失败轮次中如何落地
- **Example: P1 Physics Investigation**：参考真实案例的调用方式

---

## Core Methods (expand on demand)

### Method 1: 5 Whys Analysis
Ask "Why?" 5 times to drill down to the root cause.

**Example: Bot cannot be pushed by player**
1. Why? → Bot does not move when player collides
2. Why? → `Entity.push()` may not be called or ineffective
3. Why? → BotPlayer may override physical behavior or have special flags
4. Why? → Check `isPushable()`, `noPhysics`, `isSpectator`
5. Why? → [Root cause found or need code inspection]

**Template:**
```
Problem: [Observed symptom]
Why 1: [Immediate cause]
Why 2: [Deeper cause]
Why 3: [Mechanism]
Why 4: [Design or configuration]
Why 5: [Root architectural decision or limitation]
```

---

### Method 2: Comparative Verification
Compare working vs. broken scenarios to isolate the minimal difference.

**Example: Equipment rendering failed → succeeded**
- Failed (`a8b84b4`): Used `bot.connection.send(packet)` → FakeConnection drops packets
- Succeeded (`ef43bd6`): Used `level.broadcast(bot, packet)` → Real players receive packets
- **Root cause**: FakeConnection.send() is a no-op; must broadcast to real players

**Template:**
```
Working scenario: [What works + key configuration]
Broken scenario: [What fails + key configuration]
Minimal difference: [The ONE thing that changed]
Root cause hypothesis: [Why this difference causes the failure]
Verification: [How to test the hypothesis]
```

---

### Method 3: Binary Search (Git Bisect)
Find the commit that introduced the problem by testing intermediate revisions.

**Example: Player jump height anomaly**
- Current (`ab510fd`): Can jump 1.5 blocks
- Previous (`65f4863`): Cannot jump 1.5 blocks (normal)
- **Root cause**: `ab510fd` (FakeConnection broadcast) introduced the anomaly

**Steps:**
1. Identify known-good commit (last working state)
2. Identify known-bad commit (current broken state)
3. Checkout middle commit: `git checkout <middle-commit>`
4. Test: Does the problem exist?
   - If yes: Problem introduced before middle → Search earlier half
   - If no: Problem introduced after middle → Search later half
5. Repeat until you find the exact commit

**Template:**
```
Known good: [Commit SHA + test result]
Known bad: [Commit SHA + test result]
Middle: [Commit SHA + test result]
→ Narrowed to: [Commit range]
→ Root cause commit: [SHA + what changed]
```

---

### Method 4: Hypothesis-Driven Debugging
Formulate testable hypotheses and design experiments.

**Example: Bot inventory GUI pickup vanishes**
- Hypothesis 1: Client-side slot state not synced → Test: Add `setChanged()` after pickup
- Hypothesis 2: Server-side move fails → Test: Check server logs for item count change
- Hypothesis 3: Packet lost → Test: Add packet send logging

**Template:**
```
Observed symptom: [What you see]
Hypothesis 1: [Possible cause] → Test: [How to verify] → Result: [PASS/FAIL]
Hypothesis 2: [Alternative cause] → Test: [How to verify] → Result: [PASS/FAIL]
...
Confirmed root cause: [The hypothesis that passed all tests]
```

---

## Anti-Patterns (What NOT to do) (expand on demand)

❌ **Guess-and-patch**: Change code randomly hoping it fixes the problem
❌ **Symptom chasing**: Fix each symptom without finding the root cause (leads to whack-a-mole)
❌ **Confirmation bias**: Only look for evidence that supports your initial guess
❌ **Skipping verification**: Assume a fix works without testing the exact failure scenario

---

## Checklist Before Claiming "Root Cause Found" (expand on demand)

- [ ] Can you explain WHY the problem happens (not just WHAT happens)?
- [ ] Can you predict when the problem will/won't occur based on your explanation?
- [ ] Have you verified your root cause with a targeted test (not just "seems to work now")?
- [ ] Does your root cause explain ALL observed symptoms (not just some)?
- [ ] Can you rule out alternative explanations?

---

## Integration with Alice Workflow (expand on demand)

1. **Investigation phase**: Use this skill before writing the investigation report
2. **Failed round diagnosis**: After 2-3 failed implementation rounds, use Method 1 (5 Whys) or Method 2 (Comparative)
3. **Git bisect**: When回退测试 to find which commit introduced a problem
4. **Report format**: Structure investigation reports using the templates above

---

## Example: P1 Physics Investigation (Actual Case) (expand on demand)

**Initial conclusion**: "Server-side physics normal" (based on source code analysis)
**User test result**: Bot cannot be pushed, no knockback
**Problem**: Conclusion contradicts reality

**Applying Method 2 (Comparative Verification)**:
```
Hypothesis: Server physics works, but client sync fails
Test 1: Check server logs → Entity.push() called? deltaMovement changed?
Test 2: Check client → Does client see bot at updated position?
Result: Server changes position, client does NOT see update
→ Root cause: FakeConnection drops S2C packets (client never receives position updates)
```

**Applying Method 3 (Binary Search)**:
```
ab510fd: Player can jump 1.5 blocks (broken)
65f4863: Player cannot jump 1.5 blocks (normal)
→ Root cause: ab510fd (FakeConnection broadcast) affects player physics
```

**Next step with Method 1 (5 Whys)**:
```
Why can player jump 1.5 blocks?
→ Why does FakeConnection broadcast affect player?
→ Why are bot position packets sent to all players including self?
→ [Continue drilling...]
```

---

## Eval Checklist (Self-Check Before Reporting a Root Cause)

升级自既有「Checklist Before Claiming "Root Cause Found"」——原 Checklist 条目逐字保留，并追加计划自检方向。方法论文档自检，不改变 skills-manifest 的 gate 语义。

原 Checklist（逐字保留）：
- [ ] Can you explain WHY the problem happens (not just WHAT happens)?
- [ ] Can you predict when the problem will/won't occur based on your explanation?
- [ ] Have you verified your root cause with a targeted test (not just "seems to work now")?
- [ ] Does your root cause explain ALL observed symptoms (not just some)?
- [ ] Can you rule out alternative explanations?

追加自检（P0-S3）：
- [ ] 每个结论是否由可证伪假设 + 定向测试支持（不是"改了就好"的猜测）？
- [ ] 是否排除了替代解释（对照验证 / 测试失败假设）？
- [ ] Anti-Patterns 是否全部规避（无 Guess-and-patch / Symptom chasing / Confirmation bias / Skipping verification）？

→ 全部勾选 = **PASS**；任一项未勾选 = **FAIL**，修复缺项（补定向测试、补对照、或重新假设）后再宣称根因。

Self-check result: `PASS` / `FAIL`（每次使用本 skill 后填写）。

**This skill helps you avoid wasting 18-28 hours on blind trial-and-error.**