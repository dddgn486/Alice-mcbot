# Alice Active Work Plan

- Plan ID: `YYYYMMDD-topic-vN`
- Status: DRAFT | NEEDS_USER_DECISION | CLIENT_TEST_PENDING | APPROVED_FOR_IMPLEMENTATION | BLOCKED | COMPLETED
- Created from: `<review file, commit, or user decision>`
- Approved by: `<user decision reference; required for APPROVED_FOR_IMPLEMENTATION>`
- Scope owner: `Alice project supervisor`

## Objective

State one concrete outcome for this implementation slice.

## Why This Slice

Explain why this is the smallest useful increment now, including the complexity and verification tradeoff.

## Delivery Lane

- Lane: `mainline` | `independent` | `maintenance` | `frozen-diagnostic`.
- Relationship to active blockers: `<isolated / depends on / resolves>`.
- Why this package preserves overall project throughput: `<what it advances while other work is pending>`.

## Allowed Scope

- Files/modules the work session may change.
- Public behavior that may change.
- Required implementation constraints.

## Explicitly Out Of Scope

- Adjacent ideas that must not be added this round.
- Architecture boundaries that remain frozen.

## Preconditions

- Required prior client-test result or user decision.
- Required current Git revision or document state.

## Research Decision

- Supervisor shallow research: `<summary of Alice code/docs/R# evidence and why it is sufficient, or why it is not>`.
- Deep research: `Not required` | `Required before implementation` | `Completed`.
- Required report path(s): `<.alice-supervision/research/...>`.
- Report conclusions adopted by this plan: `<specific conclusions only; a report alone is never implementation authorization>`.
- Rejected/expired research conclusions: `<material conclusions not adopted, or why a prior report no longer applies>`.
- Evidence confidence and version boundary: `<local fact / fixed upstream commit / weak secondary source; target MC/Forge/mod versions>`.

## Implementation Steps

1. One small, reversible step.
2. One verification-oriented step.
3. Stop condition and handoff point.

## Client Test Entry

Required whenever public or client-visible behavior changes.

- Entry type and exact command/tool interaction.
- Input contract: semantic target, entity, foot position, support block, or other explicit form.
- Isolation: tasks/call chains the entry must not invoke.
- Observable evidence: stable log/result keys, screenshots/video where needed, and bot final position.
- Stop/recovery contract: what stops the scenario and how the bot remains recoverable.
- Promotion boundary: what separate plan and evidence are required before this experiment may enter a normal tool or task.

## Verification Evidence

- Required compile/headless checks.
- Required log evidence.
- Client scenarios that remain `CLIENT_TEST_PENDING`.

## User Acceptance Gate

State exactly what the user must review before this feature or the next phase can be accepted. Use `Not required for this internal-only slice` only when no client-visible behavior changes.

## Handoff Rules

- Main work session must update `docs/HANDOVER.md`, commit, push `origin/master`, and run `./tools/session-complete.sh`.
- Any discovery that changes this scope stops implementation and returns to the supervisor for replanning.
