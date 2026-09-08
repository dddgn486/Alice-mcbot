# A1.1 Transfer Draft Audit

- Date: 2026-08-21
- Baseline: `3644429d2d88e1bb6fd1eb24654d828d1451d7f8`
- Scope: supervisor static audit only; no business edits and no transfer calls.
- Target: uncommitted `src/main/java/com/dddgn/alice/transfer/` draft.
- Verdict: INCOMPLETE / NOT ACCEPTABLE AS-IS

## Positive Reusable Structure

- `TransferRequest` fixes request UUID, actor/bot UUID, chest endpoints, item id and positive count.
- `ChestEndpointRef` checks loaded chunk, original `minecraft:chest`, single chest and unsided handler candidate.
- `InventoryObservation` copies slot facts for 36 bot slots and handler slots.
- `CapacityPreflight` provides a pure full-count capacity calculation.
- `TransferLedgerData` is independent `SavedData` with request transitions and proven location fields.
- `ChestBotTransferPrimitive` separates source->bot and bot->destination legs and attempts fresh pre/post observations.

## Blocking Findings

1. **No orchestration or authorization surface**: no `TransferTask`, no explicit permission-level-2 command, no readonly status, no ledger-only abort, no replacement guard, no HARD_PATH movement integration, no timeout/suspension handling, and no focused fixtures. The draft cannot produce the approved A1.1 behavior.
2. **Mutation boundary is not closed**: `ChestBotTransferPrimitive.sourceChestToBot` performs source actual extraction and then bot writes without persisting `IN_TRANSIT_BOT` between measured source post and later movement. The primitive must remain a leg primitive; orchestration must persist ledger state before any destination movement.
3. **Destination failure semantics are not owned**: `botToDestinationChest` removes bot items before actual destination insertion. Any remainder or post mismatch must preserve/locate bot ownership or enter `UNKNOWN_DISCREPANCY`; this needs ledger/state-machine integration and must never trigger blind re-extraction.
4. **Endpoint failures collapse to the wrong code**: public primitive methods return `ENDPOINT_NOT_SINGLE_CHEST` for every `validate` null, losing `ENDPOINT_NOT_LOADED`, handler unavailable, cross-dimension, and invalid-item distinctions. The task/command must retain stable codes from validation.
5. **Capacity calculation does not prove slot validity**: `CapacityPreflight.acceptedBy` treats empty slots as capacity without an `isItemValid` decision. Fixed Forge semantics state validity and current capacity are separate; implementation must use approved source-specific checks/fixture evidence and reject when the exact full request cannot be proven.
6. **Exact predicate evidence is incomplete**: `InventoryObservation` and primitive matching use `ItemStack.hasTag()` as the only non-default guard. The approved policy is no components/NBT; development must confirm the fixed 1.20.1 representation and explicitly reject unsupported data rather than silently treating registry-id-only as exact.
7. **Bot inventory writes are direct data mutations**: `setItem`, `grow`, and `shrink` do not by themselves establish client synchronization or transaction evidence. A1.1 needs server-side post observations and must leave client sync/visible in-transit behavior for Windows acceptance.
8. **Endpoint identity is not carried into observation identity fully**: observation strings use position and bot UUID but omit dimension and robust block/entity identity; same-dimension is policy, but identity evidence should still include immutable endpoint identity and current validation.
9. **No restart/stop policy implementation**: current ledger load alone does not reconcile a complete bot inventory; A1.1 must explicitly block active restart continuation or produce manual takeover evidence, without auto-resume.

## Required Acceptance Before Commit

- Run `./tools/work-session-start.sh` first in the new developer session.
- Audit and correct the draft before integration.
- Add command/task/status/abort/replacement guard and HARD_PATH-only orchestration within active plan.
- Persist `IN_TRANSIT_BOT` immediately after proven source delta and before destination movement.
- Add focused fixtures asserting request id, state, stable code, location and source/bot/destination delta for normal, capacity, conflict, mismatch, duplicate, hazard, timeout, restart and replacement cases.
- Run `./gradlew compileJava`, commit only approved files, run `./tools/session-complete.sh`, and leave Windows behavior as `CLIENT_TEST_PENDING`.

This audit is a supervisor review artifact, not implementation authorization beyond the existing active plan and does not claim any client acceptance.
