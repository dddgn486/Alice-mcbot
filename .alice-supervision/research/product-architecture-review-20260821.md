# Alice Product Architecture Shallow Research

- Date: 2026-08-21
- Scope: architecture and roadmap only; no business-code implementation authorization.
- Trigger: user restated three long-term product goals and requested a complete framework review.

## Evidence Reviewed

- Current HEAD `ae32c4a205856af30d93981b27ccaed7686ba5a0` and dirty governance/document worktree.
- `docs/AI_PLAYER_DESIGN.md`, `AI_PLAYER_NOTES.md`, `EXECUTION_FRAMEWORK.md`, `MEK_GUI_SEMANTICS.md`, `PATHING_REFACTOR.md`, `BARITONE_PORTING_CHECKLIST.md`, and `HANDOVER.md`.
- Current implementation boundaries: server-authoritative BotPlayer/tasks, HARD_PATH mining/collection, isolated SOFT_SURFACE, InterfaceScanner capability text projection, task execution records.
- Independent adversarial architecture review performed in the supervisor session.

## Findings

1. The three product goals are coherent but need separate product lanes and acceptance matrices: general assistant, player-defined pre-AE automation, and AE network/pattern assistance.
2. The old statement that capability scanning can automatically cover most technology machines conflated basic readonly discovery with reliable semantic operations.
3. Tutorials/RAG can guide goal and tool selection but cannot establish current machine state, permission, slot semantics, completion, rollback, or safe write behavior.
4. A missing explicit layer was identified between LLM ToolCall and tasks: Policy Gate / Plan Validator.
5. Pre-AE automation requires inventory transactions, resource reservations, PipelineProfile, ProcessContract, byproduct policy, uncertainty budgets, and recoverable handoff.
6. AE work requires a fixed-version AE2 adapter, topology facts, pattern rules and preview/audit. JEI and provider names cannot be treated as ground truth.
7. Task parameters must be structured policies enforced by code, not prompt-only hints.
8. Mod compatibility must be declared by capability level C0-C5 and degrade safely by version.

## Decision

- Publish `docs/PRODUCT_ARCHITECTURE_ROADMAP.md` as the long-term product/compatibility authority.
- Revise `AI_PLAYER_DESIGN.md`, `EXECUTION_FRAMEWORK.md`, README, historical-note warnings, and HANDOVER R37-R40 accordingly.
- No deep research was required to establish this product architecture. Deep research is mandatory later for AE2 pattern/topology implementation and may be required for inventory-transfer capability semantics or a target mod adapter when local evidence is insufficient.
- This review does not authorize business-code changes. The next implementation candidate remains `interface-readonly-snapshot-v1`, now explicitly scoped to compatibility level C1.
