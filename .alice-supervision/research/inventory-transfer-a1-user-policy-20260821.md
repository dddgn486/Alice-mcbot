# Inventory Transfer A1 User Policy Input

- Date: 2026-08-21
- Source decision: user-provided policy direction after A0 design closure.
- Status: planning input only; not an A1 implementation authorization.
- Baseline: `3644429d2d88e1bb6fd1eb24654d828d1451d7f8`

## User Direction Recorded

1. Transport mode: future transfers use real bot-inventory transport, not endpoint-to-endpoint direct transfer.
2. First experimental endpoint: original chests only. Mod containers and fluids are later, separate compatibility/temporary-tank work.
3. Fulfilment behavior: before delivery, determine whether the bot has enough required material; if not, take the deficit from a specified storage area; if enough, deliver to the destination/storage endpoint.
4. Future item policy: exact item, tag matching, maximum quantity and configurable defaults are desired. First implementation should use exact item identity and treat inability to complete as failure/report, not partial success.
5. Future semantic storage marking: a marked item/storage area is desired so marked materials are not misclassified as task items.
6. Future permissions: ownership/admin distinctions are desired, but no full permission policy is required in the first experimental stage.
7. Recovery direction: survival takes priority; interruption/timeouts should preserve completed progress and retry with bounded attempts; persistent failure reports and transitions to safe default work/standby.

## Supervisor Interpretation and Required Constraints

- Exact item identity must mean a registry id (`namespace:item`), not a localized/display name. Tags, aliases and component/NBT equivalence are later explicit policies.
- “Specified storage area” must first mean an explicit selected chest endpoint identity (dimension + immutable position + container identity), not automatic area scanning or generic capability discovery.
- The first C2 chain must not add a physical “item marking zone.” That is a future WorldProfile/storage-label package with explicit classifications such as `source`, `destination`, `supply`, `protected`, `task-reserved`; it must not infer role from C1 raw slots.
- A future first transaction must record a request id and immutable item/delta facts. It may retry automatically only before any mutation or after evidence proves `not_moved`. Once extraction succeeds, retries must resume the same transaction and preserve the recorded `in_transit=bot_inventory` location; they must never issue a new blind extraction.
- Current `SurvivalSystem` is interruption-only for hard hazards; it does not yet implement a general automatic recovery executor. Therefore a first C2 package must report `SURVIVAL_INTERRUPTED` with item location and suspend/recover conservatively. It cannot promise that survival recovery time is excluded from timeout until a separate recovery capability exists.
- Distance to a destination can be telemetry/progress display only. It is not transaction truth and cannot establish item ownership or recovery state. Transaction progress is derived from measured source/bot/destination deltas.
- “No initial permissions” must not mean unrestricted multi-player writes. The first experimental package needs a conservative bootstrap gate, recommended as the command initiator matching the bot owner in a controlled/single-player environment; full owner/admin/ACL policy is a later package.
- After terminal failure, recommended first default is `idle` with a durable report, not automatic mining/follow/other default task. A later policy may opt into safe fallback work only after ownership and task interaction rules exist.

## Recommended Incremental Roadmap

1. **A1.1 transaction primitive**: fixed source chest -> bot inventory and bot inventory -> fixed destination chest, exact registry item/count, one explicit request, no partial success, `HARD_PATH` only, immutable ledger and measured pre/post deltas.
2. **A1.2 fulfilment orchestrator**: compare usable bot inventory against one exact request; if deficient, execute only the specified source leg for the deficit, then destination leg. No automatic endpoint selection.
3. **A1.3 storage labels/marking**: persistent user-defined WorldProfile/storage labels and protected/reserved item policies. No scanning-based role inference.
4. **A1.4 policy/options**: ownership/admin permissions, tag/item-component matching, maximum quantities, partial-success options, retry/fallback policy.
5. **Later compatibility lines**: fixed-version mod containers, side semantics, fluids/tanks, and adapters, each separately researched and tested.

## Remaining Required A1 Decisions

Before a C2 implementation plan, user must confirm:

1. First allowed direction: chest -> bot -> chest as an end-to-end request, or source chest -> bot only as a primitive-first experiment.
2. First exact item/count and bot capacity rule; recommended: request fails preflight when the required total cannot fit the bot inventory, with no extraction.
3. Bootstrap authorization rule; recommended: only explicit command initiator controlling the bot in a controlled/single-player experiment.
4. Survival interruption behavior for a held `in_transit` item; recommended: suspend transaction, retain bot inventory/location evidence, do not count only active transfer progress against the operation budget, but enforce a separate maximum suspension duration and then require manual takeover.
5. Retry budget and timeout model; recommended: phase-specific no-progress timeout plus absolute wall/server-tick deadline, excluding only an explicitly recorded `SURVIVAL_SUSPENDED` duration, never silently repeating extraction.

## Non-Authorization

This record does not authorize `IItemHandler` simulation/write calls, inventory mutation, endpoint registry/descriptor, storage marking, permissions implementation, C2/C3/C4, mod/fluid compatibility, task/movement changes, or client tests.
