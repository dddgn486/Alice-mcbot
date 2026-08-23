# Alice Supervisor Route Review — Post-C1 Mainline

- Review ID: `20260821-c1-next-mainline-route`
- Baseline: `3644429d2d88e1bb6fd1eb24654d828d1451d7f8`
- Preceding plan: `20260821-interface-c1-maintenance-f1-f4-v1 / COMPLETED`
- Planner task: `90f865f3-cfab-4f78-b6a6-3b92ffa83d97` — settled success
- Planner report: `.alice-supervision/research/c1-next-mainline-planner-20260821.md`
- Verdict: NEEDS_USER_DECISION
- Stage status: DRAFT

## Evidence Reviewed

- Current C1 closure: S1-S4 `USER_ACCEPTED`; F1-F4 business `10f4cd7`, governance `3644429`, supervisor review PASS.
- Planner report cited above, verified against `docs/PRODUCT_ARCHITECTURE_ROADMAP.md:249-304`, `docs/AI_PLAYER_DESIGN.md`, `docs/EXECUTION_FRAMEWORK.md`, `docs/PATHING_REFACTOR.md`, `docs/HANDOVER.md`, and `docs/SUPERVISION_PROTOCOL.md`.
- User selected direction A: inventory-transfer transaction design.

## Adopted Route

Adopt the planner's ordering:

1. A0 `inventory-transfer-transaction-design-v1`: a strictly readonly design/shallow-research package.
2. B0 `AttackTask` only as a later separate behavior package, requiring an independent fixed-version attack-semantic investigation and user policy decisions.
3. A1 restricted transfer implementation only as a later C2 package; A0 does not authorize it.
4. C0 old-save diagnosis remains independent and frozen unless the user explicitly reopens it.

A0 is the correct next foundational package because it defines the safety contract between stable C1 readonly facts and any future C2 write. It must not call `IItemHandler.insertItem/extractItem`, mutate inventory, add commands/GUI/tasks, create descriptors/endpoints, or infer machine roles from C1 raw slots.

## Required User Decisions Before A0 Plan Publication

1. First research endpoint: choose either conservative original chest <-> bot inventory only, or name a fixed target mod/container/version. Recommendation: original chest <-> bot inventory only.
2. Future C2 transport mode to model in A0: choose real bot-inventory transport, endpoint-to-endpoint direct transfer, or require a side-by-side comparison before choosing. Recommendation: comparison only; no future mode is selected or implemented by A0.
3. Confirm A0 is limited to documents/local source review and does not authorize simulation or actual write calls. Recommendation: confirm.

## Boundaries and Stop Conditions

- Deep research is not needed for A0 unless local source/version evidence cannot define the chosen fixed endpoint. A1 requires a fresh deep-research decision before any write behavior, except where a later supervisor review documents a fixed local source proof sufficient for an original chest-only scope.
- Stop A0 and return for replanning if it requires endpoint/side semantics, machine role inference, GUI automation, persistence/network format, LLM, adapters, any actual/simulated transfer call, or a code change.
- A0 has no client matrix because it is readonly design only. A1 will require compile/headless conservation and failure-recovery fixtures plus a Windows client matrix.
- No C2/C3/C4, AttackTask, old-save diagnosis, LLM, task/movement, `HARD_PATH`, or `SOFT_SURFACE` implementation is authorized by this route review.
